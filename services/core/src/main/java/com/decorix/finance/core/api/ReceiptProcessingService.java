package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ReceiptApi.CreateRequest;
import com.decorix.finance.core.api.ReceiptApi.ReceiptResponse;
import com.decorix.finance.core.api.ReceiptProcessingApi.ReceiptProcessingJob;
import com.decorix.finance.core.domain.ReceiptReconciliationPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReceiptProcessingService {
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final RowMapper<ReceiptProcessingJob> JOB_MAPPER = (rs, row) -> new ReceiptProcessingJob(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
            rs.getObject("document_id", UUID.class), rs.getString("state"), rs.getString("stage"),
            rs.getInt("progress_percent"), rs.getInt("attempt_count"), "retryable".equals(rs.getString("state")),
            rs.getString("error_code"), rs.getObject("receipt_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ReceiptObjectStorage storage;
    private final ReceiptMalwareScanner scanner;
    private final ReceiptOcrClient ocr;
    private final ReceiptVisionClient vision;
    private final ReceiptService receipts;
    private final ObjectMapper json;

    public ReceiptProcessingService(JdbcTemplate jdbc, TransactionTemplate transactions, ReceiptObjectStorage storage,
            ReceiptMalwareScanner scanner, ReceiptOcrClient ocr, ReceiptVisionClient vision,
            ReceiptService receipts, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.storage = storage;
        this.scanner = scanner;
        this.ocr = ocr;
        this.vision = vision;
        this.receipts = receipts;
        this.json = json;
    }

    public ReceiptProcessingJob upload(UUID tenantId, String subject, String idempotencyKey,
            String originalName, byte[] bytes) {
        validateKey(idempotencyKey);
        ReceiptImageValidator.DetectedImage detected;
        try {
            detected = ReceiptImageValidator.validate(bytes);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt image is invalid or unsupported", invalid);
        }
        Member member = transactions.execute(status -> requireWritableMember(tenantId, subject));
        String hash = sha256(bytes);
        ReceiptProcessingJob existing = transactions.execute(status -> {
            setTenant(tenantId);
            return findByKey(tenantId, member.userId(), idempotencyKey);
        });
        if (existing != null) return replayOrConflict(existing, hash, tenantId, member.userId(), idempotencyKey);

        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String storageKey = "quarantine/" + tenantId + "/" + documentId + "." + detected.extension();
        String displayName = displayName(originalName, detected.extension());
        storage.put(storageKey, bytes, detected.mimeType());
        try {
            ReceiptProcessingJob inserted = transactions.execute(status -> {
                setTenant(tenantId);
                acquireIdempotencyLock(tenantId, member.userId(), idempotencyKey);
                ReceiptProcessingJob raced = findByKey(tenantId, member.userId(), idempotencyKey);
                if (raced != null) return replayOrConflict(raced, hash, tenantId, member.userId(), idempotencyKey);
                jdbc.update("""
                        INSERT INTO documents
                          (id, tenant_id, uploaded_by_user_id, storage_key, content_sha256, mime_type,
                           byte_size, scan_state, original_name)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?)
                        """, documentId, tenantId, member.userId(), storageKey, hash, detected.mimeType(),
                        (long) bytes.length, displayName);
                jdbc.update("""
                        INSERT INTO receipt_processing_jobs
                          (id, tenant_id, owner_user_id, owner_subject, document_id, state, stage,
                           progress_percent, attempt_count, idempotency_key, request_hash)
                        VALUES (?, ?, ?, ?, ?, 'queued', 'queued', 0, 0, ?, ?)
                        """, jobId, tenantId, member.userId(), subject, documentId, idempotencyKey, hash);
                return findById(tenantId, jobId);
            });
            if (!documentId.equals(inserted.documentId())) storage.delete(storageKey);
            return inserted;
        } catch (RuntimeException error) {
            try { storage.delete(storageKey); } catch (RuntimeException cleanupFailure) { error.addSuppressed(cleanupFailure); }
            throw error;
        }
    }

    public ReceiptProcessingJob get(UUID tenantId, String subject, UUID jobId) {
        return transactions.execute(status -> {
            Member member = requireWritableOrReadOnlyMember(tenantId, subject);
            List<ReceiptProcessingJob> jobs = jdbc.query("""
                    SELECT id, tenant_id, document_id, state, stage, progress_percent, attempt_count,
                           error_code, receipt_id, created_at, updated_at
                    FROM receipt_processing_jobs
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
                    """, JOB_MAPPER, tenantId, member.userId(), jobId);
            if (jobs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt job not found");
            return jobs.get(0);
        });
    }

    public boolean processNext() {
        ClaimedJob job = claimNext();
        if (job == null) return false;
        process(job);
        return true;
    }

    private void process(ClaimedJob job) {
        Document document = loadDocument(job);
        byte[] image;
        try {
            image = storage.get(document.storageKey());
        } catch (RuntimeException unavailable) {
            retry(job, "STORAGE_UNAVAILABLE");
            return;
        }
        try {
            var detected = ReceiptImageValidator.validate(image);
            if (!detected.mimeType().equals(document.mimeType()) || !sha256(image).equals(document.sha256())) {
                reject(job, "DOCUMENT_CORRUPT");
                return;
            }
        } catch (IllegalArgumentException invalid) {
            reject(job, "DOCUMENT_CORRUPT");
            return;
        }

        ClamAvReceiptScanner.ScanResult scanResult;
        try {
            scanResult = scanner.scan(image);
        } catch (RuntimeException unavailable) {
            retry(job, "SCANNER_UNAVAILABLE");
            return;
        }
        if (scanResult == ClamAvReceiptScanner.ScanResult.INFECTED) {
            reject(job, "MALWARE_DETECTED");
            return;
        }
        ReceiptVisionClient.VisionReading visionReading = null;
        String visionFallbackReason = null;
        if (vision.isConfigured()) {
            updateStage(job, "vision", 30);
            try {
                visionReading = vision.read(image, job.id());
            } catch (ReceiptVisionClient.VisionUnavailableException unavailable) {
                visionFallbackReason = unavailable.errorCode();
            } catch (IllegalArgumentException invalidResponse) {
                visionFallbackReason = "VISION_INVALID_RESPONSE";
            } catch (RuntimeException unavailable) {
                visionFallbackReason = "VISION_UNAVAILABLE";
            }
        }
        updateStage(job, "ocr", 45);

        ReceiptOcrClient.OcrReading ocrReading = null;
        String ocrFallbackReason = null;
        try {
            ocrReading = ocr.read(image, job.id());
        } catch (IllegalArgumentException invalidResponse) {
            if (visionReading == null) {
                retry(job, "OCR_INVALID_RESPONSE");
                return;
            }
            ocrFallbackReason = "OCR_INVALID_RESPONSE";
        } catch (RuntimeException unavailable) {
            if (visionReading == null) {
                retry(job, "OCR_UNAVAILABLE");
                return;
            }
            ocrFallbackReason = "OCR_UNAVAILABLE";
        }
        try {
            complete(job, visionReading, ocrReading, visionFallbackReason, ocrFallbackReason);
        } catch (RuntimeException databaseFailure) {
            retry(job, "DRAFT_CREATION_FAILED");
        }
    }

    private void complete(ClaimedJob job, ReceiptVisionClient.VisionReading visionReading,
            ReceiptOcrClient.OcrReading ocrReading, String visionFallbackReason, String ocrFallbackReason) {
        transactions.executeWithoutResult(status -> {
            setTenant(job.tenantId());
            jdbc.update("UPDATE documents SET scan_state = 'ready' WHERE tenant_id = ? AND id = ? AND uploaded_by_user_id = ?",
                    job.tenantId(), job.documentId(), job.ownerUserId());
            ReceiptResponse receipt = receipts.create(job.tenantId(), job.ownerSubject(), "receipt-job-" + job.id(),
                    new CreateRequest(job.documentId(), null, null, null, List.of()));
            ReceiptReconciliationPolicy.Result reconciliation = reconcile(ocrReading, visionReading);
            String selectedReader = reconciliation.selectedReader() == null
                    ? (ocrReading == null ? (visionReading == null ? null : "vision")
                            : visionReading == null ? "ocr" : null)
                    : reconciliation.selectedReader().name().toLowerCase(Locale.ROOT);
            jdbc.update("UPDATE receipts SET selected_reader = ? WHERE tenant_id = ? AND owner_user_id = ? AND id = ?",
                    selectedReader, job.tenantId(), job.ownerUserId(), receipt.id());
            if (visionReading != null) saveVisionReading(job, receipt.id(), visionReading, ocrFallbackReason);
            if (ocrReading != null) saveOcrReading(job, receipt.id(), ocrReading, visionFallbackReason, ocrFallbackReason);
            jdbc.update("""
                    UPDATE receipt_processing_jobs
                    SET state = 'completed', stage = 'complete', progress_percent = 100, receipt_id = ?,
                        lease_expires_at = NULL, error_code = NULL, updated_at = now()
                    WHERE tenant_id = ? AND id = ? AND state = 'running'
                    """, receipt.id(), job.tenantId(), job.id());
        });
    }

    private void saveVisionReading(ClaimedJob job, UUID receiptId, ReceiptVisionClient.VisionReading reading,
            String ocrFallbackReason) {
        java.util.Map<String, Object> resultFields = new java.util.LinkedHashMap<>();
        resultFields.put("store", reading.store());
        resultFields.put("date", reading.date());
        resultFields.put("total", reading.total());
        resultFields.put("items", reading.items());
        String fields = serialize(resultFields);
        java.util.Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("provider", reading.provider());
        evidence.put("modelVersion", reading.modelVersion());
        evidence.put("promptVersion", reading.promptVersion());
        if (reading.fallbackReason() != null) evidence.put("fallbackReason", reading.fallbackReason());
        if (ocrFallbackReason != null) evidence.put("ocrFallbackReason", ocrFallbackReason);
        jdbc.update("""
                INSERT INTO receipt_readings
                  (tenant_id, receipt_id, source_job_id, reader, provider, model_version, prompt_version,
                   algorithm_version, result_fields, field_evidence)
                VALUES (?, ?, ?, 'vision', ?, ?, ?, 'receipt-vision.v1', CAST(? AS jsonb), CAST(? AS jsonb))
                ON CONFLICT (tenant_id, source_job_id, reader) WHERE source_job_id IS NOT NULL DO NOTHING
                """, job.tenantId(), receiptId, job.id(), reading.provider(), reading.modelVersion(),
                reading.promptVersion(), fields, serialize(evidence));
    }

    private void saveOcrReading(ClaimedJob job, UUID receiptId, ReceiptOcrClient.OcrReading reading,
            String visionFallbackReason, String ocrFallbackReason) {
        java.util.Map<String, Object> resultFields = new java.util.LinkedHashMap<>();
        resultFields.put("text", reading.text());
        resultFields.put("words", reading.words());
        resultFields.put("total", reading.total() == null ? null : reading.total().toPlainString());
        resultFields.put("items", reading.items());
        String fields = serialize(resultFields);
        java.util.Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("confidence", reading.confidence() == null ? 0 : reading.confidence());
        evidence.put("source", "local-ocr");
        if (visionFallbackReason != null) evidence.put("visionFallbackReason", visionFallbackReason);
        if (ocrFallbackReason != null) evidence.put("ocrFallbackReason", ocrFallbackReason);
        jdbc.update("""
                INSERT INTO receipt_readings
                  (tenant_id, receipt_id, source_job_id, reader, provider, model_version, prompt_version,
                   algorithm_version, result_fields, field_evidence, confidence)
                VALUES (?, ?, ?, 'ocr', ?, ?, ?, 'receipt-ocr.v1', CAST(? AS jsonb), CAST(? AS jsonb), ?)
                ON CONFLICT (tenant_id, source_job_id, reader) WHERE source_job_id IS NOT NULL DO NOTHING
                """, job.tenantId(), receiptId, job.id(), reading.provider(), reading.modelVersion(),
                reading.promptVersion(), fields, serialize(evidence), reading.confidence());
    }

    private static ReceiptReconciliationPolicy.Result reconcile(ReceiptOcrClient.OcrReading ocr,
            ReceiptVisionClient.VisionReading vision) {
        ReceiptReconciliationPolicy.Reading ocrEvidence = ocr == null ? null
                : new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.OCR, ocr.total(),
                        null, null, ocr.items().stream().map(item -> new ReceiptReconciliationPolicy.Item(
                                item.name(), decimal(item.lineSum()))).toList());
        ReceiptReconciliationPolicy.Reading visionEvidence = vision == null ? null
                : new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.VISION,
                        decimal(vision.total()), null, null, vision.items().stream()
                                .map(item -> new ReceiptReconciliationPolicy.Item((String) item.get("name"),
                                        decimal(item.get("lineSum"))))
                                .toList());
        return ReceiptReconciliationPolicy.evaluate(ocrEvidence, visionEvidence);
    }

    private static BigDecimal decimal(Object amount) {
        return amount == null ? null : new BigDecimal(String.valueOf(amount));
    }

    private ClaimedJob claimNext() {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.receipt_worker', 'true', true)", String.class);
            List<ClaimedJob> jobs = jdbc.query("""
                    UPDATE receipt_processing_jobs
                    SET state = 'running', stage = 'scanning', progress_percent = greatest(progress_percent, 10),
                        attempt_count = attempt_count + 1, lease_expires_at = now() + (? * interval '1 second'),
                        error_code = NULL, updated_at = now()
                    WHERE id = (
                        SELECT id FROM receipt_processing_jobs
                        WHERE attempt_count < ? AND next_attempt_at <= now()
                          AND (state IN ('queued', 'retryable') OR (state = 'running' AND lease_expires_at < now()))
                        ORDER BY created_at, id
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                    )
                    RETURNING id, tenant_id, owner_user_id, owner_subject, document_id, attempt_count
                    """, (rs, row) -> new ClaimedJob(rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                    rs.getString("owner_subject"), rs.getObject("document_id", UUID.class), rs.getInt("attempt_count")),
                    LEASE.toSeconds(), MAX_ATTEMPTS);
            return jobs.isEmpty() ? null : jobs.get(0);
        });
    }

    private Document loadDocument(ClaimedJob job) {
        return transactions.execute(status -> {
            setTenant(job.tenantId());
            List<Document> documents = jdbc.query("""
                    SELECT storage_key, content_sha256, mime_type FROM documents
                    WHERE tenant_id = ? AND id = ? AND uploaded_by_user_id = ?
                    """, (rs, row) -> new Document(rs.getString("storage_key"), rs.getString("content_sha256"),
                    rs.getString("mime_type")), job.tenantId(), job.documentId(), job.ownerUserId());
            if (documents.isEmpty()) throw new IllegalStateException("Receipt document is missing");
            return documents.get(0);
        });
    }

    private void updateStage(ClaimedJob job, String stage, int progress) {
        transactions.executeWithoutResult(status -> {
            setTenant(job.tenantId());
            jdbc.update("""
                    UPDATE receipt_processing_jobs
                    SET stage = ?, progress_percent = greatest(progress_percent, ?), updated_at = now()
                    WHERE tenant_id = ? AND id = ? AND state = 'running'
                    """, stage, progress, job.tenantId(), job.id());
        });
    }

    private void retry(ClaimedJob job, String errorCode) {
        int delaySeconds = (int) Math.min(300, 5L << Math.max(0, job.attemptCount() - 1));
        transactions.executeWithoutResult(status -> {
            setTenant(job.tenantId());
            jdbc.update("""
                    UPDATE receipt_processing_jobs
                    SET state = CASE WHEN attempt_count >= ? THEN 'rejected' ELSE 'retryable' END,
                        next_attempt_at = now() + (? * interval '1 second'), lease_expires_at = NULL,
                        error_code = ?, updated_at = now()
                    WHERE tenant_id = ? AND id = ? AND state = 'running'
                    """, MAX_ATTEMPTS, delaySeconds, errorCode, job.tenantId(), job.id());
        });
    }

    private void reject(ClaimedJob job, String errorCode) {
        transactions.executeWithoutResult(status -> {
            setTenant(job.tenantId());
            jdbc.update("UPDATE documents SET scan_state = 'rejected' WHERE tenant_id = ? AND id = ?",
                    job.tenantId(), job.documentId());
            jdbc.update("""
                    UPDATE receipt_processing_jobs
                    SET state = 'rejected', lease_expires_at = NULL, error_code = ?, updated_at = now()
                    WHERE tenant_id = ? AND id = ? AND state = 'running'
                    """, errorCode, job.tenantId(), job.id());
        });
    }

    private Member requireWritableMember(UUID tenantId, String subject) {
        Member member = requireWritableOrReadOnlyMember(tenantId, subject);
        if ("viewer".equals(member.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Viewer access is read-only");
        }
        return member;
    }

    private Member requireWritableOrReadOnlyMember(UUID tenantId, String subject) {
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        }
        setTenant(tenantId);
        List<Member> members = jdbc.query("""
                SELECT m.user_id, m.role FROM memberships m
                JOIN external_identities i ON i.user_id = m.user_id
                WHERE m.tenant_id = ? AND i.provider = 'keycloak' AND i.subject = ? AND m.status = 'active'
                """, (rs, row) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("role")), tenantId, subject);
        if (members.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        return members.get(0);
    }

    private ReceiptProcessingJob findByKey(UUID tenantId, UUID userId, String key) {
        List<ReceiptProcessingJob> jobs = jdbc.query("""
                SELECT id, tenant_id, document_id, state, stage, progress_percent, attempt_count,
                       error_code, receipt_id, created_at, updated_at
                FROM receipt_processing_jobs
                WHERE tenant_id = ? AND owner_user_id = ? AND idempotency_key = ?
                """, JOB_MAPPER, tenantId, userId, key);
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    private ReceiptProcessingJob findById(UUID tenantId, UUID jobId) {
        List<ReceiptProcessingJob> jobs = jdbc.query("""
                SELECT id, tenant_id, document_id, state, stage, progress_percent, attempt_count,
                       error_code, receipt_id, created_at, updated_at
                FROM receipt_processing_jobs WHERE tenant_id = ? AND id = ?
                """, JOB_MAPPER, tenantId, jobId);
        if (jobs.isEmpty()) throw new IllegalStateException("Receipt processing job was not persisted");
        return jobs.get(0);
    }

    private ReceiptProcessingJob replayOrConflict(ReceiptProcessingJob existing, String hash,
            UUID tenantId, UUID userId, String key) {
        String existingHash = transactions.execute(status -> {
            setTenant(tenantId);
            return jdbc.queryForObject("""
                    SELECT request_hash FROM receipt_processing_jobs
                    WHERE tenant_id = ? AND owner_user_id = ? AND idempotency_key = ?
                    """, String.class, tenantId, userId, key);
        });
        if (!MessageDigest.isEqual(existingHash.getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was used for another receipt image");
        }
        return existing;
    }

    private void acquireIdempotencyLock(UUID tenantId, UUID userId, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {}, tenantId + ":" + userId + ":" + key);
    }

    private void setTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private static String displayName(String originalName, String extension) {
        String candidate = originalName == null ? "" : originalName.replace('\\', '/');
        candidate = candidate.substring(candidate.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        int dot = candidate.lastIndexOf('.');
        if (dot > 0) candidate = candidate.substring(0, dot);
        candidate = candidate.replaceAll("[^\\p{L}\\p{N} _()-]", "_").trim();
        if (candidate.isBlank()) candidate = "receipt";
        if (candidate.length() > 245) candidate = candidate.substring(0, 245);
        return candidate + "." + extension.toLowerCase(Locale.ROOT);
    }

    private static void validateKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid idempotency key");
        }
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new IllegalStateException("Receipt OCR reading could not be persisted", error);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Member(UUID userId, String role) {}
    private record Document(String storageKey, String sha256, String mimeType) {}
    private record ClaimedJob(UUID id, UUID tenantId, UUID ownerUserId, String ownerSubject,
                              UUID documentId, int attemptCount) {}
}
