package com.decorix.finance.core.api;

import com.decorix.finance.core.api.AdviceRecalculationImpactApi.Impact;
import com.decorix.finance.core.api.AdviceWasteApi.ReceiptLine;
import com.decorix.finance.core.api.ReceiptRecalculationApi.ApplyRequest;
import com.decorix.finance.core.api.ReceiptRecalculationApi.ApplyResult;
import com.decorix.finance.core.api.ReceiptRecalculationApi.Change;
import com.decorix.finance.core.api.ReceiptRecalculationApi.HistoryPage;
import com.decorix.finance.core.api.ReceiptRecalculationApi.Preview;
import com.decorix.finance.core.api.ReceiptRecalculationApi.RunDetail;
import com.decorix.finance.core.api.ReceiptRecalculationApi.RunSummary;
import com.decorix.finance.core.domain.ReceiptBasketPolicy;
import com.decorix.finance.core.domain.ProductIdentityPolicy;
import com.decorix.finance.core.domain.ReceiptRecalculationPolicy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReceiptRecalculationService {
    private static final int MAX_ITEMS = 50_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final AdviceRecalculationImpactClient impactClient;

    public ReceiptRecalculationService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json,
                                       AdviceRecalculationImpactClient impactClient) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
        this.impactClient = impactClient;
    }

    public Preview preview(UUID tenantId, String subject) {
        PreparedPreview prepared = transaction.execute(status -> prepare(tenantId, subject));
        Impact impact = impactClient.calculate(new AdviceRecalculationImpactApi.Request(
                prepared.before(), prepared.after())).withCurrency(prepared.currency());
        return transaction.execute(status -> persistPreview(tenantId, subject, prepared, impact));
    }

    public HistoryPage history(UUID tenantId, String subject, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "History limit must be 1..100");
        HistoryCursor before = cursor == null || cursor.isBlank() ? null : decodeCursor(cursor);
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject);
            String sql = """
                    SELECT id, algorithm_version, state, checked_count, update_count, changed_count,
                           created_at, applied_at, impact_report::text AS impact_report
                    FROM recalculation_runs
                    WHERE tenant_id = ? AND owner_user_id = ?
                    """ + (before == null ? "" : " AND (created_at, id) < (?, ?)") + """
                    ORDER BY created_at DESC, id DESC LIMIT ?
                    """;
            List<RunSummary> rows = before == null
                    ? jdbc.query(sql, (rs, row) -> summary(rs), tenantId, actor.userId(), limit + 1)
                    : jdbc.query(sql, (rs, row) -> summary(rs), tenantId, actor.userId(),
                            Timestamp.from(before.createdAt()), before.runId(), limit + 1);
            boolean hasMore = rows.size() > limit;
            List<RunSummary> page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
            return new HistoryPage(page, hasMore ? encodeCursor(page.get(page.size() - 1)) : null);
        });
    }

    public RunDetail historyDetail(UUID tenantId, String subject, UUID runId, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "History limit must be 1..100");
        UUID afterItem;
        try {
            afterItem = cursor == null || cursor.isBlank() ? null : UUID.fromString(cursor);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "History cursor is invalid", invalid);
        }
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject);
            List<RunSummary> runs = jdbc.query("""
                    SELECT id, algorithm_version, state, checked_count, update_count, changed_count,
                           created_at, applied_at, impact_report::text AS impact_report
                    FROM recalculation_runs
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
                    """, (rs, row) -> summary(rs), tenantId, actor.userId(), runId);
            if (runs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recalculation run not found");
            String sql = """
                    SELECT receipt_item_id, expected_item_version, line_sum::text AS line_sum,
                           before_state ->> 'name' AS name,
                           before_state ->> 'verdict' AS before_verdict,
                           before_state ->> 'reason' AS before_reason,
                           before_state ->> 'action' AS before_action,
                           before_state ->> 'source' AS before_source,
                           after_state ->> 'verdict' AS after_verdict,
                           after_state ->> 'reason' AS after_reason,
                           after_state ->> 'action' AS after_action,
                           after_state ->> 'source' AS after_source, changed
                    FROM recalculation_changes WHERE tenant_id = ? AND run_id = ?
                    """ + (afterItem == null ? "" : " AND receipt_item_id > ?") + " ORDER BY receipt_item_id LIMIT ?";
            List<Change> rows = afterItem == null
                    ? jdbc.query(sql, changeMapper(), tenantId, runId, limit + 1)
                    : jdbc.query(sql, changeMapper(), tenantId, runId, afterItem, limit + 1);
            boolean hasMore = rows.size() > limit;
            List<Change> page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
            String nextCursor = hasMore ? page.get(page.size() - 1).itemId().toString() : null;
            return new RunDetail(runs.get(0), page, nextCursor);
        });
    }

    private static org.springframework.jdbc.core.RowMapper<Change> changeMapper() {
        return (rs, row) -> new Change(rs.getObject("receipt_item_id", UUID.class), rs.getString("name"),
                rs.getString("line_sum"), rs.getLong("expected_item_version"), rs.getString("before_verdict"),
                rs.getString("before_reason"), rs.getString("before_action"), rs.getString("before_source"),
                rs.getString("after_verdict"), rs.getString("after_reason"), rs.getString("after_action"),
                rs.getString("after_source"), rs.getBoolean("changed"));
    }

    private RunSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp appliedAt = rs.getTimestamp("applied_at");
        return new RunSummary(rs.getObject("id", UUID.class), rs.getString("algorithm_version"),
                rs.getString("state"), rs.getInt("checked_count"), rs.getInt("update_count"),
                rs.getInt("changed_count"), createdAt.toInstant(), appliedAt == null ? null : appliedAt.toInstant(),
                readImpact(rs.getString("impact_report")));
    }

    private static String encodeCursor(RunSummary run) {
        String value = run.createdAt() + "|" + run.runId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static HistoryCursor decodeCursor(String cursor) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = value.indexOf('|');
            if (separator <= 0 || separator == value.length() - 1) throw new IllegalArgumentException();
            return new HistoryCursor(Instant.parse(value.substring(0, separator)),
                    UUID.fromString(value.substring(separator + 1)));
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "History cursor is invalid", invalid);
        }
    }

    private PreparedPreview prepare(UUID tenantId, String subject) {
        Actor actor = actor(tenantId, subject);
        Map<String, Object> profile = jdbc.queryForMap(
                "SELECT timezone, currency FROM member_profiles WHERE tenant_id = ? AND user_id = ?",
                tenantId, actor.userId());
        String timezone = (String) profile.get("timezone");
        String currency = (String) profile.get("currency");
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (RuntimeException invalidTimezone) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Member timezone is invalid", invalidTimezone);
        }
        List<StoredLine> lines = jdbc.query("""
                    SELECT r.id AS receipt_id, ri.id AS item_id, ri.name, ri.line_sum, ri.verdict,
                           ri.review_reason, ri.review_action, ri.verdict_source, ri.version, t.occurred_at
                    FROM receipts r
                    JOIN transactions t ON t.tenant_id = r.tenant_id AND t.id = r.transaction_id
                    JOIN receipt_items ri ON ri.tenant_id = r.tenant_id AND ri.receipt_id = r.id
                    WHERE r.tenant_id = ? AND r.owner_user_id = ? AND r.state = 'confirmed'
                      AND t.status = 'posted' AND t.type = 'expense'
                    ORDER BY t.occurred_at, r.id, ri.ordinal, ri.id
                    LIMIT 50001
                    """, (rs, row) -> new StoredLine(rs.getObject("receipt_id", UUID.class),
                    rs.getObject("item_id", UUID.class), rs.getString("name"), rs.getBigDecimal("line_sum"),
                    rs.getString("verdict"), rs.getString("review_reason"), rs.getString("review_action"),
                    rs.getString("verdict_source"), rs.getLong("version"),
                    rs.getTimestamp("occurred_at").toInstant()), tenantId, actor.userId());
        if (lines.size() > MAX_ITEMS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Receipt history is too large to recalculate");
        }

        List<ReceiptRecalculationPolicy.Line> input = lines.stream().map(line ->
                new ReceiptRecalculationPolicy.Line(line.itemId(), line.name(), line.lineSum(), line.verdict(),
                        line.reason(), line.action(), line.source())).toList();
        ReceiptRecalculationPolicy.Plan plan;
        try {
            plan = ReceiptRecalculationPolicy.preview(input);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Receipt history contains invalid review data", invalid);
        }
        Map<UUID, StoredLine> storedById = new LinkedHashMap<>();
        lines.forEach(line -> storedById.put(line.itemId(), line));
        ImpactWindow window = impactWindow(zone);
        List<StoredLine> reportLines = lines.stream()
                .filter(line -> !line.purchasedAt().isBefore(window.start()) && line.purchasedAt().isBefore(window.end()))
                .toList();
        Map<DecisionKey, Long> allowed = loadAllowedDecisions(tenantId, actor.userId(), reportLines);
        List<ReceiptLine> beforeItems = new ArrayList<>(reportLines.size());
        List<ReceiptLine> afterItems = new ArrayList<>(reportLines.size());
        Map<UUID, ReceiptRecalculationPolicy.Update> updates = new HashMap<>();
        plan.updates().forEach(update -> updates.put(update.itemId(), update));
        for (StoredLine line : reportLines) {
            String productKey = ProductIdentityPolicy.productKey(line.name());
            Long decisionVersion = allowed.get(new DecisionKey(actor.userId(), productKey));
            boolean isAllowed = decisionVersion != null;
            beforeItems.add(toReceiptLine(line, productKey, isAllowed, decisionVersion));
            ReceiptRecalculationPolicy.Update update = updates.get(line.itemId());
            if (update == null) {
                afterItems.add(toReceiptLine(line, productKey, isAllowed, decisionVersion));
            } else {
                afterItems.add(new ReceiptLine(line.itemId().toString(), productKey, line.name(),
                        line.lineSum() == null ? null : line.lineSum().toPlainString(), update.afterVerdict(),
                        "rule", line.purchasedAt(), isAllowed, line.version(), decisionVersion == null ? 0 : decisionVersion));
            }
        }
        var before = new AdviceWasteApi.Request(window.from().toString(), window.to().toString(), window.asOf(),
                zone.getId(), List.copyOf(beforeItems));
        var after = new AdviceWasteApi.Request(window.from().toString(), window.to().toString(), window.asOf(),
                zone.getId(), List.copyOf(afterItems));
        return new PreparedPreview(actor, plan, storedById, before, after, currency);
    }

    private Preview persistPreview(UUID tenantId, String subject, PreparedPreview prepared, Impact impact) {
        // Re-check the active actor inside the write transaction after the Go call.
        Actor currentActor = actor(tenantId, subject);
        if (!currentActor.userId().equals(prepared.actor().userId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Active member changed during recalculation preview");
        }
        ReceiptRecalculationPolicy.Plan plan = prepared.plan();
        Map<UUID, StoredLine> storedById = prepared.storedById();
        UUID runId = UUID.randomUUID();
        int changedCount = (int) plan.updates().stream().filter(ReceiptRecalculationPolicy.Update::changed).count();
        jdbc.update("""
                    INSERT INTO recalculation_runs
                      (id, tenant_id, owner_user_id, initiated_by_subject, algorithm_version, checked_count,
                       update_count, changed_count, impact_report)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, runId, tenantId, currentActor.userId(), subject, ReceiptBasketPolicy.ALGORITHM_VERSION,
                    plan.checked(), plan.updates().size(), changedCount, serialize(impact));

        List<Change> changes = new ArrayList<>(plan.updates().size());
        for (ReceiptRecalculationPolicy.Update update : plan.updates()) {
            StoredLine stored = storedById.get(update.itemId());
            Map<String, Object> before = itemState(stored.name(), stored.lineSum(), update.beforeVerdict(),
                    update.beforeReason(), update.beforeAction(), update.beforeSource());
            Map<String, Object> after = itemState(stored.name(), stored.lineSum(), update.afterVerdict(),
                    update.afterReason(), update.afterAction(), update.afterSource());
            jdbc.update("""
                        INSERT INTO recalculation_changes
                          (tenant_id, run_id, receipt_id, receipt_item_id, expected_item_version, line_sum,
                           before_state, after_state, changed)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                    """, tenantId, runId, stored.receiptId(), stored.itemId(), stored.version(),
                    stored.lineSum(), serialize(before), serialize(after), update.changed());
            changes.add(toChange(update, stored.version()));
        }
        return new Preview(runId, ReceiptBasketPolicy.ALGORITHM_VERSION, "previewed", plan.checked(),
                plan.updates().size(), changedCount, impact, List.copyOf(changes));
    }

    private Map<DecisionKey, Long> loadAllowedDecisions(UUID tenantId, UUID ownerId, List<StoredLine> lines) {
        Set<String> productKeys = new HashSet<>();
        lines.forEach(line -> productKeys.add(ProductIdentityPolicy.productKey(line.name())));
        Map<DecisionKey, Long> result = new HashMap<>();
        if (productKeys.isEmpty()) return result;
        jdbc.query("""
                SELECT product_key, version FROM user_product_decisions
                WHERE tenant_id = ? AND user_id = ? AND decision = 'allowed'
                """, rs -> {
            while (rs.next()) {
                String key = rs.getString("product_key");
                if (productKeys.contains(key)) result.put(new DecisionKey(ownerId, key), rs.getLong("version"));
            }
            return null;
        }, tenantId, ownerId);
        return result;
    }

    private static ReceiptLine toReceiptLine(StoredLine line, String productKey, boolean allowed, Long decisionVersion) {
        return new ReceiptLine(line.itemId().toString(), productKey, line.name(),
                line.lineSum() == null ? null : line.lineSum().toPlainString(), line.verdict(), line.source(),
                line.purchasedAt(), allowed, line.version(), decisionVersion == null ? 0 : decisionVersion);
    }

    private static ImpactWindow impactWindow(ZoneId zone) {
        LocalDate to = LocalDate.now(zone);
        LocalDate from = to.minusDays(89);
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        return new ImpactWindow(from, to, to.plusDays(1).atStartOfDay(zone).toInstant(), start, end);
    }

    public ApplyResult apply(UUID tenantId, String subject, ApplyRequest request) {
        if (request == null || request.runId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A recalculation run is required");
        }
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject);
            List<Run> runs = jdbc.query("""
                    SELECT id, algorithm_version, state, update_count, changed_count, impact_report::text AS impact_report
                    FROM recalculation_runs
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
                    FOR UPDATE
            """, (rs, row) -> new Run(rs.getObject("id", UUID.class), rs.getString("algorithm_version"),
                    rs.getString("state"), rs.getInt("update_count"), rs.getInt("changed_count"),
                    readImpact(rs.getString("impact_report"))),
                    tenantId, actor.userId(), request.runId());
            if (runs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recalculation run not found");
            Run run = runs.get(0);
            List<Change> changes = loadChanges(tenantId, run.id());
            if ("applied".equals(run.state())) {
                return new ApplyResult(run.id(), run.algorithmVersion(), run.state(), run.updateCount(),
                        run.changedCount(), run.impact(), changes);
            }

            List<CurrentChange> current = jdbc.query("""
                    SELECT c.receipt_id, c.receipt_item_id, c.expected_item_version, c.line_sum::text AS line_sum,
                           c.before_state ->> 'name' AS name,
                           c.before_state ->> 'verdict' AS before_verdict,
                           c.before_state ->> 'reason' AS before_reason,
                           c.before_state ->> 'action' AS before_action,
                           c.before_state ->> 'source' AS before_source,
                           c.after_state ->> 'verdict' AS after_verdict,
                           c.after_state ->> 'reason' AS after_reason,
                           c.after_state ->> 'action' AS after_action,
                           c.after_state ->> 'source' AS after_source,
                           c.changed, ri.version AS current_version, ri.line_sum::text AS current_line_sum,
                           ri.verdict AS current_verdict, ri.review_reason AS current_reason,
                           ri.review_action AS current_action, ri.verdict_source AS current_source
                    FROM recalculation_changes c
                    JOIN receipt_items ri ON ri.tenant_id = c.tenant_id AND ri.id = c.receipt_item_id
                                          AND ri.receipt_id = c.receipt_id
                    WHERE c.tenant_id = ? AND c.run_id = ?
                    ORDER BY c.receipt_item_id
                    FOR UPDATE OF c, ri
                    """, (rs, row) -> new CurrentChange(rs.getObject("receipt_id", UUID.class),
                    rs.getObject("receipt_item_id", UUID.class), rs.getLong("expected_item_version"),
                    rs.getString("line_sum"), rs.getString("name"), rs.getString("before_verdict"),
                    rs.getString("before_reason"), rs.getString("before_action"), rs.getString("before_source"),
                    rs.getString("after_verdict"), rs.getString("after_reason"), rs.getString("after_action"),
                    rs.getString("after_source"), rs.getBoolean("changed"), rs.getLong("current_version"),
                    rs.getString("current_line_sum"), rs.getString("current_verdict"), rs.getString("current_reason"),
                    rs.getString("current_action"), rs.getString("current_source")), tenantId, run.id());
            for (CurrentChange change : current) {
                if (!matchesPreview(change)) {
                    throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                            "Receipt history changed; create a new recalculation preview");
                }
            }

            for (CurrentChange change : current) {
                String advice = adviceText(change.afterReason(), change.afterAction());
                int updated = jdbc.update("""
                        UPDATE receipt_items SET verdict = ?, advice = ?, review_reason = ?, review_action = ?,
                          verdict_source = 'rule', review_provider = NULL, review_model_version = NULL,
                          review_prompt_version = NULL, review_algorithm_version = ?,
                          version = version + 1, updated_at = now()
                        WHERE tenant_id = ? AND receipt_id = ? AND id = ? AND version = ?
                        """, change.afterVerdict(), advice, emptyToNull(change.afterReason()),
                        emptyToNull(change.afterAction()), run.algorithmVersion(), tenantId, change.receiptId(),
                        change.itemId(), change.expectedVersion());
                if (updated != 1) {
                    throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                            "Receipt history changed; create a new recalculation preview");
                }
                Map<String, Object> before = itemState(change.name(), decimalOrNull(change.lineSum()),
                        change.beforeVerdict(), change.beforeReason(), change.beforeAction(), change.beforeSource());
                Map<String, Object> after = itemState(change.name(), decimalOrNull(change.lineSum()),
                        change.afterVerdict(), change.afterReason(), change.afterAction(), change.afterSource());
                Map<String, Object> runBefore = Map.of("runId", run.id().toString(), "state", "previewed");
                Map<String, Object> runAfter = Map.of("runId", run.id().toString(), "state", "applied");
                jdbc.update("""
                        INSERT INTO receipt_reviews
                          (tenant_id, receipt_id, receipt_item_id, item_snapshot, actor_subject, action,
                           before_state, after_state, verdict_source, algorithm_version)
                        VALUES (?, ?, ?, CAST(? AS jsonb), ?, 'receipt.verdicts_recalculated',
                                CAST(? AS jsonb), CAST(? AS jsonb), 'rule', ?)
                        """, tenantId, change.receiptId(), change.itemId(),
                        serialize(Map.of("before", before, "after", after)), subject,
                        serialize(runBefore), serialize(runAfter), run.algorithmVersion());
            }
            jdbc.update("UPDATE recalculation_runs SET state = 'applied', applied_at = now() "
                    + "WHERE tenant_id = ? AND id = ? AND state = 'previewed'", tenantId, run.id());
            return new ApplyResult(run.id(), run.algorithmVersion(), "applied", run.updateCount(),
                    run.changedCount(), run.impact(), changes);
        });
    }

    private List<Change> loadChanges(UUID tenantId, UUID runId) {
        return jdbc.query("""
                SELECT receipt_item_id, expected_item_version, line_sum::text AS line_sum,
                       before_state ->> 'name' AS name,
                       before_state ->> 'verdict' AS before_verdict,
                       before_state ->> 'reason' AS before_reason,
                       before_state ->> 'action' AS before_action,
                       before_state ->> 'source' AS before_source,
                       after_state ->> 'verdict' AS after_verdict,
                       after_state ->> 'reason' AS after_reason,
                       after_state ->> 'action' AS after_action,
                       after_state ->> 'source' AS after_source, changed
                FROM recalculation_changes WHERE tenant_id = ? AND run_id = ? ORDER BY receipt_item_id
                """, (rs, row) -> new Change(rs.getObject("receipt_item_id", UUID.class), rs.getString("name"),
                rs.getString("line_sum"), rs.getLong("expected_item_version"), rs.getString("before_verdict"),
                rs.getString("before_reason"), rs.getString("before_action"), rs.getString("before_source"),
                rs.getString("after_verdict"), rs.getString("after_reason"), rs.getString("after_action"),
                rs.getString("after_source"), rs.getBoolean("changed")), tenantId, runId);
    }

    private Actor actor(UUID tenantId, String subject) {
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        }
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
        List<Actor> actors = jdbc.query("""
                SELECT user_id, role FROM memberships
                WHERE tenant_id = ? AND subject = ? AND status = 'active'
                """, (rs, row) -> new Actor(rs.getObject("user_id", UUID.class), rs.getString("role")),
                tenantId, subject);
        if (actors.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        Actor actor = actors.get(0);
        if ("viewer".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Viewer access is read-only");
        }
        return actor;
    }

    private static Change toChange(ReceiptRecalculationPolicy.Update update, long version) {
        return new Change(update.itemId(), update.name(), update.lineSum() == null ? null : update.lineSum().toPlainString(),
                version, update.beforeVerdict(), update.beforeReason(), update.beforeAction(), update.beforeSource(),
                update.afterVerdict(), update.afterReason(), update.afterAction(), update.afterSource(), update.changed());
    }

    private static Map<String, Object> itemState(String name, BigDecimal lineSum, String verdict, String reason,
                                                  String action, String source) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("name", name);
        state.put("lineSum", lineSum == null ? null : lineSum.toPlainString());
        state.put("verdict", verdict);
        state.put("reason", reason);
        state.put("action", action);
        state.put("source", source);
        return state;
    }

    private static boolean matchesPreview(CurrentChange change) {
        return change.currentVersion() == change.expectedVersion()
                && sameDecimal(change.currentLineSum(), change.lineSum())
                && same(change.currentVerdict(), change.beforeVerdict())
                && same(change.currentReason(), change.beforeReason())
                && same(change.currentAction(), change.beforeAction())
                && same(change.currentSource(), change.beforeSource());
    }

    private static boolean same(String left, String right) {
        return (left == null ? "" : left).equals(right == null ? "" : right);
    }

    private static boolean sameDecimal(String left, String right) {
        if (left == null || right == null) return left == null && right == null;
        return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
    }

    private static BigDecimal decimalOrNull(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static String adviceText(String reason, String action) {
        if (reason == null || reason.isBlank()) return emptyToNull(action);
        if (action == null || action.isBlank()) return reason;
        String value = reason + " — " + action;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize receipt recalculation snapshot", exception);
        }
    }

    private Impact readImpact(String value) {
        if (value == null) return null;
        try {
            return json.readValue(value, Impact.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not read receipt recalculation impact snapshot", exception);
        }
    }

    private record Actor(UUID userId, String role) {}
    private record HistoryCursor(Instant createdAt, UUID runId) {}
    private record Run(UUID id, String algorithmVersion, String state, int updateCount, int changedCount, Impact impact) {}
    private record StoredLine(UUID receiptId, UUID itemId, String name, BigDecimal lineSum, String verdict,
                              String reason, String action, String source, long version, Instant purchasedAt) {}
    private record DecisionKey(UUID ownerId, String productKey) {}
    private record ImpactWindow(LocalDate from, LocalDate to, Instant asOf, Instant start, Instant end) {}
    private record PreparedPreview(Actor actor, ReceiptRecalculationPolicy.Plan plan, Map<UUID, StoredLine> storedById,
                                   AdviceWasteApi.Request before, AdviceWasteApi.Request after, String currency) {}
    private record CurrentChange(UUID receiptId, UUID itemId, long expectedVersion, String lineSum, String name,
                                 String beforeVerdict, String beforeReason, String beforeAction, String beforeSource,
                                 String afterVerdict, String afterReason, String afterAction, String afterSource,
                                 boolean changed, long currentVersion, String currentLineSum, String currentVerdict,
                                 String currentReason, String currentAction, String currentSource) {}
}
