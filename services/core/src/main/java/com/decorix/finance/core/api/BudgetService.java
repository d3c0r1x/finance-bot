package com.decorix.finance.core.api;

import com.decorix.finance.core.api.BudgetApi.Overview;
import com.decorix.finance.core.api.BudgetApi.UpdateRequest;
import com.decorix.finance.core.api.BudgetApi.BudgetProposalRequest;
import com.decorix.finance.core.api.BudgetApi.BudgetProposalResponse;
import com.decorix.finance.core.domain.BudgetProposalPolicy;
import com.decorix.finance.core.domain.RollingFoodBudgetPolicy;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BudgetService {
    private static final Map<String, String> DEFAULT_LIMITS = Map.ofEntries(
            Map.entry("еда", "20000.00"), Map.entry("транспорт", "5000.00"),
            Map.entry("жилье", "0.00"), Map.entry("досуг", "5000.00"),
            Map.entry("одежда", "10000.00"), Map.entry("здоровье", "0.00"),
            Map.entry("работа", "0.00"), Map.entry("техника", "10000.00"),
            Map.entry("долги", "0.00"), Map.entry("прочее", "5000.00"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final BudgetProposalAdvisor advisor;

    public BudgetService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json,
                         BudgetProposalAdvisor advisor) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
        this.advisor = advisor;
    }

    public Overview get(UUID tenantId, String subject) {
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = resolveUser(subject);
            if (userId == null || !isMember(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            }
            Map<String, String> family = new LinkedHashMap<>(DEFAULT_LIMITS);
            Map<String, String> personal = new LinkedHashMap<>();
            Map<String, Long> familyVersions = new LinkedHashMap<>();
            Map<String, Long> personalVersions = new LinkedHashMap<>();
            DEFAULT_LIMITS.keySet().forEach(key -> familyVersions.put(key, 0L));
            Map<String, StoredBudget> rows = jdbc.query("""
                    SELECT budget_key, owner_user_id::text AS owner_id, amount::numeric(20,2)::text AS amount,
                           version, active
                    FROM tenant_budgets
                    WHERE tenant_id = ? AND period = 'monthly' AND (owner_user_id IS NULL OR owner_user_id = ?)
                    """, rs -> {
                Map<String, StoredBudget> stored = new LinkedHashMap<>();
                while (rs.next()) {
                    String key = rs.getString("budget_key");
                    String owner = rs.getString("owner_id");
                    stored.put((owner == null ? "family:" : "personal:") + key,
                            new StoredBudget(null, key, rs.getString("amount"), rs.getLong("version"), rs.getBoolean("active")));
                }
                return stored;
            }, tenantId, userId);
            String personalTotal = null;
            long familyTotalVersion = 0;
            long personalTotalVersion = 0;
            for (var entry : rows.entrySet()) {
                String[] parts = entry.getKey().split(":", 2);
                StoredBudget stored = entry.getValue();
                if ("family".equals(parts[0])) {
                    if ("__total__".equals(parts[1])) familyTotalVersion = stored.version();
                    else familyVersions.put(parts[1], stored.version());
                    if (stored.active()) family.put(parts[1], stored.amount());
                } else {
                    if ("__total__".equals(parts[1])) personalTotalVersion = stored.version();
                    else personalVersions.put(parts[1], stored.version());
                    if (stored.active()) {
                        if ("__total__".equals(parts[1])) personalTotal = stored.amount();
                        else personal.put(parts[1], stored.amount());
                    }
                }
            }
            String familyTotal = family.getOrDefault("__total__", "55000.00");
            family.remove("__total__");
            Map<String, String> effective = new LinkedHashMap<>(family);
            effective.putAll(personal);
            ZoneId zone = ZoneId.of(jdbc.queryForObject(
                    "SELECT timezone FROM member_profiles WHERE tenant_id = ? AND user_id = ?", String.class, tenantId, userId));
            YearMonth month = YearMonth.now(zone);
            Instant start = month.atDay(1).atStartOfDay(zone).toInstant();
            Instant end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
            Map<String, String> spent = jdbc.query("""
                    SELECT category_code, (COALESCE(sum(amount) FILTER (WHERE type = 'expense'), 0)
                           - COALESCE(sum(amount) FILTER (WHERE type = 'refund'), 0))::numeric(20,2)::text AS spent
                    FROM transactions WHERE tenant_id = ? AND owner_user_id = ? AND status = 'posted'
                      AND type IN ('expense', 'refund') AND occurred_at >= ? AND occurred_at < ?
                    GROUP BY category_code
                    """, rs -> {
                Map<String, String> totals = new LinkedHashMap<>();
                while (rs.next()) totals.put(rs.getString("category_code"), rs.getString("spent"));
                return totals;
            }, tenantId, userId, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));
            BigDecimal totalSpent = spent.values().stream().map(BigDecimal::new)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, String> limitStatus = new LinkedHashMap<>();
            for (String category : effective.keySet()) {
                BigDecimal amount = new BigDecimal(spent.getOrDefault(category, "0.00"));
                limitStatus.put(category, budgetStatus(amount, new BigDecimal(effective.get(category))));
            }
            String totalLimit = personalTotal == null ? familyTotal : personalTotal;
            Map<String, StoredBudget> rollingRows = jdbc.query("""
                    SELECT id, owner_user_id::text AS owner_id, amount::numeric(20,2)::text AS amount, version, active
                    FROM tenant_budgets WHERE tenant_id = ? AND budget_key = 'еда' AND period = 'rolling7'
                      AND (owner_user_id IS NULL OR owner_user_id = ?)
                    """, rs -> {
                Map<String, StoredBudget> stored = new LinkedHashMap<>();
                while (rs.next()) {
                    String owner = rs.getString("owner_id");
                    stored.put(owner == null ? "family" : "personal", new StoredBudget(rs.getObject("id", UUID.class),
                            "еда", "rolling7", rs.getString("amount"), rs.getLong("version"), rs.getBoolean("active")));
                }
                return stored;
            }, tenantId, userId);
            String familyRollingLimit = "0.00";
            String personalRollingLimit = null;
            long familyRollingVersion = 0;
            long personalRollingVersion = 0;
            for (var rollingEntry : rollingRows.entrySet()) {
                StoredBudget rolling = rollingEntry.getValue();
                if ("personal".equals(rollingEntry.getKey())) {
                    personalRollingVersion = rolling.version();
                    if (rolling.active()) personalRollingLimit = rolling.amount();
                } else {
                    familyRollingVersion = rolling.version();
                    if (rolling.active()) familyRollingLimit = rolling.amount();
                }
            }
            String effectiveRollingLimit = personalRollingLimit == null ? familyRollingLimit : personalRollingLimit;
            LocalDate today = java.time.LocalDate.now(zone);
            BudgetApi.RollingFoodStatus foodStatus = calculateRollingFoodStatus(
                    tenantId, userId, zone, today, effectiveRollingLimit);
            String rollingSpent = foodStatus.spent();
            return new Overview("RUB", month.toString(), Map.copyOf(family), Map.copyOf(personal), Map.copyOf(effective),
                    Map.copyOf(spent), Map.copyOf(limitStatus), Map.copyOf(familyVersions), Map.copyOf(personalVersions),
                    familyTotal, personalTotal, totalLimit, totalSpent.setScale(2).toPlainString(),
                    budgetStatus(totalSpent, new BigDecimal(totalLimit)), familyTotalVersion, personalTotalVersion,
                    familyRollingLimit, personalRollingLimit, effectiveRollingLimit, rollingSpent,
                    foodStatus.limitStatus(), familyRollingVersion, personalRollingVersion, foodStatus);
        });
    }

    public BudgetApi.RollingFoodStatus rollingFoodStatus(UUID tenantId, String subject, boolean family) {
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = resolveUser(subject);
            if (userId == null || !isMember(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            }
            Overview overview = get(tenantId, subject);
            String timezone = jdbc.queryForObject("SELECT timezone FROM member_profiles WHERE tenant_id = ? AND user_id = ?",
                    String.class, tenantId, userId);
            ZoneId zone = ZoneId.of(timezone);
            return calculateRollingFoodStatus(tenantId, family ? null : userId, zone, LocalDate.now(zone),
                    family ? overview.rolling7FoodLimit() : overview.effectiveRolling7FoodLimit());
        });
    }

    private BudgetApi.RollingFoodStatus calculateRollingFoodStatus(UUID tenantId, UUID ownerId, ZoneId zone,
                                                                    LocalDate today, String limit) {
        Map<Integer, String> byWeek = new LinkedHashMap<>();
        Instant from = today.minusDays(41).atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();
        jdbc.query("""
                SELECT ((?::date - (occurred_at AT TIME ZONE ?)::date) / 7)::integer AS week_index,
                       sum(amount)::numeric(20,2)::text AS spent
                FROM transactions
                WHERE tenant_id = ? AND status = 'posted' AND type = 'expense' AND category_code = 'еда'
                  AND (?::uuid IS NULL OR owner_user_id = ?)
                  AND occurred_at >= ? AND occurred_at < ?
                GROUP BY week_index
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                byWeek.put(rs.getInt("week_index"), rs.getString("spent")),
                java.sql.Date.valueOf(today), zone.getId(), tenantId, ownerId, ownerId,
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        Map<Integer, String> pastWeeks = new LinkedHashMap<>();
        byWeek.forEach((index, spent) -> {
            if (index >= 1 && index <= 5) pastWeeks.put(index, spent);
        });
        String currentSpent = byWeek.getOrDefault(0, "0.00");
        RollingFoodBudgetPolicy.Status status = RollingFoodBudgetPolicy.evaluate(today, limit, currentSpent, pastWeeks);
        return new BudgetApi.RollingFoodStatus(status.fromDate(), status.toDate(), status.limit(), status.spent(),
                status.remaining(), status.limitStatus(), status.usualWeeklySpend(), status.historyWeeks(),
                status.paceStatus(), status.paceShare());
    }

    private static String budgetStatus(BigDecimal spent, BigDecimal limit) {
        if (limit.signum() == 0) return "disabled";
        if (spent.compareTo(limit) >= 0) return "exceeded";
        if (spent.multiply(BigDecimal.valueOf(100)).compareTo(limit.multiply(BigDecimal.valueOf(90))) >= 0) return "near";
        return "normal";
    }

    public Overview update(UUID tenantId, String subject, String key, String idempotencyKey,
                           long expectedVersion, UpdateRequest request) {
        String amount = validate(tenantId, subject, key, idempotencyKey, expectedVersion, request);
        String period = normalizePeriod(request.period());
        String route = "/budgets/" + key + "/" + request.scope() + "/" + period;
        String requestHash = sha256(key + "|" + request.scope() + "|" + period + "|" + amount + "|" + expectedVersion);
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = resolveUser(subject);
            String role = membershipRole(tenantId, userId);
            if (role == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            if ("family".equals(request.scope()) && !List.of("owner", "admin").contains(role)
                    || "personal".equals(request.scope()) && "viewer".equals(role)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient budget permissions");
            }
            List<UUID> inserted = jdbc.query(
                    "INSERT INTO idempotency_records (tenant_id, actor_subject, route, idempotency_key, request_hash) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                    (rs, row) -> rs.getObject(1, UUID.class), tenantId, subject, route, idempotencyKey, requestHash);
            if (inserted.isEmpty()) return replay(tenantId, subject, route, idempotencyKey, requestHash);

            jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", UUID.class, tenantId);
            UUID ownerId = "personal".equals(request.scope()) ? userId : null;
            String storedQuery = ownerId == null ? """
                    SELECT id, amount::numeric(20,2)::text AS amount, version, active
                    FROM tenant_budgets WHERE tenant_id = ? AND budget_key = ? AND period = ? AND owner_user_id IS NULL FOR UPDATE
                    """ : """
                    SELECT id, amount::numeric(20,2)::text AS amount, version, active
                    FROM tenant_budgets WHERE tenant_id = ? AND budget_key = ? AND period = ? AND owner_user_id = ? FOR UPDATE
                    """;
            List<Object> storedArgs = ownerId == null ? List.of(tenantId, key, period) : List.of(tenantId, key, period, ownerId);
            List<StoredBudget> oldRows = jdbc.query(storedQuery,
                    (rs, row) -> new StoredBudget(rs.getObject("id", UUID.class), key, period, rs.getString("amount"), rs.getLong("version"), rs.getBoolean("active")),
                    storedArgs.toArray());
            long actualVersion = oldRows.isEmpty() ? 0 : oldRows.get(0).version();
            if (actualVersion != expectedVersion) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Budget version is stale");
            }
            Instant now = Instant.now();
            UUID budgetId;
            long nextVersion = actualVersion + 1;
            if (oldRows.isEmpty()) {
                budgetId = jdbc.queryForObject("""
                        INSERT INTO tenant_budgets (tenant_id, owner_user_id, budget_key, period, amount, version, updated_by, updated_at)
                        VALUES (?, ?, ?, ?, ?, 1, ?, ?) RETURNING id
                        """, UUID.class, tenantId, ownerId, key, period, new java.math.BigDecimal(amount), subject, java.sql.Timestamp.from(now));
            } else {
                budgetId = oldRows.get(0).id();
                jdbc.update("UPDATE tenant_budgets SET amount = ?, version = ?, active = true, updated_by = ?, updated_at = ? WHERE tenant_id = ? AND id = ?",
                        new java.math.BigDecimal(amount), nextVersion, subject, java.sql.Timestamp.from(now), tenantId, budgetId);
            }
            Map<String, Object> before = oldRows.isEmpty() ? null : Map.of(
                    "budgetKey", key, "period", period, "scope", request.scope(), "amount", oldRows.get(0).amount(),
                    "active", oldRows.get(0).active(), "version", actualVersion);
            Map<String, Object> after = Map.of("budgetKey", key, "period", period, "scope", request.scope(),
                    "amount", amount, "active", true, "version", nextVersion);
            String afterJson = serialize(after);
            String traceId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id, before_state, after_state, trace_id)
                    VALUES (?, ?, 'budget.updated', 'budget', ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                    """, tenantId, subject, budgetId, before == null ? null : serialize(before), afterJson, traceId);
            UUID eventId = UUID.randomUUID();
            Map<String, Object> event = Map.ofEntries(
                    Map.entry("event_id", eventId), Map.entry("event_type", "budget.updated"),
                    Map.entry("schema_version", 1), Map.entry("tenant_id", tenantId),
                    Map.entry("aggregate_type", "budget"), Map.entry("aggregate_id", budgetId),
                    Map.entry("aggregate_version", nextVersion), Map.entry("occurred_at", now),
                    Map.entry("recorded_at", now), Map.entry("producer", "core"),
                    Map.entry("correlation_id", traceId), Map.entry("payload", after));
            jdbc.update("""
                    INSERT INTO outbox_events (event_id, tenant_id, aggregate_type, aggregate_id,
                      aggregate_version, event_type, payload) VALUES (?, ?, 'budget', ?, ?, 'budget.updated', CAST(? AS jsonb))
                    """, eventId, tenantId, budgetId, nextVersion, serialize(event));
            jdbc.update("UPDATE idempotency_records SET response_status = 200, response_body = CAST(? AS jsonb) "
                            + "WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                    serialize(get(tenantId, subject)), tenantId, subject, route, idempotencyKey);
            return get(tenantId, subject);
        });
    }

    public BudgetProposalResponse propose(UUID tenantId, String subject, String idempotencyKey,
                                          BudgetProposalRequest request) {
        if (tenantId == null || subject == null || subject.isBlank() || idempotencyKey == null
                || idempotencyKey.length() < 16 || idempotencyKey.length() > 128 || request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid budget proposal request");
        }
        try {
            var income = new BigDecimal(request.monthlyIncome());
            if (income.signum() <= 0 || income.scale() > 2 || income.precision() > 20) throw new NumberFormatException();
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "monthlyIncome must be a positive amount", ex);
        }
        String route = "/budget-proposals";
        String requestHash = sha256(serialize(request));
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = resolveUser(subject);
            String role = membershipRole(tenantId, userId);
            if (role == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            if (!List.of("owner", "admin").contains(role)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner or admin can propose family budgets");
            }
            List<UUID> inserted = jdbc.query(
                    "INSERT INTO idempotency_records (tenant_id, actor_subject, route, idempotency_key, request_hash) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                    (rs, row) -> rs.getObject(1, UUID.class), tenantId, subject, route, idempotencyKey, requestHash);
            if (inserted.isEmpty()) return replayProposal(tenantId, subject, route, idempotencyKey, requestHash);
            jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", UUID.class, tenantId);
            Overview baseline = get(tenantId, subject);
            BudgetProposalPolicy.Proposal draft;
            try {
                draft = BudgetProposalPolicy.propose(request.monthlyIncome(), baseline.familyLimits());
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
            }
            UUID id = UUID.randomUUID();
            Instant createdAt = Instant.now();
            BudgetProposalResponse response = new BudgetProposalResponse(id, new BigDecimal(request.monthlyIncome())
                    .setScale(2).toPlainString(), draft.totalLimit(), draft.limits(), baseline.familyVersions(),
                    baseline.familyTotalVersion(), "pending", createdAt, "income", 0, null, null);
            jdbc.update("""
                    INSERT INTO budget_proposals (id, tenant_id, created_by, monthly_income, total_limit,
                      proposed_limits, base_versions, base_total_version, created_at)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                    """, id, tenantId, subject, new BigDecimal(response.monthlyIncome()),
                    new BigDecimal(response.totalLimit()), serialize(response.limits()),
                    serialize(response.baseVersions()), response.baseTotalVersion(), java.sql.Timestamp.from(createdAt));
            jdbc.update("""
                    INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id,
                      before_state, after_state, trace_id)
                    VALUES (?, ?, 'budget.proposal_created', 'budget_proposal', ?, NULL, CAST(? AS jsonb), ?)
                    """, tenantId, subject, id, serialize(response), UUID.randomUUID().toString());
            jdbc.update("UPDATE idempotency_records SET response_status = 201, response_body = CAST(? AS jsonb) "
                            + "WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                    serialize(response), tenantId, subject, route, idempotencyKey);
            return response;
        });
    }

    public BudgetProposalResponse proposeFromHistory(UUID tenantId, String subject, String idempotencyKey) {
        if (tenantId == null || subject == null || subject.isBlank() || idempotencyKey == null
                || idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid history budget proposal request");
        }
        HistoryPreparation preparation = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = resolveUser(subject);
            String role = membershipRole(tenantId, userId);
            if (role == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            if (!List.of("owner", "admin").contains(role)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner or admin can propose family budgets");
            }
            String route = "/budget-proposals/history";
            String requestHash = sha256(tenantId + "|history-based-budget-proposal");
            List<String> prior = jdbc.query("SELECT request_hash FROM idempotency_records WHERE tenant_id = ? "
                            + "AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                    (rs, row) -> rs.getString(1), tenantId, subject, route, idempotencyKey);
            if (!prior.isEmpty()) {
                return new HistoryPreparation(route, requestHash,
                        replayProposal(tenantId, subject, route, idempotencyKey, requestHash), null, null, null, 0);
            }

            var profiles = jdbc.query("SELECT planned_income, timezone FROM member_profiles "
                            + "WHERE tenant_id = ? AND user_id = ?",
                    (rs, row) -> new ProfileBudgetInputs(rs.getBigDecimal("planned_income"), rs.getString("timezone")),
                    tenantId, userId);
            if (profiles.isEmpty() || profiles.get(0).income() == null
                    || profiles.get(0).income().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Set planned monthly income before requesting a history proposal");
            }
            BigDecimal income = profiles.get(0).income();
            ZoneId zone = ZoneId.of(profiles.get(0).timezone());
            LocalDate today = LocalDate.now(zone);
            LocalDate firstExpense = jdbc.queryForObject("""
                    SELECT min((occurred_at AT TIME ZONE ?)::date)
                    FROM transactions WHERE tenant_id = ? AND owner_user_id = ?
                      AND status = 'posted' AND type = 'expense'
                    """, LocalDate.class, zone.getId(), tenantId, userId);
            long availableDays = firstExpense == null ? 0 : ChronoUnit.DAYS.between(firstExpense, today);
            if (availableDays < 30) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "At least 30 days of posted expense history are required");
            }
            int historyDays = (int) Math.min(availableDays, 3660);
            Overview baseline = get(tenantId, subject);
            List<String> categories = DEFAULT_LIMITS.keySet().stream()
                    .filter(category -> !"долги".equals(category)).sorted().toList();
            YearMonth currentMonth = YearMonth.now(zone);
            Instant from = currentMonth.minusMonths(3).atDay(1).atStartOfDay(zone).toInstant();
            Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();
            Map<String, Map<String, String>> expenses = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT to_char(occurred_at AT TIME ZONE ?, 'YYYY-MM') AS month, category_code,
                           sum(amount)::numeric(20,2)::text AS amount
                    FROM transactions WHERE tenant_id = ? AND owner_user_id = ? AND status = 'posted'
                      AND type = 'expense' AND occurred_at >= ? AND occurred_at < ?
                    GROUP BY month, category_code ORDER BY month, category_code
                    """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                String category = rs.getString("category_code");
                if (categories.contains(category)) {
                    String month = rs.getString("month");
                    expenses.computeIfAbsent(month, ignored -> new LinkedHashMap<>())
                            .put(category, rs.getString("amount"));
                }
            }, zone.getId(), tenantId, userId,
                    java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
            Map<String, String> incomes = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT to_char(occurred_at AT TIME ZONE ?, 'YYYY-MM') AS month,
                           sum(amount)::numeric(20,2)::text AS amount
                    FROM transactions WHERE tenant_id = ? AND owner_user_id = ? AND status = 'posted'
                      AND type = 'income' AND occurred_at >= ? AND occurred_at < ?
                    GROUP BY month ORDER BY month
                    """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        incomes.put(rs.getString("month"), rs.getString("amount"));
                    },
                    zone.getId(), tenantId, userId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
            BudgetProposalAdvisor.Context context = new BudgetProposalAdvisor.Context(
                    income.setScale(2).toPlainString(), historyDays, categories, expenses, incomes, baseline.familyLimits());
            return new HistoryPreparation(route, requestHash, null, context, baseline, income, historyDays);
        });
        if (preparation.replay() != null) return preparation.replay();

        BudgetProposalAdvisor.Advice advice = advisor.advise(preparation.context());
        BudgetProposalPolicy.Proposal draft;
        try {
            draft = BudgetProposalPolicy.proposeFromHistoryShares(preparation.income().toPlainString(),
                    preparation.baseline().familyLimits(), advice.shares());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Budget AI returned an invalid proposal", ex);
        }
        if (advice.provider() == null || advice.provider().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Budget AI provider metadata is missing");
        }

        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = resolveUser(subject);
            String role = membershipRole(tenantId, userId);
            if (role == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            if (!List.of("owner", "admin").contains(role)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner or admin can propose family budgets");
            }
            List<UUID> inserted = jdbc.query(
                    "INSERT INTO idempotency_records (tenant_id, actor_subject, route, idempotency_key, request_hash) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                    (rs, row) -> rs.getObject(1, UUID.class), tenantId, subject, preparation.route(), idempotencyKey,
                    preparation.requestHash());
            if (inserted.isEmpty()) {
                return replayProposal(tenantId, subject, preparation.route(), idempotencyKey, preparation.requestHash());
            }
            jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", UUID.class, tenantId);
            UUID id = UUID.randomUUID();
            Instant createdAt = Instant.now();
            BudgetProposalResponse response = new BudgetProposalResponse(id,
                    preparation.income().setScale(2).toPlainString(), draft.totalLimit(), draft.limits(),
                    preparation.baseline().familyVersions(), preparation.baseline().familyTotalVersion(),
                    "pending", createdAt, "history_ai", preparation.historyDays(), advice.modelVersion(), advice.promptVersion());
            jdbc.update("""
                    INSERT INTO budget_proposals (id, tenant_id, created_by, monthly_income, total_limit,
                      proposed_limits, base_versions, base_total_version, created_at,
                      proposal_source, history_days, provider, model_version, prompt_version)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, 'history_ai', ?, ?, ?, ?)
                    """, id, tenantId, subject, new BigDecimal(response.monthlyIncome()),
                    new BigDecimal(response.totalLimit()), serialize(response.limits()), serialize(response.baseVersions()),
                    response.baseTotalVersion(), java.sql.Timestamp.from(createdAt), response.historyDays(),
                    advice.provider(), advice.modelVersion(), advice.promptVersion());
            jdbc.update("""
                    INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id,
                      before_state, after_state, trace_id)
                    VALUES (?, ?, 'budget.proposal_created', 'budget_proposal', ?, NULL, CAST(? AS jsonb), ?)
                    """, tenantId, subject, id, serialize(response), UUID.randomUUID().toString());
            jdbc.update("UPDATE idempotency_records SET response_status = 201, response_body = CAST(? AS jsonb) "
                            + "WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                    serialize(response), tenantId, subject, preparation.route(), idempotencyKey);
            return response;
        });
    }

    public Overview applyProposal(UUID tenantId, String subject, UUID proposalId, String idempotencyKey) {
        if (tenantId == null || subject == null || subject.isBlank() || proposalId == null || idempotencyKey == null
                || idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid budget proposal apply request");
        }
        String route = "/budget-proposals/" + proposalId + "/apply";
        String requestHash = sha256(tenantId + "|" + proposalId);
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = resolveUser(subject);
            String role = membershipRole(tenantId, userId);
            if (role == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            if (!List.of("owner", "admin").contains(role)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner or admin can apply family budgets");
            }
            List<UUID> inserted = jdbc.query(
                    "INSERT INTO idempotency_records (tenant_id, actor_subject, route, idempotency_key, request_hash) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                    (rs, row) -> rs.getObject(1, UUID.class), tenantId, subject, route, idempotencyKey, requestHash);
            if (inserted.isEmpty()) return replay(tenantId, subject, route, idempotencyKey, requestHash);
            jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", UUID.class, tenantId);
            var rows = jdbc.query("""
                    SELECT proposed_limits::text, base_versions::text, total_limit::numeric(20,2)::text AS total_limit,
                           base_total_version, status, expires_at
                    FROM budget_proposals WHERE tenant_id = ? AND id = ? FOR UPDATE
                    """, (rs, row) -> new ProposalRow(rs.getString("proposed_limits"), rs.getString("base_versions"),
                    rs.getString("total_limit"), rs.getLong("base_total_version"), rs.getString("status"),
                    rs.getTimestamp("expires_at").toInstant()),
                    tenantId, proposalId);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget proposal not found");
            ProposalRow proposal = rows.get(0);
            if (!"pending".equals(proposal.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Budget proposal is no longer pending");
            }
            if (proposal.expiresAt().isBefore(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.GONE, "Budget proposal expired");
            }
            Map<String, String> limits = readStringMap(proposal.limitsJson());
            Map<String, Long> baseVersions = readLongMap(proposal.versionsJson());
            Overview current = get(tenantId, subject);
            if (current.familyTotalVersion() != proposal.totalVersion()
                    || baseVersions.entrySet().stream().anyMatch(entry ->
                    !java.util.Objects.equals(entry.getValue(), current.familyVersions().get(entry.getKey())))) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Budget changed since proposal was created");
            }
            for (var limit : limits.entrySet()) {
                update(tenantId, subject, limit.getKey(), proposalUpdateKey(idempotencyKey, limit.getKey()),
                        baseVersions.getOrDefault(limit.getKey(), 0L), new UpdateRequest("family", limit.getValue(), "monthly"));
            }
            update(tenantId, subject, "__total__", proposalUpdateKey(idempotencyKey, "__total__"),
                    proposal.totalVersion(), new UpdateRequest("family", proposal.totalLimit(), "monthly"));
            jdbc.update("UPDATE budget_proposals SET status = 'applied', applied_at = now() WHERE tenant_id = ? AND id = ?",
                    tenantId, proposalId);
            Overview response = get(tenantId, subject);
            jdbc.update("""
                    INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id,
                      before_state, after_state, trace_id)
                    VALUES (?, ?, 'budget.proposal_applied', 'budget_proposal', ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                    """, tenantId, subject, proposalId, serialize(proposal), serialize(response), UUID.randomUUID().toString());
            jdbc.update("UPDATE idempotency_records SET response_status = 200, response_body = CAST(? AS jsonb) "
                            + "WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                    serialize(response), tenantId, subject, route, idempotencyKey);
            return response;
        });
    }

    public Overview resetPersonal(UUID tenantId, String subject, String idempotencyKey) {
        if (tenantId == null || subject == null || subject.isBlank() || idempotencyKey == null
                || idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid budget reset request");
        }
        String route = "/budgets/personal-overrides";
        String requestHash = sha256(tenantId + "|" + route);
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = resolveUser(subject);
            String role = membershipRole(tenantId, userId);
            if (role == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            if ("viewer".equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient budget permissions");
            List<UUID> inserted = jdbc.query(
                    "INSERT INTO idempotency_records (tenant_id, actor_subject, route, idempotency_key, request_hash) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                    (rs, row) -> rs.getObject(1, UUID.class), tenantId, subject, route, idempotencyKey, requestHash);
            if (inserted.isEmpty()) return replay(tenantId, subject, route, idempotencyKey, requestHash);
            jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", UUID.class, tenantId);
            List<StoredBudget> stored = jdbc.query("""
                    SELECT id, budget_key, period, amount::numeric(20,2)::text AS amount, version
                    FROM tenant_budgets WHERE tenant_id = ? AND owner_user_id = ? AND active FOR UPDATE
                    """, (rs, row) -> new StoredBudget(rs.getObject("id", UUID.class),
                    rs.getString("budget_key"), rs.getString("period"), rs.getString("amount"), rs.getLong("version"), true), tenantId, userId);
            Instant now = Instant.now();
            for (StoredBudget budget : stored) {
                long nextVersion = budget.version() + 1;
                Map<String, Object> before = Map.of("budgetKey", budget.key(), "period", budget.period(), "scope", "personal",
                        "amount", budget.amount(), "version", budget.version());
                Map<String, Object> after = Map.of("budgetKey", budget.key(), "period", budget.period(), "scope", "personal",
                        "amount", budget.amount(), "active", false, "version", nextVersion);
                String traceId = UUID.randomUUID().toString();
                jdbc.update("""
                        INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id,
                                               before_state, after_state, trace_id)
                        VALUES (?, ?, 'budget.personal_override_removed', 'budget', ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                        """, tenantId, subject, budget.id(), serialize(before), serialize(after), traceId);
                UUID eventId = UUID.randomUUID();
                Map<String, Object> event = Map.ofEntries(
                        Map.entry("event_id", eventId), Map.entry("event_type", "budget.updated"),
                        Map.entry("schema_version", 1), Map.entry("tenant_id", tenantId),
                        Map.entry("aggregate_type", "budget"), Map.entry("aggregate_id", budget.id()),
                        Map.entry("aggregate_version", nextVersion), Map.entry("occurred_at", now),
                        Map.entry("recorded_at", now), Map.entry("producer", "core"),
                        Map.entry("correlation_id", traceId), Map.entry("payload", after));
                jdbc.update("""
                        INSERT INTO outbox_events (event_id, tenant_id, aggregate_type, aggregate_id,
                          aggregate_version, event_type, payload) VALUES (?, ?, 'budget', ?, ?, 'budget.updated', CAST(? AS jsonb))
                        """, eventId, tenantId, budget.id(), nextVersion, serialize(event));
                jdbc.update("UPDATE tenant_budgets SET active = false, version = ?, updated_by = ?, updated_at = ? WHERE tenant_id = ? AND id = ?",
                        nextVersion, subject, java.sql.Timestamp.from(now), tenantId, budget.id());
            }
            Overview response = get(tenantId, subject);
            jdbc.update("UPDATE idempotency_records SET response_status = 200, response_body = CAST(? AS jsonb) "
                            + "WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                    serialize(response), tenantId, subject, route, idempotencyKey);
            return response;
        });
    }

    private Overview replay(UUID tenantId, String subject, String route, String key, String requestHash) {
        var existing = jdbc.queryForMap("SELECT request_hash, response_body::text FROM idempotency_records "
                        + "WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                tenantId, subject, route, key);
        if (!requestHash.equals(existing.get("request_hash"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was used with another request");
        }
        try {
            return json.readValue((String) existing.get("response_body"), Overview.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Stored budget response is invalid", ex);
        }
    }

    private BudgetProposalResponse replayProposal(UUID tenantId, String subject, String route, String key, String requestHash) {
        var existing = jdbc.queryForMap("SELECT request_hash, response_body::text FROM idempotency_records "
                        + "WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                tenantId, subject, route, key);
        if (!requestHash.equals(existing.get("request_hash"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was used with another proposal");
        }
        try {
            return json.readValue((String) existing.get("response_body"), BudgetProposalResponse.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Stored budget proposal is invalid", ex);
        }
    }

    private Map<String, String> readStringMap(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (JacksonException ex) { throw new IllegalStateException("Stored proposal limits are invalid", ex); }
    }

    private Map<String, Long> readLongMap(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (JacksonException ex) { throw new IllegalStateException("Stored proposal versions are invalid", ex); }
    }

    private static String proposalUpdateKey(String applyKey, String budgetKey) {
        return "proposal-" + sha256(applyKey + "|" + budgetKey).substring(0, 48);
    }

    private static String validate(UUID tenantId, String subject, String key, String idempotencyKey,
                                   long expectedVersion, UpdateRequest request) {
        if (tenantId == null || subject == null || subject.isBlank() || idempotencyKey == null
                || idempotencyKey.length() < 16 || idempotencyKey.length() > 128 || expectedVersion < 0
                || request == null || request.scope() == null || !List.of("family", "personal").contains(request.scope())
                || key == null || !("__total__".equals(key) || DEFAULT_LIMITS.containsKey(key))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid budget update");
        }
        String period = normalizePeriod(request.period());
        if ("rolling7".equals(period) && !"еда".equals(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rolling seven-day budget is only supported for food");
        }
        try {
            var value = new java.math.BigDecimal(request.amount());
            if (value.signum() < 0 || value.compareTo(new java.math.BigDecimal("100000000")) >= 0
                    || value.scale() > 2) throw new NumberFormatException("out of range");
            return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (NumberFormatException | ArithmeticException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Budget amount must be a non-negative decimal", ex);
        }
    }

    private static String normalizePeriod(String period) {
        String value = period == null ? "monthly" : period;
        if (!List.of("monthly", "rolling7").contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Budget period must be monthly or rolling7");
        }
        return value;
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException ex) { throw new IllegalStateException("Cannot serialize budget", ex); }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private String membershipRole(UUID tenantId, UUID userId) {
        if (userId == null) return null;
        List<String> roles = jdbc.query("SELECT role FROM memberships WHERE tenant_id = ? AND user_id = ? AND status = 'active'",
                (rs, row) -> rs.getString(1), tenantId, userId);
        return roles.isEmpty() ? null : roles.get(0);
    }

    private record StoredBudget(UUID id, String key, String period, String amount, long version, boolean active) {
        private StoredBudget(UUID id, String key, String amount, long version, boolean active) {
            this(id, key, "monthly", amount, version, active);
        }
        private StoredBudget(UUID id, String key, String amount, long version) {
            this(id, key, "monthly", amount, version, true);
        }
        private StoredBudget(UUID id, String amount, long version) {
            this(id, null, "monthly", amount, version, true);
        }
    }

    private record ProposalRow(String limitsJson, String versionsJson, String totalLimit, long totalVersion,
                               String status, Instant expiresAt) {}
    private record ProfileBudgetInputs(BigDecimal income, String timezone) {}
    private record HistoryPreparation(String route, String requestHash, BudgetProposalResponse replay,
                                      BudgetProposalAdvisor.Context context, Overview baseline,
                                      BigDecimal income, int historyDays) {}

    private UUID resolveUser(String subject) {
        List<UUID> users = jdbc.query("SELECT user_id FROM external_identities WHERE provider = 'keycloak' AND subject = ?",
                (rs, row) -> rs.getObject(1, UUID.class), subject);
        return users.isEmpty() ? null : users.get(0);
    }

    private boolean isMember(UUID tenantId, UUID userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM memberships WHERE tenant_id = ? AND user_id = ? AND status = 'active'",
                Integer.class, tenantId, userId);
        return count != null && count > 0;
    }
}
