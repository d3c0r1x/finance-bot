package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ExportApi.CreateRequest;
import com.decorix.finance.core.api.ExportApi.ExportJob;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExportService {
    private static final int MAX_DATES = 366;
    private static final int MAX_ROWS = 100_000;
    private static final String FORMAT = "csv-v1";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectProvider<ExportDownloadSigner> downloadSigners;

    public ExportService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                         ObjectProvider<ExportDownloadSigner> downloadSigners) {
        this.jdbc = jdbc;
        this.downloadSigners = downloadSigners;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public ExportJob create(UUID tenantId, String subject, CreateRequest request) {
        validate(tenantId, subject, request);
        return transaction.execute(status -> {
            setTenantContext(tenantId, subject);
            UUID requesterId = resolveUser(subject);
            Membership requester = membership(tenantId, requesterId);
            if (requester == null) throw notFound();
            boolean manager = "owner".equals(requester.role()) || "admin".equals(requester.role());
            boolean all = "all".equals(request.memberId());
            UUID scopeMemberId = all || request.memberId() == null ? requesterId : parseMemberId(request.memberId());
            if ((all || !scopeMemberId.equals(requesterId)) && !manager) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Export scope is not permitted");
            }
            if (!all && membership(tenantId, scopeMemberId) == null) throw notFound();

            String timezone = jdbc.queryForObject("SELECT timezone FROM member_profiles WHERE tenant_id=? AND user_id=?",
                    String.class, tenantId, requesterId);
            ZoneId zone = ZoneId.of(timezone);
            Instant from = request.fromDate().atStartOfDay(zone).toInstant();
            Instant until = request.toDate().plusDays(1).atStartOfDay(zone).toInstant();
            int count = jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM transactions t "
                            + "WHERE t.tenant_id=? AND t.status='posted' AND t.occurred_at>=? AND t.occurred_at<? "
                            + (all ? "" : "AND t.owner_user_id=? ") + "LIMIT ?) selected",
                    Integer.class, all ? new Object[]{tenantId, Timestamp.from(from), Timestamp.from(until), MAX_ROWS + 1}
                            : new Object[]{tenantId, Timestamp.from(from), Timestamp.from(until), scopeMemberId, MAX_ROWS + 1});
            if (count > MAX_ROWS) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Export exceeds row limit");

            UUID exportId = UUID.randomUUID();
            Instant snapshotAt = jdbc.queryForObject("SELECT transaction_timestamp()", Timestamp.class).toInstant();
            jdbc.update("INSERT INTO export_jobs (id, tenant_id, requester_user_id, format_version, from_date, to_date, "
                    + "requester_timezone, scope_member_id, include_all_members, snapshot_at, row_count) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", exportId, tenantId, requesterId, FORMAT,
                    request.fromDate(), request.toDate(), timezone, all ? null : scopeMemberId, all, Timestamp.from(snapshotAt), count);
            String scopePredicate = all ? "" : "AND t.owner_user_id=? ";
            String insertSnapshot = "INSERT INTO export_snapshot_rows (tenant_id, export_id, row_number, transaction_id, "
                    + "owner_user_id, occurred_at, amount, currency, category_code, subcategory_code, description, "
                    + "transaction_type, debt_target, source) SELECT ?, ?, row_number() OVER (ORDER BY t.occurred_at, t.id), "
                    + "t.id, t.owner_user_id, t.occurred_at, t.amount, t.currency, t.category_code, t.subcategory_code, "
                    + "t.description, t.type, d.name, t.source FROM transactions t LEFT JOIN debts d "
                    + "ON d.tenant_id=t.tenant_id AND d.id=t.debt_id WHERE t.tenant_id=? AND t.status='posted' "
                    + "AND t.occurred_at>=? AND t.occurred_at<? " + scopePredicate
                    + "ORDER BY t.occurred_at, t.id";
            if (all) jdbc.update(insertSnapshot, tenantId, exportId, tenantId, Timestamp.from(from), Timestamp.from(until));
            else jdbc.update(insertSnapshot, tenantId, exportId, tenantId, Timestamp.from(from), Timestamp.from(until), scopeMemberId);

            String payload = "{\"formatVersion\":\"csv-v1\",\"snapshotAt\":\"" + snapshotAt + "\",\"rowCount\":" + count + "}";
            jdbc.update("INSERT INTO outbox_events (tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload) "
                    + "VALUES (?, 'export', ?, 1, 'export.requested', CAST(? AS jsonb))", tenantId, exportId, payload);
            jdbc.update("INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id, after_state, trace_id) "
                            + "VALUES (?, ?, 'export.requested', 'export', ?, CAST(? AS jsonb), ?)", tenantId, subject,
                    exportId, "{\"formatVersion\":\"csv-v1\",\"rowCount\":" + count + "}", UUID.randomUUID().toString());
            return getWithinTransaction(tenantId, requesterId, requester.role(), exportId);
        });
    }

    public ExportJob get(UUID tenantId, String subject, UUID exportId) {
        return transaction.execute(status -> {
            setTenantContext(tenantId, subject);
            UUID requesterId = resolveUser(subject);
            Membership membership = membership(tenantId, requesterId);
            if (membership == null) throw notFound();
            ExportJob job = getWithinTransaction(tenantId, requesterId, membership.role(), exportId);
            jdbc.update("INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id, after_state, trace_id) "
                            + "VALUES (?, ?, 'export.status_viewed', 'export', ?, CAST(? AS jsonb), ?)", tenantId,
                    subject, exportId, "{\"status\":\"" + job.status() + "\"}", UUID.randomUUID().toString());
            if (job.downloadUrl() != null) {
                jdbc.update("INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id, after_state, trace_id) "
                                + "VALUES (?, ?, 'export.download_url_issued', 'export', ?, CAST(? AS jsonb), ?)", tenantId,
                        subject, exportId, "{\"status\":\"ready\"}", UUID.randomUUID().toString());
            }
            return job;
        });
    }

    private ExportJob getWithinTransaction(UUID tenantId, UUID requesterId, String role, UUID exportId) {
        boolean manager = "owner".equals(role) || "admin".equals(role);
        List<ExportJob> jobs = jdbc.query("SELECT id,status,format_version,from_date,to_date,scope_member_id,"
                        + "include_all_members,row_count,snapshot_at,created_at,expires_at,object_key FROM export_jobs "
                        + "WHERE tenant_id=? AND id=? AND (? OR requester_user_id=?)",
                (rs, row) -> {
                    Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
                    String status = rs.getString("status");
                    String objectKey = rs.getString("object_key");
                    String downloadUrl = null;
                    Instant now = Instant.now();
                    if ("ready".equals(status) && expiresAt.isAfter(now) && objectKey != null) {
                        ExportDownloadSigner signer = downloadSigners.getIfAvailable();
                        if (signer != null) {
                            var remaining = java.time.Duration.between(now, expiresAt);
                            if (remaining.compareTo(java.time.Duration.ofSeconds(1)) >= 0) {
                                var lifetime = remaining.compareTo(java.time.Duration.ofMinutes(5)) < 0
                                        ? remaining : java.time.Duration.ofMinutes(5);
                                downloadUrl = signer.sign(objectKey, lifetime);
                            }
                        }
                    }
                    return new ExportJob(rs.getObject("id", UUID.class), status,
                            rs.getString("format_version"), rs.getObject("from_date", LocalDate.class),
                            rs.getObject("to_date", LocalDate.class), rs.getObject("scope_member_id", UUID.class),
                            rs.getBoolean("include_all_members"), rs.getInt("row_count"),
                            rs.getTimestamp("snapshot_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                            expiresAt, downloadUrl);
                }, tenantId, exportId, manager, requesterId);
        if (jobs.isEmpty()) throw notFound();
        return jobs.get(0);
    }

    private UUID resolveUser(String subject) {
        List<UUID> users = jdbc.query("SELECT user_id FROM external_identities WHERE provider='keycloak' AND subject=?",
                (rs, row) -> rs.getObject(1, UUID.class), subject);
        return users.isEmpty() ? null : users.get(0);
    }

    private Membership membership(UUID tenantId, UUID userId) {
        if (userId == null) return null;
        List<Membership> rows = jdbc.query("SELECT role FROM memberships WHERE tenant_id=? AND user_id=? AND status='active'",
                (rs, row) -> new Membership(rs.getString("role")), tenantId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID parseMemberId(String memberId) {
        try { return UUID.fromString(memberId); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid memberId"); }
    }

    private void validate(UUID tenantId, String subject, CreateRequest request) {
        if (tenantId == null || subject == null || subject.isBlank() || request == null
                || !FORMAT.equals(request.formatVersion()) || request.fromDate() == null || request.toDate() == null
                || (request.memberId() != null && !"all".equals(request.memberId()) && request.memberId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid export request");
        }
        if (request.toDate().isBefore(request.fromDate())
                || java.time.temporal.ChronoUnit.DAYS.between(request.fromDate(), request.toDate()) + 1 > MAX_DATES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export date range must be 1 to 366 days");
        }
    }

    private void setTenantContext(UUID tenantId, String subject) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
    }

    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Export not found"); }
    private record Membership(String role) {}
}
