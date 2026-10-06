package com.decorix.finance.core.api;

import com.decorix.finance.core.api.AdviceAnalyticsApi.ClaimedJob;
import com.decorix.finance.core.api.AdviceAnalyticsApi.F43Item;
import com.decorix.finance.core.api.AdviceAnalyticsApi.F43Recalculation;
import com.decorix.finance.core.api.AdviceAnalyticsApi.F43Request;
import com.decorix.finance.core.api.AdviceAnalyticsApi.Job;
import com.decorix.finance.core.api.AdviceAnalyticsApi.JobResult;
import com.decorix.finance.core.domain.ProductIdentityPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdviceAnalyticsService {
    static final String ALGORITHM = "advice-f43.v1";
    private static final int MAX_ITEMS = 50_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public AdviceAnalyticsService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
    }

    public Job latest(UUID tenantId, String subject) {
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, false);
            List<Job> rows = jdbc.query("""
                    SELECT id, state, input_watermark, algorithm_version, result, error_code, updated_at
                    FROM advice_analytics_jobs WHERE tenant_id = ? AND owner_user_id = ?
                    ORDER BY input_watermark DESC LIMIT 1
                    """, (rs, row) -> job(rs.getObject("id", UUID.class), rs.getString("state"),
                    rs.getLong("input_watermark"), rs.getString("algorithm_version"),
                    readJson(rs.getString("result")), rs.getString("error_code"), rs.getTimestamp("updated_at").toInstant()),
                    tenantId, actor.userId());
            return rows.stream().findFirst().orElse(new Job(null, null, null, null, null, null, null, Instant.now()));
        });
    }

    public Job request(UUID tenantId, String subject) {
        try {
            return transaction.execute(status -> enqueue(tenantId, subject));
        } catch (TooManyItems exception) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "too_many_items", exception);
        }
    }

    private Job enqueue(UUID tenantId, String subject) {
        Actor actor = actor(tenantId, subject, true);
        Snapshot snapshot = snapshot(tenantId, actor.userId());
        if (snapshot.items().size() > MAX_ITEMS) throw new TooManyItems();
        String contentHash = sha256(serialize(snapshot.payload()));
        List<Version> current = jdbc.query("""
                SELECT input_hash, input_watermark FROM advice_analytics_versions
                WHERE tenant_id = ? AND owner_user_id = ? FOR UPDATE
                """, (rs, row) -> new Version(rs.getString("input_hash"), rs.getLong("input_watermark")), tenantId, actor.userId());
        long watermark = current.isEmpty() ? 1 : current.get(0).watermark() + (current.get(0).hash().equals(contentHash) ? 0 : 1);
        if (current.isEmpty()) {
            jdbc.update("INSERT INTO advice_analytics_versions (tenant_id, owner_user_id, input_hash, input_watermark) VALUES (?, ?, ?, ?)",
                    tenantId, actor.userId(), contentHash, watermark);
        } else if (!current.get(0).hash().equals(contentHash)) {
            jdbc.update("UPDATE advice_analytics_versions SET input_hash = ?, input_watermark = ?, updated_at = now() WHERE tenant_id = ? AND owner_user_id = ?",
                    contentHash, watermark, tenantId, actor.userId());
            jdbc.update("UPDATE advice_analytics_jobs SET state = 'stale', lease_token = NULL, lease_expires_at = NULL, updated_at = now() "
                    + "WHERE tenant_id = ? AND owner_user_id = ? AND state IN ('pending', 'processing')", tenantId, actor.userId());
        }
        List<Job> existing = jdbc.query("""
                SELECT id, state, input_watermark, algorithm_version, result, error_code, updated_at
                FROM advice_analytics_jobs WHERE tenant_id = ? AND owner_user_id = ? AND input_watermark = ? AND algorithm_version = ?
                """, (rs, row) -> job(rs.getObject("id", UUID.class), rs.getString("state"),
                rs.getLong("input_watermark"), rs.getString("algorithm_version"), readJson(rs.getString("result")),
                rs.getString("error_code"), rs.getTimestamp("updated_at").toInstant()), tenantId, actor.userId(), watermark, ALGORITHM);
        if (!existing.isEmpty()) {
            Job previous = existing.get(0);
            if ("failed".equals(previous.state())) {
                jdbc.update("UPDATE advice_analytics_jobs SET state='pending', attempt_count=0, error_code=NULL, next_attempt_at=now(), updated_at=now() WHERE id=?",
                        previous.id());
                return new Job(previous.id(), "pending", previous.inputWatermark(), previous.algorithmVersion(), null, null, null, Instant.now());
            }
            return previous;
        }
        F43Request payload = withWatermark(snapshot.payload(), Long.toString(watermark));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO advice_analytics_jobs (id, tenant_id, owner_user_id, input_hash, input_watermark,
                    algorithm_version, input_payload) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, id, tenantId, actor.userId(), contentHash, watermark, ALGORITHM, serialize(payload));
        return new Job(id, "pending", Long.toString(watermark), ALGORITHM, null, null, null, Instant.now());
    }

    public Job get(UUID tenantId, String subject, UUID jobId) {
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, false);
            List<Job> jobs = jdbc.query("""
                    SELECT id, state, input_watermark, algorithm_version, result, error_code, updated_at
                    FROM advice_analytics_jobs WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
                    """, (rs, row) -> job(rs.getObject("id", UUID.class), rs.getString("state"),
                    rs.getLong("input_watermark"), rs.getString("algorithm_version"), readJson(rs.getString("result")),
                    rs.getString("error_code"), rs.getTimestamp("updated_at").toInstant()), tenantId, actor.userId(), jobId);
            if (jobs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Advice analytics job not found");
            Job job = jobs.get(0);
            long watermark = jdbc.queryForObject("SELECT input_watermark FROM advice_analytics_versions WHERE tenant_id = ? AND owner_user_id = ?",
                    Long.class, tenantId, actor.userId());
            return watermark == Long.parseLong(job.inputWatermark()) ? job
                    : new Job(job.id(), "stale", job.inputWatermark(), job.algorithmVersion(), null, "stale_input", null, job.updatedAt());
        });
    }

    public ClaimedJob claim() {
        return transaction.execute(status -> {
            workerContext();
            jdbc.update("UPDATE advice_analytics_jobs SET state='failed', error_code='retry_limit', lease_token=NULL, lease_expires_at=NULL, updated_at=now() "
                    + "WHERE state='processing' AND lease_expires_at <= now() AND attempt_count >= 5");
            List<ClaimedJob> jobs = jdbc.query("""
                    WITH candidate AS (
                      SELECT id FROM advice_analytics_jobs
                      WHERE ((state = 'pending' AND next_attempt_at <= now())
                         OR (state = 'processing' AND lease_expires_at <= now())) AND attempt_count < 5
                      ORDER BY next_attempt_at, created_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                    )
                    UPDATE advice_analytics_jobs job SET state = 'processing', attempt_count = attempt_count + 1,
                      lease_token = gen_random_uuid(), completed_lease_token = NULL,
                      lease_expires_at = now() + interval '2 minutes', updated_at = now()
                    FROM candidate WHERE job.id = candidate.id
                    RETURNING job.id, job.tenant_id, job.owner_user_id, job.input_watermark, job.algorithm_version,
                      job.lease_token, job.attempt_count, job.input_payload::text
                    """, (rs, row) -> new ClaimedJob(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                    rs.getObject("owner_user_id", UUID.class), Long.toString(rs.getLong("input_watermark")),
                    rs.getString("algorithm_version"), rs.getObject("lease_token", UUID.class), rs.getInt("attempt_count"),
                    readRequest(rs.getString("input_payload"))));
            return jobs.stream().findFirst().orElse(null);
        });
    }

    public boolean complete(UUID jobId, JobResult result) {
        return transaction.execute(status -> {
            workerContext();
            List<Claim> rows = jdbc.query("""
                    SELECT tenant_id, owner_user_id, input_watermark, algorithm_version, lease_token, completed_lease_token, state
                    FROM advice_analytics_jobs WHERE id = ? FOR UPDATE
                    """, (rs, row) -> new Claim(rs.getObject("tenant_id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                    rs.getLong("input_watermark"), rs.getString("algorithm_version"), rs.getObject("lease_token", UUID.class),
                    rs.getObject("completed_lease_token", UUID.class), rs.getString("state")), jobId);
            if (rows.isEmpty()) return false;
            Claim claim = rows.get(0);
            if ("ready".equals(claim.state()) && result.leaseToken().equals(claim.completedLeaseToken())) return true;
            if (!"processing".equals(claim.state()) || !result.leaseToken().equals(claim.leaseToken())
                    || claim.watermark() != Long.parseLong(result.inputWatermark())
                    || !claim.algorithm().equals(result.algorithmVersion())) return false;
            List<Long> current = jdbc.query("SELECT input_watermark FROM advice_analytics_versions WHERE tenant_id = ? AND owner_user_id = ?",
                    (rs, row) -> rs.getLong(1), claim.tenantId(), claim.ownerId());
            if (current.isEmpty() || current.get(0) != claim.watermark()) {
                jdbc.update("UPDATE advice_analytics_jobs SET state='stale', lease_token=NULL, lease_expires_at=NULL, result=NULL, updated_at=now() WHERE id=?", jobId);
                return false;
            }
            if (result.errorCode() != null && !result.errorCode().isBlank()) {
                String state = jdbc.queryForObject("SELECT CASE WHEN attempt_count >= 5 THEN 'failed' ELSE 'pending' END FROM advice_analytics_jobs WHERE id = ?", String.class, jobId);
                jdbc.update("UPDATE advice_analytics_jobs SET state=?, error_code=?, next_attempt_at=now() + (LEAST(60, (1 << attempt_count)) * interval '1 second'), "
                        + "lease_token=NULL, completed_lease_token=NULL, lease_expires_at=NULL, updated_at=now() WHERE id=?", state, result.errorCode(), jobId);
                return true;
            }
            validateReport(result, claim);
            jdbc.update("UPDATE advice_analytics_jobs SET state='ready', result=CAST(? AS jsonb), error_code=NULL, completed_lease_token=lease_token, "
                    + "lease_token=NULL, lease_expires_at=NULL, updated_at=now() WHERE id=?",
                    json.writeValueAsString(result.report()), jobId);
            return true;
        });
    }

    private Snapshot snapshot(UUID tenantId, UUID userId) {
        MemberProfile profile = jdbc.query("SELECT timezone, planned_income FROM member_profiles WHERE tenant_id=? AND user_id=?",
                (rs, row) -> new MemberProfile(rs.getString("timezone"), rs.getBigDecimal("planned_income") == null ? null
                        : rs.getBigDecimal("planned_income").toPlainString()), tenantId, userId).stream().findFirst()
                .orElse(new MemberProfile("UTC", null));
        ZoneId zone;
        try { zone = ZoneId.of(profile.timeZone()); } catch (RuntimeException invalid) { zone = ZoneId.of("UTC"); }
        Instant asOf = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
        String monthlyLimit = jdbc.query("SELECT amount::text FROM tenant_budgets WHERE tenant_id=? AND owner_user_id=? AND budget_key='__total__'",
                (rs, row) -> rs.getString(1), tenantId, userId).stream().findFirst().orElse(null);
        List<Purchase> purchases = jdbc.query("""
                SELECT ri.id, ri.name, ri.line_sum::text, ri.verdict, ri.advice,
                       t.occurred_at, ri.version,
                       EXISTS (SELECT 1 FROM recalculation_changes c JOIN recalculation_runs rr
                           ON rr.tenant_id=c.tenant_id AND rr.id=c.run_id
                           WHERE c.tenant_id=r.tenant_id AND c.receipt_item_id=ri.id AND c.changed=true
                             AND rr.state='applied' AND c.before_state->>'verdict' IS DISTINCT FROM c.after_state->>'verdict'
                             AND c.before_state->>'verdict' IS DISTINCT FROM 'harmful'
                             AND c.before_state->>'verdict' IS DISTINCT FROM 'unnecessary') AS f42_introduced
                FROM receipts r JOIN transactions t ON t.tenant_id=r.tenant_id AND t.id=r.transaction_id
                JOIN receipt_items ri ON ri.tenant_id=r.tenant_id AND ri.receipt_id=r.id
                WHERE r.tenant_id=? AND r.owner_user_id=? AND r.state='confirmed'
                  AND t.status='posted' AND t.type='expense' AND t.occurred_at < ?
                ORDER BY t.occurred_at, r.id, ri.ordinal, ri.id LIMIT 50001
                """, (rs, row) -> new Purchase(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("line_sum"),
                rs.getString("verdict"), rs.getString("advice"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getBoolean("f42_introduced")), tenantId, userId, Timestamp.from(asOf));
        // Product decisions are member-specific and use the stable normalized key, so apply them after retrieving facts.
        Set<String> productKeys = new HashSet<>();
        purchases.forEach(purchase -> productKeys.add(ProductIdentityPolicy.productKey(purchase.name())));
        Set<String> allowedKeys = new HashSet<>();
        if (!productKeys.isEmpty()) {
            jdbc.query("SELECT product_key FROM user_product_decisions WHERE tenant_id=? AND user_id=? AND decision='allowed'",
                    rs -> { while (rs.next()) { String key = rs.getString(1); if (productKeys.contains(key)) allowedKeys.add(key); }
                        return null; }, tenantId, userId);
        }
        List<F43Item> items = new ArrayList<>(purchases.size());
        for (Purchase p : purchases) {
            String key = ProductIdentityPolicy.productKey(p.name());
            boolean allowed = allowedKeys.contains(key);
            boolean adviceGiven = !p.f42Introduced() && p.advice() != null && !p.advice().isBlank()
                    && ("harmful".equals(p.verdict()) || "unnecessary".equals(p.verdict()));
            items.add(new F43Item(p.id().toString(), key, p.name(), p.lineSum(), p.verdict(), p.advice(),
                    adviceGiven, allowed, p.purchasedAt()));
        }
        if (purchases.size() > MAX_ITEMS) return new Snapshot(null, purchases);
        List<F43Recalculation> recalculations = jdbc.query("""
                SELECT applied_at, changed_count, impact_report->>'optionalSpendDelta' AS optional_spend_delta
                FROM recalculation_runs WHERE tenant_id=? AND owner_user_id=? AND state='applied'
                  AND applied_at IS NOT NULL AND applied_at < ? ORDER BY applied_at LIMIT 50001
                """, (rs, row) -> new F43Recalculation(rs.getTimestamp("applied_at").toInstant(),
                rs.getInt("changed_count"), rs.getString("optional_spend_delta")), tenantId, userId, Timestamp.from(asOf));
        if (recalculations.size() > MAX_ITEMS) return new Snapshot(null, purchases);
        return new Snapshot(new F43Request("1", asOf, zone.getId(), profile.income(), monthlyLimit, items, recalculations), purchases);
    }

    private Actor actor(UUID tenantId, String subject, boolean write) {
        if (tenantId == null || subject == null || subject.isBlank()) throw notFound();
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
        List<Actor> actors = jdbc.query("SELECT user_id, role FROM memberships WHERE tenant_id=? AND subject=? AND status='active' "
                + "FOR UPDATE", (rs, row) -> new Actor(rs.getObject("user_id", UUID.class), rs.getString("role")), tenantId, subject);
        if (actors.isEmpty()) throw notFound();
        Actor actor = actors.get(0);
        if (write && "viewer".equals(actor.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Viewer access is read-only");
        return actor;
    }

    private void workerContext() {
        jdbc.queryForObject("SELECT set_config('app.analytics_worker', 'true', true)", String.class);
    }

    private static void validateReport(JobResult result, Claim claim) {
        JsonNode report = result.report();
        if (report == null || !claim.algorithm().equals(report.path("algorithmVersion").asString())
                || !Long.toString(claim.watermark()).equals(report.path("inputWatermark").asString())
                || !List.of("complete", "partial").contains(report.path("completeness").asString())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Analytics report does not match its job");
        }
    }

    private Job job(UUID id, String state, long watermark, String algorithm, JsonNode report, String error, Instant updated) {
        return new Job(id, state, Long.toString(watermark), algorithm,
                report == null ? null : report.path("completeness").asString(null), error, report, updated);
    }

    private F43Request readRequest(String source) {
        try { return json.readValue(source, F43Request.class); }
        catch (JacksonException ex) { throw new IllegalStateException("Stored analytics job input is invalid", ex); }
    }

    private JsonNode readJson(String source) {
        if (source == null) return null;
        try { return json.readTree(source); }
        catch (JacksonException ex) { throw new IllegalStateException("Stored analytics result is invalid", ex); }
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException ex) { throw new IllegalStateException("Could not serialize analytics input", ex); }
    }

    private static F43Request withWatermark(F43Request request, String watermark) {
        return new F43Request(watermark, request.asOf(), request.timeZone(), request.income(), request.monthlyLimit(),
                request.items(), request.recalculations());
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"); }
    private record Actor(UUID userId, String role) {}
    private record Version(String hash, long watermark) {}
    private record MemberProfile(String timeZone, String income) {}
    private record Purchase(UUID id, String name, String lineSum, String verdict, String advice,
                            Instant purchasedAt, boolean f42Introduced) {}
    private record Snapshot(F43Request payload, List<Purchase> items) {}
    private record Claim(UUID tenantId, UUID ownerId, long watermark, String algorithm, UUID leaseToken,
                         UUID completedLeaseToken, String state) {}
    private static final class TooManyItems extends RuntimeException {}
}
