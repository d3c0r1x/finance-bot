package com.decorix.finance.core.api;

import com.decorix.finance.core.api.BankImportApi.ImportPreview;
import com.decorix.finance.core.api.BankImportApi.ImportRow;
import com.decorix.finance.core.api.BankImportApi.ConfirmRequest;
import com.decorix.finance.core.api.BankImportApi.CategorySelectionRequest;
import com.decorix.finance.core.api.BankImportApi.RowSelectionRequest;
import com.decorix.finance.core.api.BankImportApi.UndoResult;
import com.decorix.finance.core.api.TransactionApi.CreateRequest;
import com.decorix.finance.core.domain.BankImportPolicy;
import com.decorix.finance.core.domain.MerchantCategoryPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class BankImportService {
    private static final RowMapper<StoredImport> IMPORT_MAPPER = (rs, row) -> new StoredImport(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
            rs.getObject("owner_user_id", UUID.class), rs.getString("owner_subject"), rs.getString("parser_version"),
            rs.getString("quality"), rs.getString("period_start"), rs.getString("period_end"),
            rs.getString("parsed_expense_total"), rs.getString("parsed_income_total"),
            rs.getString("expected_expense_total"), rs.getString("expected_income_total"), rs.getString("state"),
            rs.getLong("revision"), rs.getString("commit_idempotency_key"), rs.getString("undo_idempotency_key"),
            rs.getInt("created_count"), rs.getInt("duplicate_count"), rs.getInt("reverted_count"));
    private static final RowMapper<StoredRow> ROW_MAPPER = (rs, row) -> new StoredRow(
            rs.getObject("id", UUID.class), rs.getInt("ordinal"), rs.getString("operation_date"),
            rs.getString("operation_time"), rs.getString("signed_amount"), rs.getString("currency"),
            rs.getString("kind"), rs.getString("merchant"), rs.getString("description"),
            rs.getString("card_last4"), rs.getString("selected_type"), rs.getString("outcome"),
            rs.getObject("transaction_id", UUID.class), rs.getObject("duplicate_of_transaction_id", UUID.class),
            rs.getString("category_code"), rs.getString("category_source"), rs.getString("suggested_category_code"),
            rs.getString("category_confidence"), rs.getString("category_model_version"),
            rs.getString("category_prompt_version"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final TBankParserClient parser;
    private final TransactionService transactions;
    private final MerchantMappingService merchantMappings;
    private final MerchantClassificationAdvisor merchantClassifier;

    public BankImportService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json,
                             TBankParserClient parser, TransactionService transactions,
                             MerchantMappingService merchantMappings, MerchantClassificationAdvisor merchantClassifier) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
        this.parser = parser;
        this.transactions = transactions;
        this.merchantMappings = merchantMappings;
        this.merchantClassifier = merchantClassifier;
    }

    public ImportPreview create(UUID tenantId, String subject, MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 12L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a PDF file no larger than 12 MiB");
        }
        final TBankParserClient.ParsedStatement parsed;
        try {
            parsed = parser.parse(file.getBytes());
        } catch (java.io.IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded PDF could not be read", exception);
        }
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, true);
            var statement = parsed.statement();
            var preview = BankImportPolicy.preview(statement, Map.of());
            UUID importId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO bank_imports (id, tenant_id, owner_user_id, owner_subject, parser_version, quality,
                        period_start, period_end, parsed_expense_total, parsed_income_total, expected_expense_total,
                        expected_income_total, state, revision)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'needs_review', 1)
                    """, importId, tenantId, actor.userId(), subject, parsed.parseVersion(), statement.quality(),
                    LocalDate.parse(parsed.periodStart()), LocalDate.parse(parsed.periodEnd()),
                    new BigDecimal(statement.parsedExpenseTotal()), new BigDecimal(statement.parsedIncomeTotal()),
                    decimalOrNull(statement.expectedExpenseTotal()), decimalOrNull(statement.expectedIncomeTotal()));
            for (BankImportPolicy.OperationInput operation : statement.operations()) {
                jdbc.update("""
                        INSERT INTO bank_import_rows (tenant_id, import_id, ordinal, operation_date, operation_time,
                            signed_amount, currency, kind, merchant, description, card_last4)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, tenantId, importId, operation.ordinal(), LocalDate.parse(operation.operationDate()),
                        LocalTime.parse(operation.operationTime()), new BigDecimal(operation.signedAmount()),
                        operation.currency(), operation.kind(), operation.merchant(), operation.description(),
                        operation.cardLast4());
            }
            audit(tenantId, subject, "bank_import.staged", importId,
                    Map.of("quality", statement.quality(), "rowCount", statement.operations().size(),
                            "parseVersion", parsed.parseVersion()));
            return preview(tenantId, actor.userId(), importId);
        });
    }

    public ImportPreview get(UUID tenantId, String subject, UUID importId) {
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, false);
            return preview(tenantId, actor.userId(), importId);
        });
    }

    public ImportPreview selectRow(UUID tenantId, String subject, UUID importId, UUID rowId, long expectedRevision,
                                   RowSelectionRequest request) {
        if (request == null || expectedRevision < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid import selection");
        }
        String selectedType = request.transactionType();
        if (selectedType != null && !List.of("expense", "income", "refund", "transfer").contains(selectedType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported transaction type");
        }
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, true);
            StoredImport stored = findImport(tenantId, actor.userId(), importId);
            if (!"needs_review".equals(stored.state())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Import is no longer editable");
            }
            List<StoredRow> rows = loadRows(tenantId, importId);
            StoredRow target = rows.stream().filter(row -> row.id().equals(rowId)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Import row not found"));
            Map<Integer, String> selections = selections(rows);
            if (selectedType == null) {
                selections.remove(target.ordinal());
            } else {
                selections.put(target.ordinal(), selectedType);
            }
            try {
                BankImportPolicy.preview(statement(stored, rows), selections);
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selection does not match the operation", exception);
            }
            int revised = jdbc.update("UPDATE bank_imports SET revision = revision + 1, updated_at = now() "
                            + "WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND revision = ? AND state = 'needs_review'",
                    tenantId, actor.userId(), importId, expectedRevision);
            if (revised != 1) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Import preview changed; reload it");
            }
            jdbc.update("UPDATE bank_import_rows SET selected_type = ? WHERE tenant_id = ? AND import_id = ? AND id = ?",
                    selectedType, tenantId, importId, rowId);
            audit(tenantId, subject, "bank_import.row_selected", importId,
                    Map.of("rowId", rowId.toString(), "transactionType", selectedType == null ? "default" : selectedType,
                            "revision", expectedRevision + 1));
            return preview(tenantId, actor.userId(), importId);
        });
    }

    public ImportPreview selectCategory(UUID tenantId, String subject, UUID importId, UUID rowId,
                                        long expectedRevision, CategorySelectionRequest request) {
        if (request == null || expectedRevision < 1 || !MerchantCategoryPolicy.CATEGORIES.contains(request.categoryCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid merchant category is required");
        }
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, true);
            StoredImport stored = findImport(tenantId, actor.userId(), importId);
            requireEditableRevision(stored, expectedRevision);
            List<StoredRow> rows = loadRows(tenantId, importId);
            StoredRow target = rows.stream().filter(row -> row.id().equals(rowId)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Import row not found"));
            BankImportPolicy.Preview selection = policyPreview(stored, rows);
            BankImportPolicy.PreviewRow selected = selection.rows().get(target.ordinal());
            if (!selected.included() || !"expense".equals(selected.transactionType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only included expenses can have a merchant category");
            }
            if (target.merchant() == null || target.merchant().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A merchant name is required to save a category");
            }
            String normalized = MerchantCategoryPolicy.normalizeMerchant(target.merchant());
            merchantMappings.saveForMember(tenantId, actor.userId(), cleanMerchantLabel(target.merchant()), normalized,
                    request.categoryCode());
            int changed = jdbc.update("""
                    UPDATE bank_import_rows SET category_code = ?, category_source = 'human'
                    WHERE tenant_id = ? AND import_id = ? AND id = ?
                    """, request.categoryCode(), tenantId, importId, rowId);
            if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import row not found");
            reviseImport(tenantId, actor.userId(), importId, expectedRevision);
            audit(tenantId, subject, "merchant.mapping_saved", importId,
                    Map.of("rowId", rowId.toString(), "categoryCode", request.categoryCode(),
                            "revision", expectedRevision + 1));
            return preview(tenantId, actor.userId(), importId);
        });
    }

    public ImportPreview classify(UUID tenantId, String subject, UUID importId, long expectedRevision) {
        if (expectedRevision < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Import revision is required");
        ClassificationPlan plan = transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, true);
            StoredImport stored = findImport(tenantId, actor.userId(), importId);
            requireEditableRevision(stored, expectedRevision);
            List<StoredRow> rows = loadRows(tenantId, importId);
            BankImportPolicy.Preview importPreview = policyPreview(stored, rows);
            Map<String, String> mapped = mappingCategories(tenantId, actor.userId());
            Map<String, String> merchantLabels = new java.util.LinkedHashMap<>();
            for (BankImportPolicy.PreviewRow row : importPreview.rows()) {
                if (!row.included() || !"expense".equals(row.transactionType())) continue;
                StoredRow operation = rows.get(row.ordinal());
                if (operation.merchant() == null || operation.merchant().isBlank()) continue;
                String normalized = MerchantCategoryPolicy.normalizeMerchant(operation.merchant());
                if (!mapped.containsKey(normalized)) merchantLabels.putIfAbsent(normalized, cleanMerchantLabel(operation.merchant()));
            }
            if (merchantLabels.size() > 500) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Classify at most 500 unique merchants at a time");
            }
            Map<String, CachedClassification> cached = loadCachedClassifications(tenantId, actor.userId(), merchantLabels.keySet());
            return new ClassificationPlan(actor.userId(), stored.revision(), List.copyOf(merchantLabels.keySet()),
                    Map.copyOf(cached));
        });
        if (plan.merchants().isEmpty()) {
            return transaction.execute(status -> {
                Actor actor = actor(tenantId, subject, false);
                return preview(tenantId, actor.userId(), importId);
            });
        }

        Map<String, CachedClassification> classifications = new HashMap<>(plan.cached());
        List<String> uncached = plan.merchants().stream().filter(merchant -> !classifications.containsKey(merchant)).toList();
        for (int start = 0; start < uncached.size(); start += MerchantCategoryPolicy.MAX_BATCH_SIZE) {
            List<String> batch = uncached.subList(start, Math.min(start + MerchantCategoryPolicy.MAX_BATCH_SIZE, uncached.size()));
            MerchantClassificationAdvisor.Advice advice = merchantClassifier.classify(batch);
            for (MerchantClassificationAdvisor.Classification item : advice.classifications()) {
                classifications.put(item.merchant(), new CachedClassification(item.merchant(), item.categoryCode(),
                        item.confidence(), advice.provider(), advice.modelVersion(), advice.promptVersion()));
            }
        }

        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, true);
            StoredImport stored = findImport(tenantId, actor.userId(), importId);
            requireEditableRevision(stored, plan.revision());
            List<StoredRow> rows = loadRows(tenantId, importId);
            for (CachedClassification item : classifications.values()) saveCachedClassification(tenantId, actor.userId(), item);
            int suggestions = 0;
            for (StoredRow row : rows) {
                if (row.merchant() == null || row.merchant().isBlank()) continue;
                CachedClassification item = classifications.get(MerchantCategoryPolicy.normalizeMerchant(row.merchant()));
                if (item == null) continue;
                suggestions += jdbc.update("""
                        UPDATE bank_import_rows SET suggested_category_code = ?, category_confidence = ?,
                            category_model_version = ?, category_prompt_version = ?
                        WHERE tenant_id = ? AND import_id = ? AND id = ?
                        """, item.categoryCode(), item.confidence(), item.modelVersion(), item.promptVersion(),
                        tenantId, importId, row.id());
            }
            if (suggestions == 0) return preview(tenantId, actor.userId(), importId);
            reviseImport(tenantId, actor.userId(), importId, plan.revision());
            audit(tenantId, subject, "merchant.classification_completed", importId,
                    Map.of("merchantCount", classifications.size(), "suggestionRows", suggestions,
                            "revision", plan.revision() + 1));
            return preview(tenantId, actor.userId(), importId);
        });
    }

    public ImportPreview confirm(UUID tenantId, String subject, UUID importId, long expectedRevision,
                                 String idempotencyKey, ConfirmRequest request) {
        if (request == null || !request.confirmed() || expectedRevision < 1 || !validIdempotencyKey(idempotencyKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirmation and an idempotency key are required");
        }
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, true);
            StoredImport stored = findImportForUpdate(tenantId, actor.userId(), importId);
            if ("committed".equals(stored.state())) {
                if (idempotencyKey.equals(stored.commitIdempotencyKey())) {
                    return preview(tenantId, actor.userId(), importId);
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Import was already committed");
            }
            if (!"needs_review".equals(stored.state())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Import cannot be committed in its current state");
            }
            if (stored.revision() != expectedRevision) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Import preview changed; reload it");
            }

            List<StoredRow> rows = loadRows(tenantId, importId);
            BankImportPolicy.Preview policyPreview = policyPreview(stored, rows);
            Set<String> lockedMerchants = new TreeSet<>();
            for (BankImportPolicy.PreviewRow row : policyPreview.rows()) {
                if (!row.included() || !"expense".equals(row.transactionType())) continue;
                StoredRow operation = rows.get(row.ordinal());
                if (operation.categoryCode() == null && operation.merchant() != null && !operation.merchant().isBlank()) {
                    lockedMerchants.add(MerchantCategoryPolicy.normalizeMerchant(operation.merchant()));
                }
            }
            for (String normalizedMerchant : lockedMerchants) {
                MerchantCategoryLocks.acquire(jdbc, tenantId, actor.userId(), normalizedMerchant);
            }
            Map<String, String> categoryMappings = mappingCategories(tenantId, actor.userId());
            Map<String, Integer> occurrences = new HashMap<>();
            List<ImportCandidate> candidates = new java.util.ArrayList<>();
            List<StoredRow> excludedRows = new java.util.ArrayList<>();
            ZoneId timezone = null;
            for (BankImportPolicy.PreviewRow row : policyPreview.rows()) {
                StoredRow operation = rows.get(row.ordinal());
                if (!row.included()) {
                    excludedRows.add(operation);
                    continue;
                }
                if (timezone == null) {
                    timezone = memberTimezone(tenantId, actor.userId());
                }
                String fingerprint = fingerprint(tenantId, operation);
                int occurrence = occurrences.merge(fingerprint, 1, Integer::sum);
                Instant occurredAt = LocalDateTime.of(LocalDate.parse(operation.operationDate()),
                        LocalTime.parse(operation.operationTime())).atZone(timezone).toInstant();
                String type = row.transactionType();
                String category = "expense".equals(type)
                        ? operation.categoryCode() != null ? operation.categoryCode()
                        : categoryMappings.getOrDefault(normalizedMerchantOrNull(operation.merchant()), "прочее")
                        : categoryFor(type);
                BigDecimal amount = new BigDecimal(row.amount());
                candidates.add(new ImportCandidate(operation, fingerprint, occurrence, UUID.randomUUID(), occurredAt,
                        type, category, amount, operation.merchant() == null || operation.merchant().isBlank()
                        ? operation.description() : operation.merchant()));
            }
            List<ImportOutcome> outcomes = claimImportRows(tenantId, importId, candidates);
            List<ImportCandidate> createdRows = outcomes.stream().filter(outcome -> !outcome.duplicate())
                    .map(ImportOutcome::candidate).toList();
            int duplicateCount = outcomes.size() - createdRows.size();
            insertImportedTransactions(tenantId, actor.userId(), subject, createdRows);
            persistImportOutcomes(tenantId, importId, outcomes, excludedRows);
            recordImportedTransactions(tenantId, subject, actor.userId(), createdRows);
            int committed = jdbc.update("""
                    UPDATE bank_imports SET state = 'committed', revision = revision + 1,
                        commit_idempotency_key = ?, created_count = ?, duplicate_count = ?, committed_at = now(), updated_at = now()
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND state = 'needs_review' AND revision = ?
                    """, idempotencyKey, createdRows.size(), duplicateCount, tenantId, actor.userId(), importId, expectedRevision);
            if (committed != 1) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Import preview changed; reload it");
            }
            audit(tenantId, subject, "bank_import.committed", importId,
                    Map.of("createdCount", createdRows.size(), "duplicateCount", duplicateCount,
                            "revision", expectedRevision + 1));
            return preview(tenantId, actor.userId(), importId);
        });
    }

    public UndoResult undo(UUID tenantId, String subject, UUID importId, long expectedRevision, String idempotencyKey) {
        if (expectedRevision < 1 || !validIdempotencyKey(idempotencyKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A current revision and idempotency key are required");
        }
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, true);
            StoredImport stored = findImportForUpdate(tenantId, actor.userId(), importId);
            if ("reverted".equals(stored.state()) && idempotencyKey.equals(stored.undoIdempotencyKey())) {
                return new UndoResult(importId, stored.state(), stored.revision(), stored.revertedCount(), List.of());
            }
            if (!"committed".equals(stored.state())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a committed import can be undone");
            }
            if (stored.revision() != expectedRevision) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Import batch changed; reload it");
            }
            List<BatchTransaction> created = jdbc.query("""
                    SELECT t.id, t.version, t.status, t.source, t.owner_user_id, t.owner_subject, t.type,
                        t.amount::text, t.currency, t.category_code, t.occurred_at
                    FROM bank_import_rows r JOIN transactions t
                      ON t.tenant_id = r.tenant_id AND t.id = r.transaction_id
                    WHERE r.tenant_id = ? AND r.import_id = ? AND r.outcome = 'created'
                    ORDER BY r.ordinal
                    """, (rs, row) -> new BatchTransaction(rs.getObject("id", UUID.class), rs.getLong("version"),
                    rs.getString("status"), rs.getString("source"), rs.getObject("owner_user_id", UUID.class),
                    rs.getString("owner_subject"), rs.getString("type"), rs.getString("amount"),
                    rs.getString("currency"), rs.getString("category_code"),
                    rs.getTimestamp("occurred_at").toInstant()), tenantId, importId);
            List<UUID> conflicts = created.stream().filter(row -> row.version() != 1 || !"posted".equals(row.status())
                    || !"bank_import".equals(row.source()) || !row.ownerUserId().equals(stored.ownerId())
                    || !row.ownerSubject().equals(stored.ownerSubject())).map(BatchTransaction::id).toList();
            if (!conflicts.isEmpty()) {
                return new UndoResult(importId, stored.state(), stored.revision(), 0, conflicts);
            }
            for (BatchTransaction row : created) {
                transactions.voidTransaction(tenantId, subject, row.id(),
                        "bank-import-undo-" + importId.toString().replace("-", "") + "-" + row.id().toString().replace("-", ""),
                        row.version());
            }
            jdbc.update("DELETE FROM bank_import_dedupe_keys WHERE tenant_id = ? AND import_id = ?", tenantId, importId);
            jdbc.update("UPDATE bank_import_rows SET outcome = 'reverted' WHERE tenant_id = ? AND import_id = ? AND outcome = 'created'",
                    tenantId, importId);
            long newRevision = stored.revision() + 1;
            jdbc.update("""
                    UPDATE bank_imports SET state = 'reverted', revision = ?, undo_idempotency_key = ?,
                        reverted_count = ?, reverted_at = now(), updated_at = now()
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND state = 'committed' AND revision = ?
                    """, newRevision, idempotencyKey, created.size(), tenantId, actor.userId(), importId, expectedRevision);
            audit(tenantId, subject, "bank_import.reverted", importId,
                    Map.of("revertedCount", created.size(), "revision", newRevision));
            return new UndoResult(importId, "reverted", newRevision, created.size(), List.of());
        });
    }

    private Map<String, String> mappingCategories(UUID tenantId, UUID userId) {
        Map<String, String> categories = new HashMap<>();
        merchantMappings.listForMember(tenantId, userId).forEach(mapping ->
                categories.put(mapping.normalizedMerchant(), mapping.categoryCode()));
        return categories;
    }

    private Map<String, CachedClassification> loadCachedClassifications(UUID tenantId, UUID userId,
                                                                          Set<String> merchants) {
        if (merchants.isEmpty()) return Map.of();
        List<CachedClassification> rows = jdbc.query("""
                SELECT normalized_merchant, category_code, confidence, provider, model_version, prompt_version
                FROM merchant_classification_cache
                WHERE tenant_id = ? AND user_id = ? AND prompt_version = ? AND expires_at > now()
                """, (rs, row) -> new CachedClassification(rs.getString("normalized_merchant"),
                rs.getString("category_code"), rs.getBigDecimal("confidence"), rs.getString("provider"),
                rs.getString("model_version"), rs.getString("prompt_version")), tenantId, userId,
                MerchantCategoryPolicy.ALGORITHM_VERSION);
        Map<String, CachedClassification> cached = new HashMap<>();
        for (CachedClassification item : rows) {
            if (merchants.contains(item.normalizedMerchant())) cached.put(item.normalizedMerchant(), item);
        }
        return cached;
    }

    private void saveCachedClassification(UUID tenantId, UUID userId, CachedClassification item) {
        jdbc.update("""
                INSERT INTO merchant_classification_cache (tenant_id, user_id, normalized_merchant, prompt_version,
                    category_code, confidence, provider, model_version, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now() + interval '90 days')
                ON CONFLICT (tenant_id, user_id, normalized_merchant, prompt_version) DO UPDATE SET
                    category_code = EXCLUDED.category_code, confidence = EXCLUDED.confidence,
                    provider = EXCLUDED.provider, model_version = EXCLUDED.model_version,
                    cached_at = now(), expires_at = EXCLUDED.expires_at
                """, tenantId, userId, item.normalizedMerchant(), item.promptVersion(), item.categoryCode(),
                item.confidence(), item.provider(), item.modelVersion());
    }

    private void requireEditableRevision(StoredImport stored, long expectedRevision) {
        if (!"needs_review".equals(stored.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Import is no longer editable");
        }
        if (stored.revision() != expectedRevision) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Import preview changed; reload it");
        }
    }

    private void reviseImport(UUID tenantId, UUID ownerId, UUID importId, long expectedRevision) {
        int revised = jdbc.update("UPDATE bank_imports SET revision = revision + 1, updated_at = now() "
                        + "WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND revision = ? AND state = 'needs_review'",
                tenantId, ownerId, importId, expectedRevision);
        if (revised != 1) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Import preview changed; reload it");
        }
    }

    private static String cleanMerchantLabel(String merchant) {
        if (merchant == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Merchant name is required");
        String label = Normalizer.normalize(merchant, Normalizer.Form.NFKC).replaceAll("\\s+", " ").strip();
        try {
            MerchantCategoryPolicy.normalizeMerchant(label);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Merchant name is invalid", exception);
        }
        return label;
    }

    private static String normalizedMerchantOrNull(String merchant) {
        return merchant == null || merchant.isBlank() ? null : MerchantCategoryPolicy.normalizeMerchant(merchant);
    }

    private ImportPreview preview(UUID tenantId, UUID ownerId, UUID importId) {
        StoredImport stored = findImport(tenantId, ownerId, importId);
        List<StoredRow> rows = loadRows(tenantId, importId);
        BankImportPolicy.Preview policyPreview = policyPreview(stored, rows);
        Map<String, String> categoryMappings = mappingCategories(tenantId, ownerId);
        List<MerchantCategoryPolicy.MerchantSpend> merchantSpends = policyPreview.rows().stream()
                .filter(row -> row.included() && "expense".equals(row.transactionType()))
                .map(row -> new MerchantCategoryPolicy.MerchantSpend(rows.get(row.ordinal()).merchant(),
                        new BigDecimal(row.amount()))).toList();
        Set<String> clarificationMerchants = MerchantCategoryPolicy.clarificationCandidates(merchantSpends,
                categoryMappings).stream().map(MerchantCategoryPolicy.ClarificationCandidate::normalizedMerchant)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<Integer, UUID> rowIds = new HashMap<>();
        rows.forEach(row -> rowIds.put(row.ordinal(), row.id()));
        Map<Integer, RowImportState> rowStates = importRowStates(tenantId, stored, rows, policyPreview);
        int duplicateCount = "needs_review".equals(stored.state())
                ? (int) rowStates.values().stream().filter(RowImportState::duplicate).count() : stored.duplicateCount();
        List<ImportRow> responseRows = policyPreview.rows().stream().map(row -> {
            StoredRow operation = rows.get(row.ordinal());
            RowImportState state = rowStates.get(row.ordinal());
            String normalizedMerchant = normalizedMerchantOrNull(operation.merchant());
            String mappedCategory = normalizedMerchant == null ? null : categoryMappings.get(normalizedMerchant);
            String resolvedCategory = operation.categoryCode() != null ? operation.categoryCode() : mappedCategory;
            String categorySource = operation.categoryCode() != null ? "human"
                    : mappedCategory != null ? "mapping"
                    : operation.suggestedCategoryCode() != null ? "model" : "unknown";
            return new ImportRow(rowIds.get(row.ordinal()), row.ordinal(), row.operationDate(),
                    operation.operationTime().substring(0, 5), row.signedAmount(), row.amount(), row.kind(),
                    row.transactionType(), row.included(), row.selectionSource(), row.exclusionReason(),
                    state.duplicate(), state.duplicateOfTransactionId(), state.outcome(), state.transactionId(),
                    row.merchant(), row.description(), row.cardLast4(), resolvedCategory, categorySource,
                    operation.suggestedCategoryCode(), operation.categoryConfidence(),
                    normalizedMerchant != null && clarificationMerchants.contains(normalizedMerchant));
        }).toList();
        return new ImportPreview(stored.id(), tenantId, stored.state(), stored.revision(), stored.quality(),
                stored.parserVersion(), stored.periodStart(), stored.periodEnd(), stored.parsedExpenseTotal(),
                stored.parsedIncomeTotal(), stored.expectedExpenseTotal(), stored.expectedIncomeTotal(),
                policyPreview.expenseTotal(), policyPreview.incomeTotal(), policyPreview.refundTotal(),
                policyPreview.transferTotal(), policyPreview.excludedTotal(), policyPreview.includedCount(),
                policyPreview.excludedCount(), stored.createdCount(), duplicateCount, responseRows);
    }

    private StoredImport findImport(UUID tenantId, UUID ownerId, UUID importId) {
        List<StoredImport> imports = jdbc.query("""
                SELECT id, tenant_id, owner_user_id, owner_subject, parser_version, quality, period_start::text,
                    period_end::text, parsed_expense_total::text, parsed_income_total::text,
                    expected_expense_total::text, expected_income_total::text, state, revision,
                    commit_idempotency_key, undo_idempotency_key, created_count, duplicate_count, reverted_count
                FROM bank_imports WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
                """, IMPORT_MAPPER, tenantId, ownerId, importId);
        if (imports.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import not found");
        }
        return imports.get(0);
    }

    private StoredImport findImportForUpdate(UUID tenantId, UUID ownerId, UUID importId) {
        List<StoredImport> imports = jdbc.query("""
                SELECT id, tenant_id, owner_user_id, owner_subject, parser_version, quality, period_start::text,
                    period_end::text, parsed_expense_total::text, parsed_income_total::text,
                    expected_expense_total::text, expected_income_total::text, state, revision,
                    commit_idempotency_key, undo_idempotency_key, created_count, duplicate_count, reverted_count
                FROM bank_imports WHERE tenant_id = ? AND owner_user_id = ? AND id = ? FOR UPDATE
                """, IMPORT_MAPPER, tenantId, ownerId, importId);
        if (imports.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import not found");
        }
        return imports.get(0);
    }

    private List<StoredRow> loadRows(UUID tenantId, UUID importId) {
        return jdbc.query("""
                SELECT id, ordinal, operation_date::text, to_char(operation_time, 'HH24:MI:SS') AS operation_time,
                    signed_amount::text, currency, kind, merchant, description, card_last4, selected_type,
                    outcome, transaction_id, duplicate_of_transaction_id, category_code, category_source,
                    suggested_category_code, category_confidence::text, category_model_version, category_prompt_version
                FROM bank_import_rows WHERE tenant_id = ? AND import_id = ? ORDER BY ordinal
                """, ROW_MAPPER, tenantId, importId);
    }

    private BankImportPolicy.Preview policyPreview(StoredImport stored, List<StoredRow> rows) {
        try {
            return BankImportPolicy.preview(statement(stored, rows), selections(rows));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Stored import rows violate the versioned import policy", exception);
        }
    }

    private List<ImportOutcome> claimImportRows(UUID tenantId, UUID importId, List<ImportCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        int[][] claimBatches = jdbc.batchUpdate("""
                INSERT INTO bank_import_dedupe_keys
                    (tenant_id, fingerprint, occurrence_no, import_id, row_id, transaction_id)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, candidates, 250, (PreparedStatement statement, ImportCandidate candidate) -> {
            statement.setObject(1, tenantId);
            statement.setString(2, candidate.fingerprint());
            statement.setInt(3, candidate.occurrence());
            statement.setObject(4, importId);
            statement.setObject(5, candidate.row().id());
            statement.setObject(6, candidate.transactionId());
        });
        int[] claims = Arrays.stream(claimBatches).flatMapToInt(Arrays::stream).toArray();
        List<ImportOutcome> outcomes = new java.util.ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            ImportCandidate candidate = candidates.get(index);
            int claim = claims[index];
            if (claim > 0 || claim == Statement.SUCCESS_NO_INFO) {
                outcomes.add(new ImportOutcome(candidate, false, null));
                continue;
            }
            UUID duplicateOf = jdbc.queryForObject("""
                    SELECT transaction_id FROM bank_import_dedupe_keys
                    WHERE tenant_id = ? AND fingerprint = ? AND occurrence_no = ?
                    """, UUID.class, tenantId, candidate.fingerprint(), candidate.occurrence());
            if (duplicateOf == null) {
                throw new IllegalStateException("A claimed import duplicate has no committed transaction");
            }
            outcomes.add(new ImportOutcome(candidate, true, duplicateOf));
        }
        return List.copyOf(outcomes);
    }

    private void insertImportedTransactions(UUID tenantId, UUID ownerId, String subject,
                                            List<ImportCandidate> createdRows) {
        if (createdRows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO transactions (id, tenant_id, owner_subject, owner_user_id, type, amount,
                    currency, category_code, description, source, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'bank_import', ?)
                """, createdRows, 250, (PreparedStatement statement, ImportCandidate candidate) -> {
            statement.setObject(1, candidate.transactionId());
            statement.setObject(2, tenantId);
            statement.setString(3, subject);
            statement.setObject(4, ownerId);
            statement.setString(5, candidate.type());
            statement.setBigDecimal(6, candidate.amount());
            statement.setString(7, candidate.row().currency());
            statement.setString(8, candidate.category());
            statement.setString(9, candidate.description());
            statement.setTimestamp(10, java.sql.Timestamp.from(candidate.occurredAt()));
        });
    }

    private void persistImportOutcomes(UUID tenantId, UUID importId, List<ImportOutcome> outcomes,
                                       List<StoredRow> excludedRows) {
        if (!outcomes.isEmpty()) {
            jdbc.batchUpdate("""
                    UPDATE bank_import_rows SET outcome = 'created', transaction_id = ?, reclassification_version = 1
                    WHERE tenant_id = ? AND import_id = ? AND id = ?
                    """, outcomes.stream().filter(outcome -> !outcome.duplicate()).toList(), 250,
                    (PreparedStatement statement, ImportOutcome outcome) -> {
                statement.setObject(1, outcome.candidate().transactionId());
                statement.setObject(2, tenantId);
                statement.setObject(3, importId);
                statement.setObject(4, outcome.candidate().row().id());
            });
            jdbc.batchUpdate("""
                    UPDATE bank_import_rows SET outcome = 'duplicate', duplicate_of_transaction_id = ?
                    WHERE tenant_id = ? AND import_id = ? AND id = ?
                    """, outcomes.stream().filter(ImportOutcome::duplicate).toList(), 250,
                    (PreparedStatement statement, ImportOutcome outcome) -> {
                statement.setObject(1, outcome.duplicateOfTransactionId());
                statement.setObject(2, tenantId);
                statement.setObject(3, importId);
                statement.setObject(4, outcome.candidate().row().id());
            });
        }
        if (!excludedRows.isEmpty()) {
            jdbc.batchUpdate("""
                    UPDATE bank_import_rows SET outcome = 'excluded'
                    WHERE tenant_id = ? AND import_id = ? AND id = ?
                    """, excludedRows, 250, (PreparedStatement statement, StoredRow row) -> {
                statement.setObject(1, tenantId);
                statement.setObject(2, importId);
                statement.setObject(3, row.id());
            });
        }
    }

    private void recordImportedTransactions(UUID tenantId, String subject, UUID ownerId,
                                            List<ImportCandidate> createdRows) {
        if (createdRows.isEmpty()) {
            return;
        }
        List<ImportEvent> events = new java.util.ArrayList<>(createdRows.size());
        for (ImportCandidate row : createdRows) {
            UUID eventId = UUID.randomUUID();
            String traceId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            Map<String, Object> transactionState = Map.ofEntries(
                    Map.entry("id", row.transactionId()), Map.entry("tenantId", tenantId),
                    Map.entry("ownerUserId", ownerId), Map.entry("type", row.type()),
                    Map.entry("amount", row.amount().toPlainString()), Map.entry("currency", row.row().currency()),
                    Map.entry("categoryCode", row.category()), Map.entry("description", row.description()),
                    Map.entry("source", "bank_import"), Map.entry("status", "posted"),
                    Map.entry("occurredAt", row.occurredAt()));
            Map<String, Object> payload = Map.ofEntries(
                    Map.entry("event_id", eventId), Map.entry("event_type", "transaction.created"),
                    Map.entry("schema_version", 1), Map.entry("tenant_id", tenantId),
                    Map.entry("aggregate_type", "transaction"), Map.entry("aggregate_id", row.transactionId()),
                    Map.entry("aggregate_version", 1), Map.entry("occurred_at", now), Map.entry("recorded_at", now),
                    Map.entry("producer", "core"), Map.entry("correlation_id", traceId),
                    Map.entry("payload", Map.of("owner_user_id", ownerId, "type", row.type(),
                            "amount", row.amount().toPlainString(), "currency", row.row().currency(),
                            "category_code", row.category(), "description", row.description(),
                            "source", "bank_import", "status", "posted", "financial_occurred_at", row.occurredAt())));
            events.add(new ImportEvent(eventId, row.transactionId(), serialize(payload), serialize(transactionState), traceId));
        }
        jdbc.batchUpdate("""
                INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id, after_state, trace_id)
                VALUES (?, ?, 'transaction.created', 'transaction', ?, CAST(? AS jsonb), ?)
                """, events, 250, (PreparedStatement statement, ImportEvent event) -> {
            statement.setObject(1, tenantId);
            statement.setString(2, subject);
            statement.setObject(3, event.transactionId());
            statement.setString(4, event.transactionState());
            statement.setString(5, event.traceId());
        });
        jdbc.batchUpdate("""
                INSERT INTO outbox_events (event_id, tenant_id, aggregate_type, aggregate_id,
                    aggregate_version, event_type, payload)
                VALUES (?, ?, 'transaction', ?, 1, 'transaction.created', CAST(? AS jsonb))
                """, events, 250, (PreparedStatement statement, ImportEvent event) -> {
            statement.setObject(1, event.eventId());
            statement.setObject(2, tenantId);
            statement.setObject(3, event.transactionId());
            statement.setString(4, event.payload());
        });
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize bank import transaction event", exception);
        }
    }

    private Map<Integer, RowImportState> importRowStates(UUID tenantId, StoredImport stored,
                                                          List<StoredRow> rows,
                                                          BankImportPolicy.Preview policyPreview) {
        Map<Integer, RowImportState> states = new HashMap<>();
        if (!"needs_review".equals(stored.state())) {
            rows.forEach(row -> states.put(row.ordinal(), new RowImportState(
                    "duplicate".equals(row.outcome()), row.duplicateOfTransactionId(), row.outcome(),
                    row.transactionId())));
            return states;
        }
        Map<Integer, StoredRow> byOrdinal = new HashMap<>();
        rows.forEach(row -> byOrdinal.put(row.ordinal(), row));
        Map<String, Integer> occurrences = new HashMap<>();
        for (BankImportPolicy.PreviewRow row : policyPreview.rows()) {
            StoredRow operation = byOrdinal.get(row.ordinal());
            if (operation == null) {
                throw new IllegalStateException("Stored import ordinal is missing");
            }
            if (!row.included()) {
                states.put(row.ordinal(), new RowImportState(false, null, "pending", null));
                continue;
            }
            String fingerprint = fingerprint(tenantId, operation);
            int occurrence = occurrences.merge(fingerprint, 1, Integer::sum);
            List<UUID> existing = jdbc.query("""
                    SELECT transaction_id FROM bank_import_dedupe_keys
                    WHERE tenant_id = ? AND fingerprint = ? AND occurrence_no = ?
                    """, (rs, rowNum) -> rs.getObject("transaction_id", UUID.class), tenantId,
                    fingerprint, occurrence);
            UUID duplicateOf = existing.isEmpty() ? null : existing.get(0);
            states.put(row.ordinal(), new RowImportState(duplicateOf != null, duplicateOf,
                    duplicateOf == null ? "pending" : "duplicate", null));
        }
        return states;
    }

    private ZoneId memberTimezone(UUID tenantId, UUID userId) {
        String timezone = jdbc.queryForObject("SELECT timezone FROM member_profiles WHERE tenant_id = ? AND user_id = ?",
                String.class, tenantId, userId);
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Member profile contains an unsupported timezone", exception);
        }
    }

    private static String fingerprint(UUID tenantId, StoredRow row) {
        String normalizedDescription = Normalizer.normalize(row.description(), Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String account = row.cardLast4() == null ? "unknown-account" : row.cardLast4().trim();
        String canonical = String.join("\n", tenantId.toString(), account, row.operationDate(),
                row.operationTime(), row.currency(), new BigDecimal(row.signedAmount()).setScale(2).toPlainString(),
                normalizedDescription);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String categoryFor(String type) {
        return switch (type) {
            case "income" -> "other_income";
            case "refund" -> "refund";
            case "transfer" -> "bank_transfer";
            default -> "other";
        };
    }

    private static boolean validIdempotencyKey(String key) {
        return key != null && key.length() >= 16 && key.length() <= 128;
    }

    private Actor actor(UUID tenantId, String subject, boolean write) {
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid import scope");
        }
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
        List<Actor> actors = jdbc.query("""
                SELECT m.user_id, m.role FROM memberships m
                JOIN external_identities i ON i.user_id = m.user_id AND i.provider = 'keycloak'
                WHERE m.tenant_id = ? AND m.subject = ? AND m.status = 'active' AND i.subject = ?
                """, (rs, row) -> new Actor(rs.getObject("user_id", UUID.class), rs.getString("role")),
                tenantId, subject, subject);
        if (actors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        }
        Actor actor = actors.get(0);
        if (write && "viewer".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Viewer access is read-only");
        }
        return actor;
    }

    private void audit(UUID tenantId, String subject, String action, UUID importId, Map<String, Object> state) {
        try {
            jdbc.update("""
                    INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id, after_state, trace_id)
                    VALUES (?, ?, ?, 'bank_import', ?, CAST(? AS jsonb), ?)
                    """, tenantId, subject, action, importId, json.writeValueAsString(state), UUID.randomUUID().toString());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot record import audit state", exception);
        }
    }

    private static BankImportPolicy.StatementInput statement(StoredImport stored, List<StoredRow> rows) {
        return new BankImportPolicy.StatementInput(stored.quality(), stored.parsedExpenseTotal(),
                stored.parsedIncomeTotal(), stored.expectedExpenseTotal(), stored.expectedIncomeTotal(),
                rows.stream().map(row -> new BankImportPolicy.OperationInput(row.operationDate(),
                        row.operationTime().substring(0, 5), row.signedAmount(), row.currency(), row.kind(),
                        row.merchant(), row.description(), row.cardLast4(), row.ordinal())).toList());
    }

    private static Map<Integer, String> selections(List<StoredRow> rows) {
        Map<Integer, String> selected = new HashMap<>();
        rows.stream().filter(row -> row.selectedType() != null)
                .forEach(row -> selected.put(row.ordinal(), row.selectedType()));
        return selected;
    }

    private static BigDecimal decimalOrNull(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private record Actor(UUID userId, String role) {}
    private record StoredImport(UUID id, UUID tenantId, UUID ownerId, String ownerSubject, String parserVersion,
                                String quality, String periodStart, String periodEnd, String parsedExpenseTotal,
                                String parsedIncomeTotal, String expectedExpenseTotal, String expectedIncomeTotal,
                                String state, long revision, String commitIdempotencyKey, String undoIdempotencyKey,
                                int createdCount, int duplicateCount, int revertedCount) {}
    private record StoredRow(UUID id, int ordinal, String operationDate, String operationTime, String signedAmount,
                             String currency, String kind, String merchant, String description, String cardLast4,
                             String selectedType, String outcome, UUID transactionId,
                             UUID duplicateOfTransactionId, String categoryCode, String categorySource,
                             String suggestedCategoryCode, String categoryConfidence, String categoryModelVersion,
                             String categoryPromptVersion) {}
    private record CachedClassification(String normalizedMerchant, String categoryCode, BigDecimal confidence,
                                        String provider, String modelVersion, String promptVersion) {}
    private record ClassificationPlan(UUID ownerId, long revision, List<String> merchants,
                                      Map<String, CachedClassification> cached) {}
    private record RowImportState(boolean duplicate, UUID duplicateOfTransactionId, String outcome, UUID transactionId) {}
    private record ImportCandidate(StoredRow row, String fingerprint, int occurrence, UUID transactionId,
                                   Instant occurredAt, String type, String category, BigDecimal amount,
                                   String description) {}
    private record ImportOutcome(ImportCandidate candidate, boolean duplicate, UUID duplicateOfTransactionId) {}
    private record ImportEvent(UUID eventId, UUID transactionId, String payload, String transactionState, String traceId) {}
    private record BatchTransaction(UUID id, long version, String status, String source, UUID ownerUserId,
                                    String ownerSubject, String type, String amount, String currency,
                                    String categoryCode, Instant occurredAt) {}
}
