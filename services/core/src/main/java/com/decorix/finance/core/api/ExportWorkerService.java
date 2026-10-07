package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ExportWorkerApi.Claim;
import com.decorix.finance.core.api.ExportWorkerApi.CompleteRequest;
import com.decorix.finance.core.api.ExportWorkerApi.FailRequest;
import com.decorix.finance.core.api.ExportWorkerApi.SnapshotPage;
import com.decorix.finance.core.api.ExportWorkerApi.SnapshotRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExportWorkerService {
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_PAGE_SIZE = 1000;
    private static final long MAX_OBJECT_BYTES = 134_217_728L;
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern OBJECT_KEY = Pattern.compile(
            "tenants/[0-9a-fA-F-]{36}/exports/[0-9a-fA-F-]{36}/[0-9a-fA-F-]{36}\\.csv");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ExportWorkerService(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public Claim claim() {
        return transaction.execute(status -> {
            setWorkerContext();
            jdbc.update("UPDATE export_jobs SET status='expired', lease_token=NULL, lease_expires_at=NULL, "
                            + "error_code='export_expired', updated_at=now() WHERE status IN ('queued','processing') "
                            + "AND expires_at<=now()");
            jdbc.update("UPDATE export_jobs SET status='failed', lease_token=NULL, lease_expires_at=NULL, "
                            + "error_code='retry_limit', updated_at=now() WHERE status='processing' "
                            + "AND lease_expires_at<=now() AND attempt_count>=?", MAX_ATTEMPTS);
            List<Claim> jobs = jdbc.query("""
                    WITH candidate AS (
                      SELECT id FROM export_jobs
                      WHERE expires_at > now() AND attempt_count < ? AND (
                        (status = 'queued' AND next_attempt_at <= now()) OR
                        (status = 'processing' AND lease_expires_at <= now()))
                      ORDER BY next_attempt_at, created_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                    )
                    UPDATE export_jobs job SET status='processing', attempt_count=attempt_count+1,
                      lease_token=gen_random_uuid(), lease_expires_at=now() + interval '2 minutes', updated_at=now()
                    FROM candidate WHERE job.id=candidate.id
                    RETURNING job.id, job.tenant_id, job.requester_user_id, job.format_version, job.from_date,
                      job.to_date, job.scope_member_id, job.include_all_members, job.requester_timezone,
                      job.snapshot_at, job.row_count, job.lease_token, job.lease_expires_at, job.attempt_count
                    """, (rs, row) -> new Claim(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                    rs.getObject("requester_user_id", UUID.class), rs.getString("format_version"),
                    rs.getObject("from_date", LocalDate.class), rs.getObject("to_date", LocalDate.class),
                    rs.getObject("scope_member_id", UUID.class), rs.getBoolean("include_all_members"),
                    rs.getString("requester_timezone"), rs.getTimestamp("snapshot_at").toInstant(),
                    rs.getInt("row_count"), rs.getObject("lease_token", UUID.class),
                    rs.getTimestamp("lease_expires_at").toInstant(), rs.getInt("attempt_count")), MAX_ATTEMPTS);
            return jobs.stream().findFirst().orElse(null);
        });
    }

    public SnapshotPage page(UUID exportId, UUID leaseToken, long afterRowNumber, int limit) {
        if (exportId == null || leaseToken == null || afterRowNumber < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid export page request");
        }
        return transaction.execute(status -> {
            setWorkerContext();
            List<JobMetadata> jobs = jdbc.query("UPDATE export_jobs SET lease_expires_at=now() + interval '2 minutes', "
                            + "updated_at=now() WHERE id=? AND status='processing' AND lease_token=? "
                            + "AND lease_expires_at>now() AND expires_at>now() "
                            + "RETURNING format_version, requester_timezone, snapshot_at, row_count",
                    (rs, row) -> new JobMetadata(rs.getString("format_version"), rs.getString("requester_timezone"),
                            rs.getTimestamp("snapshot_at").toInstant(), rs.getInt("row_count")), exportId, leaseToken);
            if (jobs.isEmpty()) throw notFound();
            JobMetadata job = jobs.get(0);
            List<SnapshotRow> rows = jdbc.query("SELECT row_number, transaction_id, owner_user_id, occurred_at, "
                            + "amount::text AS amount, currency, category_code, subcategory_code, description, "
                            + "transaction_type, debt_target, source, telegram_id FROM export_snapshot_rows "
                            + "WHERE export_id=? AND row_number>? ORDER BY row_number LIMIT ?",
                    (rs, row) -> new SnapshotRow(rs.getLong("row_number"), rs.getObject("transaction_id", UUID.class),
                            rs.getObject("owner_user_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                            rs.getString("amount"), rs.getString("currency"), rs.getString("category_code"),
                            rs.getString("subcategory_code"), rs.getString("description"), rs.getString("transaction_type"),
                            rs.getString("debt_target"), rs.getString("source"), rs.getString("telegram_id")),
                    exportId, afterRowNumber, limit);
            long next = rows.isEmpty() ? afterRowNumber : rows.get(rows.size() - 1).rowNumber();
            return new SnapshotPage(exportId, job.formatVersion(), job.timezone(), job.snapshotAt(),
                    job.rowCount(), rows, next);
        });
    }

    public boolean complete(UUID exportId, CompleteRequest request) {
        validateComplete(exportId, request);
        return transaction.execute(status -> {
            setWorkerContext();
            List<Completed> previous = jdbc.query("SELECT object_key, object_checksum_sha256, object_byte_count "
                            + "FROM export_jobs WHERE id=? AND status='ready' AND completed_lease_token=?",
                    (rs, row) -> new Completed(rs.getString("object_key"), rs.getString("object_checksum_sha256"),
                            rs.getLong("object_byte_count")), exportId, request.leaseToken());
            if (!previous.isEmpty()) {
                Completed saved = previous.get(0);
                return saved.key().equals(request.objectKey()) && saved.sha256().equals(request.sha256())
                        && saved.byteCount() == request.byteCount();
            }
            return jdbc.update("UPDATE export_jobs SET status='ready', object_key=?, object_checksum_sha256=?, "
                            + "object_byte_count=?, completed_at=now(), completed_lease_token=lease_token, "
                            + "lease_token=NULL, lease_expires_at=NULL, error_code=NULL, updated_at=now() "
                            + "WHERE id=? AND status='processing' AND lease_token=? AND lease_expires_at>now() "
                            + "AND expires_at>now() AND ? LIKE 'tenants/' || tenant_id::text || '/exports/' || id::text || '/%'",
                    request.objectKey(), request.sha256(), request.byteCount(),
                    exportId, request.leaseToken(), request.objectKey()) == 1;
        });
    }

    public boolean fail(UUID exportId, FailRequest request) {
        validateFailure(exportId, request);
        return transaction.execute(status -> {
            setWorkerContext();
            List<Integer> attempts = jdbc.query("SELECT attempt_count FROM export_jobs WHERE id=? "
                            + "AND status='processing' AND lease_token=? AND lease_expires_at>now() AND expires_at>now() FOR UPDATE",
                    (rs, row) -> rs.getInt(1), exportId, request.leaseToken());
            if (attempts.isEmpty()) return false;
            int attempt = attempts.get(0);
            boolean retry = request.retryable() && attempt < MAX_ATTEMPTS;
            return jdbc.update("UPDATE export_jobs SET status=?, error_code=?, "
                            + "next_attempt_at=CASE WHEN ? THEN now() + (LEAST(60, (1 << attempt_count)) * interval '1 second') "
                            + "ELSE next_attempt_at END, lease_token=NULL, lease_expires_at=NULL, "
                            + "completed_lease_token=NULL, updated_at=now() WHERE id=? AND status='processing' "
                            + "AND lease_token=?", retry ? "queued" : "failed", request.errorCode(), retry,
                    exportId, request.leaseToken()) == 1;
        });
    }

    private void validateComplete(UUID exportId, CompleteRequest request) {
        if (exportId == null || request == null || request.leaseToken() == null || request.byteCount() < 1
                || request.byteCount() > MAX_OBJECT_BYTES || request.objectKey() == null
                || !OBJECT_KEY.matcher(request.objectKey()).matches()
                || request.sha256() == null || !SHA256.matcher(request.sha256()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid export completion");
        }
    }

    private void validateFailure(UUID exportId, FailRequest request) {
        if (exportId == null || request == null || request.leaseToken() == null || request.errorCode() == null
                || !ERROR_CODE.matcher(request.errorCode()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid export failure");
        }
    }

    private void setWorkerContext() {
        jdbc.queryForObject("SELECT set_config('app.export_service', 'true', true)", String.class);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Export job or active lease not found");
    }

    private record JobMetadata(String formatVersion, String timezone, Instant snapshotAt, int rowCount) {}
    private record Completed(String key, String sha256, long byteCount) {}
}
