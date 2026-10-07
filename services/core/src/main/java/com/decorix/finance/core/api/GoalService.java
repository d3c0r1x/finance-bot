package com.decorix.finance.core.api;

import com.decorix.finance.core.api.GoalCandidatesApi.AcceptRequest;
import com.decorix.finance.core.api.GoalCandidatesApi.Candidate;
import com.decorix.finance.core.api.GoalCandidatesApi.CandidateReport;
import com.decorix.finance.core.api.GoalCandidatesApi.Decision;
import com.decorix.finance.core.api.GoalCandidatesApi.Goal;
import com.decorix.finance.core.api.GoalCandidatesApi.GoalOutcome;
import com.decorix.finance.core.api.GoalCandidatesApi.GoalProgress;
import com.decorix.finance.core.api.GoalCandidatesApi.GoalUnitResponse;
import com.decorix.finance.core.api.GoalCandidatesApi.Overview;
import com.decorix.finance.core.api.GoalCandidatesApi.Purchase;
import com.decorix.finance.core.api.GoalCandidatesApi.F45GoalInput;
import com.decorix.finance.core.api.GoalCandidatesApi.F45PurchaseInput;
import com.decorix.finance.core.api.GoalCandidatesApi.ProgressRequest;
import com.decorix.finance.core.api.GoalCandidatesApi.Request;
import com.decorix.finance.core.api.GoalCandidatesApi.SkippedCandidate;
import com.decorix.finance.core.domain.ProductIdentityPolicy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class GoalService {
    private static final int MAX_ITEMS = 50_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final GoalCandidatesClient candidates;
    private final GoalProgressClient progressClient;
    private final ObjectMapper json;

    public GoalService(JdbcTemplate jdbc, TransactionTemplate transaction,
                       GoalCandidatesClient candidates, GoalProgressClient progressClient, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.candidates = candidates;
        this.progressClient = progressClient;
        this.json = json;
    }

    public Overview get(UUID tenantId, String subject) {
        Snapshot snapshot = transaction.execute(status -> snapshot(tenantId, subject, false));
        CandidateReport report = candidates.calculate(snapshot.request());
        GoalProgress progress = null;
        if (snapshot.active() != null) {
            Goal active = snapshot.active();
            F45GoalInput terms = new F45GoalInput(active.key(), active.scope(), active.unit(), active.acceptedAt(),
                    active.endsAt(), active.countTarget(), active.monthlyLimit(), snapshot.activeMembers());
            List<F45PurchaseInput> purchases = snapshot.request().purchases().stream()
                    .map(purchase -> new F45PurchaseInput(purchase.productKey(), purchase.lineSum(), purchase.purchasedAt()))
                    .toList();
            progress = progressClient.calculate(new ProgressRequest(snapshot.request().inputWatermark(),
                    snapshot.request().asOf(), terms, purchases));
            if (progress.finished() && snapshot.canWrite()) {
                if (closeIfFinished(tenantId, subject, active.id(), progress)) return get(tenantId, subject);
            }
        }
        return new Overview(snapshot.unit(), snapshot.active(), report.inputWatermark(),
                report.products(), report.groups(), report.skipped(), progress, snapshot.history());
    }

    public Goal accept(UUID tenantId, String subject, AcceptRequest request) {
        validateAccept(tenantId, subject, request);
        Overview overview = get(tenantId, subject);
        Candidate selected = java.util.stream.Stream.concat(overview.candidates().stream(), overview.groups().stream())
                .filter(candidate -> candidate.key().equals(request.candidateKey())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Goal candidate is stale"));
        if (!overview.inputWatermark().equals(request.inputWatermark()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Goal candidate is stale");

        try {
            return transaction.execute(status -> {
                Actor actor = actor(tenantId, subject, true);
                String unit = preference(tenantId, actor.userId());
                Snapshot current = snapshot(tenantId, subject, false, actor, unit);
                if (!request.inputWatermark().equals(current.request().inputWatermark()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Goal candidate is stale");
                if (hasActiveGoal(tenantId, actor.userId()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "An active goal already exists");

                UUID id = UUID.randomUUID();
                String scope = selected.key().startsWith("cat:") ? "group" : "product";
                Instant acceptedAt = Instant.now();
                String after = serialize(selected);
                jdbc.update("""
                        INSERT INTO goals (id, tenant_id, owner_user_id, goal_key, goal_scope, display_name, unit,
                            baseline_rate, count_target, baseline_monthly_spend, monthly_limit, evidence_count,
                            input_watermark, accepted_at, ends_at, member_product_keys)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                        """, id, tenantId, actor.userId(), selected.key(), scope, selected.name(), selected.unit(),
                        new BigDecimal(selected.monthlyRate()), selected.countTarget(), decimal(selected.monthlySpend()),
                        decimal(selected.monthlyLimit()), selected.evidenceCount(), request.inputWatermark(),
                        Timestamp.from(acceptedAt), Timestamp.from(acceptedAt.plus(java.time.Duration.ofDays(30))),
                        serialize(acceptedMembers(selected, scope)));
                Goal goal = goal(tenantId, actor.userId(), id);
                audit(tenantId, subject, "goal", "goal.accepted", id, null, goal);
                outbox(tenantId, "goal", id, 1, "goal.accepted", goal);
                return goal;
            });
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An active goal already exists", conflict);
        }
    }

    public GoalUnitResponse updateUnit(UUID tenantId, String subject, GoalCandidatesApi.GoalUnitRequest request) {
        if (request == null || !("count".equals(request.unit()) || "sum".equals(request.unit())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal unit must be count or sum");
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, true);
            String before = preference(tenantId, actor.userId());
            if (!before.equals(request.unit())) {
                jdbc.update("UPDATE member_profiles SET goal_measure_unit = ?, updated_at = now() WHERE tenant_id = ? AND user_id = ?",
                        request.unit(), tenantId, actor.userId());
                Map<String, Object> state = Map.of("unit", request.unit());
                audit(tenantId, subject, "member_profile", "goal.unit_changed", actor.userId(), Map.of("unit", before), state);
                outbox(tenantId, "member_profile", actor.userId(), 1, "goal.unit_changed", state);
            }
            return new GoalUnitResponse(request.unit());
        });
    }

    public Goal cancel(UUID tenantId, String subject, UUID goalId) {
        if (goalId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid goal id");
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, true);
            Goal before = goalOrNull(tenantId, actor.userId(), goalId);
            if (before == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found");
            if (!"active".equals(before.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Goal is not active");
            jdbc.update("UPDATE goals SET status='cancelled', version=version+1, updated_at=now() WHERE tenant_id=? AND owner_user_id=? AND id=? AND status='active'",
                    tenantId, actor.userId(), goalId);
            Goal after = goal(tenantId, actor.userId(), goalId);
            audit(tenantId, subject, "goal", "goal.cancelled", goalId, before, after);
            outbox(tenantId, "goal", goalId, after.version(), "goal.cancelled", after);
            return after;
        });
    }

    private Snapshot snapshot(UUID tenantId, String subject, boolean write) {
        Actor actor = actor(tenantId, subject, write);
        return snapshot(tenantId, subject, write, actor, preference(tenantId, actor.userId()));
    }

    private Snapshot snapshot(UUID tenantId, String subject, boolean write, Actor actor, String unit) {
        List<Fact> facts = jdbc.query("""
                SELECT ri.name, ri.line_sum, ri.verdict, ri.verdict_source, t.occurred_at
                FROM receipts r JOIN transactions t ON t.tenant_id=r.tenant_id AND t.id=r.transaction_id
                JOIN receipt_items ri ON ri.tenant_id=r.tenant_id AND ri.receipt_id=r.id
                WHERE r.tenant_id=? AND r.owner_user_id=? AND r.state='confirmed'
                  AND t.status='posted' AND t.type='expense'
                ORDER BY t.occurred_at, r.id, ri.ordinal, ri.id LIMIT 50001
                """, (rs, row) -> new Fact(rs.getString("name"), rs.getBigDecimal("line_sum"),
                rs.getString("verdict"), rs.getString("verdict_source"), rs.getTimestamp("occurred_at").toInstant()),
                tenantId, actor.userId());
        if (facts.size() > MAX_ITEMS) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "too_many_items");

        Map<String, DecisionBuilder> grouped = new HashMap<>();
        List<Purchase> purchases = new ArrayList<>(facts.size());
        for (Fact fact : facts) {
            String key = ProductIdentityPolicy.productKey(fact.name());
            if (key.isBlank()) continue;
            purchases.add(new Purchase(key, fact.name(), fact.lineSum() == null ? null : fact.lineSum().toPlainString(), fact.purchasedAt()));
            if ("harmful".equals(fact.verdict()) || "unnecessary".equals(fact.verdict())) {
                DecisionBuilder builder = grouped.computeIfAbsent(key, ignored -> new DecisionBuilder(key, fact.name()));
                builder.harmful++;
                if ("model".equals(fact.source())) builder.model++;
            }
        }
        Map<String, String> decisions = new HashMap<>();
        jdbc.query("SELECT product_key, decision FROM user_product_decisions WHERE tenant_id=? AND user_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> decisions.put(rs.getString(1), rs.getString(2)),
                tenantId, actor.userId());
        List<Decision> decisionRows = grouped.values().stream().map(builder -> {
            String decision = decisions.get(builder.key);
            return new Decision(builder.key, builder.name, builder.harmful, builder.model == builder.harmful,
                    "confirmed".equals(decision), "allowed".equals(decision));
        }).sorted(java.util.Comparator.comparing(Decision::productKey)).toList();
        if (!withinInputBound(decisionRows.size(), purchases.size()))
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "too_many_items");
        String watermark = GoalCandidatesClient.watermark(unit, decisionRows, purchases, json);
        Request request = new Request(watermark, Instant.now(), unit, decisionRows, purchases);
        Goal active = activeGoal(tenantId, actor.userId());
        List<String> members = active == null ? List.of() : memberProductKeys(tenantId, actor.userId(), active.id());
        return new Snapshot(unit, active, members, history(tenantId, actor.userId()), request,
                !"viewer".equals(actor.role()));
    }

    private Actor actor(UUID tenantId, String subject, boolean write) {
        if (tenantId == null || subject == null || subject.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found");
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
        List<Actor> actors = jdbc.query("SELECT user_id, role FROM memberships WHERE tenant_id=? AND subject=? AND status='active'",
                (rs, row) -> new Actor(rs.getObject("user_id", UUID.class), rs.getString("role")), tenantId, subject);
        if (actors.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found");
        Actor actor = actors.get(0);
        if (write && "viewer".equals(actor.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Viewer access is read-only");
        return actor;
    }

    private String preference(UUID tenantId, UUID userId) {
        return jdbc.query("SELECT goal_measure_unit FROM member_profiles WHERE tenant_id=? AND user_id=?",
                (rs, row) -> rs.getString(1), tenantId, userId).stream().findFirst().orElse("count");
    }

    private boolean hasActiveGoal(UUID tenantId, UUID userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM goals WHERE tenant_id=? AND owner_user_id=? AND status='active'",
                Integer.class, tenantId, userId);
        return count != null && count > 0;
    }

    private Goal activeGoal(UUID tenantId, UUID userId) {
        return jdbc.query("SELECT id FROM goals WHERE tenant_id=? AND owner_user_id=? AND status='active'",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, userId).stream().findFirst()
                .map(id -> goal(tenantId, userId, id)).orElse(null);
    }

    private List<String> memberProductKeys(UUID tenantId, UUID userId, UUID goalId) {
        List<String> raw = jdbc.query("SELECT member_product_keys::text FROM goals WHERE tenant_id=? AND owner_user_id=? AND id=?",
                (rs, row) -> rs.getString(1), tenantId, userId, goalId);
        if (raw.isEmpty()) return List.of();
        try {
            return json.readValue(raw.get(0), json.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored goal membership is invalid", exception);
        }
    }

    private List<GoalOutcome> history(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT id, goal_id, goal_snapshot->>'key' AS goal_key, goal_snapshot->>'name' AS goal_name,
                    goal_snapshot->>'scope' AS goal_scope, goal_snapshot->>'unit' AS goal_unit,
                    COALESCE((goal_snapshot->>'countTarget')::integer, 0) AS count_target,
                    goal_snapshot->>'monthlyLimit' AS monthly_limit,
                    COALESCE((progress_snapshot->>'bought')::integer, 0) AS bought,
                    progress_snapshot->>'spent' AS spent,
                    CASE WHEN progress_snapshot->'met' = 'null'::jsonb THEN NULL
                         ELSE (progress_snapshot->>'met')::boolean END AS met,
                    accepted_at, completed_at, origin
                FROM goal_outcomes WHERE tenant_id=? AND owner_user_id=? AND
                    (origin = 'legacy' OR id IN (
                        SELECT id FROM goal_outcomes WHERE tenant_id=? AND owner_user_id=? AND origin='completed'
                        ORDER BY completed_at DESC, id DESC LIMIT 24
                    ))
                ORDER BY completed_at DESC, id DESC
                """, (rs, row) -> new GoalOutcome(rs.getObject("id", UUID.class), rs.getObject("goal_id", UUID.class),
                rs.getString("goal_key"), rs.getString("goal_name"), rs.getString("goal_scope"), rs.getString("goal_unit"),
                rs.getInt("count_target"), rs.getString("monthly_limit"), rs.getInt("bought"), rs.getString("spent"),
                (Boolean) rs.getObject("met"), rs.getTimestamp("accepted_at").toInstant(),
                rs.getTimestamp("completed_at").toInstant(), rs.getString("origin")), tenantId, userId, tenantId, userId);
    }

    private boolean closeIfFinished(UUID tenantId, String subject, UUID goalId, GoalProgress progress) {
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, true);
            Goal before = goalOrNull(tenantId, actor.userId(), goalId);
            if (before == null || !"active".equals(before.status()) || !progress.finished()) return false;
            Snapshot current = snapshot(tenantId, subject, false, actor, preference(tenantId, actor.userId()));
            if (current.active() == null || !goalId.equals(current.active().id())
                    || !progress.inputWatermark().equals(current.request().inputWatermark())) return false;
            int changed = jdbc.update("UPDATE goals SET status='completed', version=version+1, updated_at=now() "
                            + "WHERE tenant_id=? AND owner_user_id=? AND id=? AND status='active' AND ends_at < ?",
                    tenantId, actor.userId(), goalId, Timestamp.from(current.request().asOf()));
            if (changed != 1) return false;
            Goal completed = goal(tenantId, actor.userId(), goalId);
            jdbc.update("""
                    INSERT INTO goal_outcomes (tenant_id, owner_user_id, goal_id, origin, goal_snapshot,
                        progress_snapshot, accepted_at, completed_at)
                    VALUES (?, ?, ?, 'completed', CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                    ON CONFLICT (tenant_id, goal_id) WHERE goal_id IS NOT NULL DO NOTHING
                    """, tenantId, actor.userId(), goalId, serialize(completed), serialize(progress),
                    Timestamp.from(completed.acceptedAt()), Timestamp.from(current.request().asOf()));
            jdbc.update("""
                    DELETE FROM goal_outcomes WHERE tenant_id=? AND owner_user_id=? AND origin='completed'
                      AND id IN (SELECT id FROM goal_outcomes WHERE tenant_id=? AND owner_user_id=? AND origin='completed'
                                 ORDER BY completed_at DESC, id DESC OFFSET 24)
                    """, tenantId, actor.userId(), tenantId, actor.userId());
            audit(tenantId, subject, "goal", "goal.completed", goalId, before, completed);
            outbox(tenantId, "goal", goalId, completed.version(), "goal.completed", completed);
            return true;
        });
    }

    private static List<String> acceptedMembers(Candidate candidate, String scope) {
        List<String> members = candidate.memberProductKeys() == null ? List.of() : candidate.memberProductKeys();
        if ("group".equals(scope) != !members.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Goal candidate membership is invalid");
        if (members.size() > MAX_ITEMS || new java.util.HashSet<>(members).size() != members.size()
                || members.stream().anyMatch(key -> key == null || !key.matches("[a-zа-я0-9]{1,256}")))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Goal candidate membership is invalid");
        return members;
    }

    private Goal goalOrNull(UUID tenantId, UUID userId, UUID id) {
        return jdbc.query("SELECT id FROM goals WHERE tenant_id=? AND owner_user_id=? AND id=?",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, userId, id).stream().findFirst()
                .map(goalId -> goal(tenantId, userId, goalId)).orElse(null);
    }

    private Goal goal(UUID tenantId, UUID userId, UUID id) {
        List<Goal> rows = jdbc.query("""
                SELECT id, goal_key, goal_scope, display_name, unit, baseline_rate::text, count_target,
                    baseline_monthly_spend::text, monthly_limit::text, evidence_count, input_watermark,
                    accepted_at, ends_at, status, version
                FROM goals WHERE tenant_id=? AND owner_user_id=? AND id=?
                """, (rs, row) -> new Goal(rs.getObject("id", UUID.class), rs.getString("goal_key"),
                rs.getString("goal_scope"), rs.getString("display_name"), rs.getString("unit"),
                rs.getString("baseline_rate"), rs.getInt("count_target"), rs.getString("baseline_monthly_spend"),
                rs.getString("monthly_limit"), rs.getInt("evidence_count"), rs.getString("input_watermark"),
                rs.getTimestamp("accepted_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                rs.getString("status"), rs.getLong("version")), tenantId, userId, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found");
        return rows.get(0);
    }

    private void audit(UUID tenantId, String subject, String entityType, String action, UUID entityId, Object before, Object after) {
        jdbc.update("""
                INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id, before_state, after_state, trace_id)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                """, tenantId, subject, action, entityType, entityId,
                before == null ? null : serialize(before), serialize(after), UUID.randomUUID().toString());
    }

    private void outbox(UUID tenantId, String aggregateType, UUID id, long version, String eventType, Object payload) {
        jdbc.update("""
                INSERT INTO outbox_events (event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, UUID.randomUUID(), tenantId, aggregateType, id, version, eventType, serialize(payload));
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException error) { throw new IllegalStateException("Goal event serialization failed", error); }
    }

    private static BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }

    static boolean withinInputBound(int decisions, int purchases) {
        return decisions >= 0 && purchases >= 0 && purchases <= MAX_ITEMS
                && decisions <= MAX_ITEMS - purchases;
    }


    private static void validateAccept(UUID tenantId, String subject, AcceptRequest request) {
        if (tenantId == null || subject == null || subject.isBlank() || request == null
                || request.candidateKey() == null || request.candidateKey().isBlank() || request.candidateKey().length() > 300
                || request.inputWatermark() == null || !request.inputWatermark().matches("[1-9][0-9]{0,19}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid goal acceptance");
    }

    private record Actor(UUID userId, String role) {}
    private record Fact(String name, BigDecimal lineSum, String verdict, String source, Instant purchasedAt) {}
    private record Snapshot(String unit, Goal active, List<String> activeMembers, List<GoalOutcome> history,
                            Request request, boolean canWrite) {}
    private static final class DecisionBuilder {
        final String key;
        final String name;
        int harmful;
        int model;
        DecisionBuilder(String key, String name) { this.key = key; this.name = name; }
    }
}
