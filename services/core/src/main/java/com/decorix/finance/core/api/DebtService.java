package com.decorix.finance.core.api;

import com.decorix.finance.core.api.DebtApi.CreateRequest;
import com.decorix.finance.core.api.DebtApi.BalanceAdjustmentRequest;
import com.decorix.finance.core.api.DebtApi.DebtResponse;
import com.decorix.finance.core.api.DebtApi.ForecastResponse;
import com.decorix.finance.core.api.DebtApi.Page;
import com.decorix.finance.core.api.DebtApi.PaymentRequest;
import com.decorix.finance.core.api.DebtApi.PaymentResponse;
import com.decorix.finance.core.domain.MoneyAmount;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DebtService {
    private static final RowMapper<DebtResponse> MAPPER = (rs, row) -> new DebtResponse(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("name"),
            rs.getString("opening_balance"), rs.getString("current_balance"), rs.getString("interest_rate"),
            rs.getString("minimum_payment"), rs.getString("status"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant());
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public DebtService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
    }

    public DebtResponse create(UUID tenantId, String subject, String key, CreateRequest request) {
        BigDecimal opening = positive(request == null ? null : request.openingBalance(), "openingBalance");
        BigDecimal rate = optionalNonNegative(request.interestRate(), "interestRate");
        BigDecimal minimum = optionalNonNegative(request.minimumPayment(), "minimumPayment");
        if (request.name() == null || request.name().isBlank() || request.name().length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debt name is required");
        }
        return transaction.execute(status -> {
            UUID userId = setTenantAndGetMember(tenantId, subject, true);
            String route = "/debts";
            String hash = sha256(serialize(request));
            if (!reserve(tenantId, subject, route, key, hash)) return replayDebt(tenantId, subject, route, key, hash);
            Instant now = Instant.now();
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO debts (id, tenant_id, name, opening_balance, current_balance, interest_rate,
                                       minimum_payment, status, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'open', ?, ?, ?)
                    """, id, tenantId, request.name().trim(), opening, opening, rate, minimum, subject,
                    Timestamp.from(now), Timestamp.from(now));
            DebtResponse response = getInTransaction(tenantId, id);
            writeDebtEvent(tenantId, subject, response, "debt.created", null);
            saveResponse(tenantId, subject, route, key, response);
            return response;
        });
    }

    public Page list(UUID tenantId, String subject) {
        return transaction.execute(status -> {
            setTenantAndGetMember(tenantId, subject, false);
            return new Page(jdbc.query("""
                    SELECT id, tenant_id, name, opening_balance::numeric(20,2)::text AS opening_balance,
                           current_balance::numeric(20,2)::text AS current_balance,
                           interest_rate::numeric(7,4)::text AS interest_rate,
                           minimum_payment::numeric(20,2)::text AS minimum_payment, status, version, created_at
                    FROM debts WHERE tenant_id = ? ORDER BY created_at, id
                    """, MAPPER, tenantId));
        });
    }

    public DebtResponse get(UUID tenantId, String subject, UUID id) {
        return transaction.execute(status -> {
            setTenantAndGetMember(tenantId, subject, false);
            return getInTransaction(tenantId, id);
        });
    }

    public DebtResponse adjustBalance(UUID tenantId, String subject, UUID id, String key,
                                      long expectedVersion, BalanceAdjustmentRequest request) {
        BigDecimal parsedBalance;
        try {
            parsedBalance = new BigDecimal(request == null ? null : request.currentBalance());
            if (parsedBalance.signum() < 0 || parsedBalance.scale() > 2 || parsedBalance.precision() > 20) throw new NumberFormatException();
            parsedBalance = parsedBalance.setScale(2);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "currentBalance must be a non-negative amount with at most two decimal places", ex);
        }
        final BigDecimal balance = parsedBalance;
        if (expectedVersion < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid debt version");
        String route = "/debts/" + id + "/balance";
        String hash = sha256(id + "|" + expectedVersion + "|" + serialize(request));
        return transaction.execute(status -> {
            setTenantAndGetMember(tenantId, subject, true);
            if (!reserve(tenantId, subject, route, key, hash)) return replayDebt(tenantId, subject, route, key, hash);
            List<DebtResponse> rows = queryDebt(tenantId, id, true);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Debt not found");
            DebtResponse before = rows.get(0);
            if (before.version() != expectedVersion) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Debt version is stale");
            }
            String nextStatus = balance.signum() == 0 ? "closed" : "open";
            jdbc.update("UPDATE debts SET current_balance = ?, status = ?, version = version + 1, updated_at = ? WHERE tenant_id = ? AND id = ?",
                    balance, nextStatus, Timestamp.from(Instant.now()), tenantId, id);
            DebtResponse after = getInTransaction(tenantId, id);
            writeDebtEvent(tenantId, subject, after, "debt.balance_adjusted", before);
            saveResponse(tenantId, subject, route, key, after);
            return after;
        });
    }

    public PaymentResponse pay(UUID tenantId, String subject, UUID id, String key,
                               long expectedVersion, PaymentRequest request) {
        BigDecimal amount = positive(request == null ? null : request.amount(), "amount");
        if (request.occurredAt() == null || expectedVersion < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment timestamp or version");
        }
        String route = "/debts/" + id + "/payments";
        String hash = sha256(id + "|" + expectedVersion + "|" + serialize(request));
        return transaction.execute(status -> {
            UUID userId = setTenantAndGetMember(tenantId, subject, false);
            if (!reserve(tenantId, subject, route, key, hash)) return replayPayment(tenantId, subject, route, key, hash);
            List<DebtResponse> rows = queryDebt(tenantId, id, true);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Debt not found");
            DebtResponse before = rows.get(0);
            if (before.version() != expectedVersion || "closed".equals(before.status())) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Debt version is stale or debt is closed");
            }
            BigDecimal reduction = amount.min(new BigDecimal(before.currentBalance()));
            BigDecimal nextBalance = new BigDecimal(before.currentBalance()).subtract(reduction);
            long nextVersion = before.version() + 1;
            String nextStatus = nextBalance.signum() == 0 ? "closed" : "open";
            Instant now = Instant.now();
            UUID transactionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO transactions (id, tenant_id, owner_subject, owner_user_id, type, amount, currency,
                      category_code, description, source, occurred_at, debt_id, debt_balance_effect)
                    VALUES (?, ?, ?, ?, 'debt_payment', ?, 'RUB', 'долги', ?, 'web', ?, ?, ?)
                    """, transactionId, tenantId, subject, userId, amount,
                    "Платёж по долгу: " + before.name(), Timestamp.from(request.occurredAt()), id, reduction);
            jdbc.update("UPDATE debts SET current_balance = ?, status = ?, version = ?, updated_at = ? WHERE tenant_id = ? AND id = ?",
                    nextBalance, nextStatus, nextVersion, Timestamp.from(now), tenantId, id);
            DebtResponse after = getInTransaction(tenantId, id);
            Map<String, Object> txAfter = Map.of("id", transactionId, "type", "debt_payment", "amount", amount.toPlainString(),
                    "currency", "RUB", "categoryCode", "долги", "description", "Платёж по долгу: " + before.name(),
                    "source", "web",
                    "occurredAt", request.occurredAt(), "status", "posted", "version", 1);
            audit(tenantId, subject, transactionId, "transaction.created", null, txAfter);
            writeTransactionEvent(tenantId, transactionId, userId, amount, request.occurredAt(), before, reduction, now);
            writeDebtEvent(tenantId, subject, after, "debt.payment_recorded", before);
            PaymentResponse response = new PaymentResponse(transactionId, reduction.setScale(2).toPlainString(), after);
            saveResponse(tenantId, subject, route, key, response);
            return response;
        });
    }

    public ForecastResponse forecast(UUID tenantId, String subject, UUID id) {
        DebtResponse debt = get(tenantId, subject, id);
        Integer months = payoffMonths(new BigDecimal(debt.currentBalance()),
                debt.minimumPayment() == null ? BigDecimal.ZERO : new BigDecimal(debt.minimumPayment()),
                debt.interestRate() == null ? null : new BigDecimal(debt.interestRate()));
        return new ForecastResponse(id, months, "Fixed minimum payment with monthly compound estimate; no interest is posted to ledger.");
    }

    /** Reverses only the amount this exact posted transaction removed from the linked debt. */
    public void reversePayment(UUID tenantId, String subject, UUID transactionId) {
        List<DebtEffect> effects = jdbc.query("""
                SELECT debt_id, debt_balance_effect::numeric(20,2)::text AS effect
                FROM transactions WHERE tenant_id = ? AND id = ? AND type = 'debt_payment' AND debt_id IS NOT NULL
                """, (rs, row) -> new DebtEffect(rs.getObject("debt_id", UUID.class), new BigDecimal(rs.getString("effect"))),
                tenantId, transactionId);
        if (effects.isEmpty() || effects.get(0).amount().signum() == 0) return;
        DebtEffect effect = effects.get(0);
        List<DebtResponse> rows = queryDebt(tenantId, effect.debtId(), true);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Linked debt is unavailable for reversal");
        DebtResponse before = rows.get(0);
        BigDecimal nextBalance = new BigDecimal(before.currentBalance()).add(effect.amount());
        long nextVersion = before.version() + 1;
        Instant now = Instant.now();
        jdbc.update("UPDATE debts SET current_balance = ?, status = 'open', version = ?, updated_at = ? WHERE tenant_id = ? AND id = ?",
                nextBalance, nextVersion, Timestamp.from(now), tenantId, effect.debtId());
        DebtResponse after = getInTransaction(tenantId, effect.debtId());
        writeDebtEvent(tenantId, subject, after, "debt.payment_reversed", before);
    }

    /** Applies the debt-side effect for an edited transaction inside the caller's database transaction. */
    public BigDecimal applyEditedPayment(UUID tenantId, String subject, UUID debtId, BigDecimal amount) {
        List<DebtResponse> rows = queryDebt(tenantId, debtId, true);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Debt not found");
        DebtResponse before = rows.get(0);
        if ("closed".equals(before.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A closed debt cannot receive a payment");
        }
        BigDecimal reduction = amount.min(new BigDecimal(before.currentBalance()));
        BigDecimal nextBalance = new BigDecimal(before.currentBalance()).subtract(reduction);
        jdbc.update("UPDATE debts SET current_balance = ?, status = ?, version = version + 1, updated_at = ? "
                        + "WHERE tenant_id = ? AND id = ?",
                nextBalance, nextBalance.signum() == 0 ? "closed" : "open", Timestamp.from(Instant.now()), tenantId, debtId);
        DebtResponse after = getInTransaction(tenantId, debtId);
        writeDebtEvent(tenantId, subject, after, "debt.payment_adjusted", before);
        return reduction;
    }

    static Integer payoffMonths(BigDecimal balance, BigDecimal minimumPayment, BigDecimal interestRate) {
        if (balance.signum() <= 0) return 0;
        if (minimumPayment.signum() <= 0) return null;
        BigDecimal monthlyRate = interestRate == null ? BigDecimal.ZERO
                : interestRate.divide(BigDecimal.valueOf(1200), 12, RoundingMode.HALF_EVEN);
        BigDecimal remaining = balance;
        for (int month = 1; month <= 600; month++) {
            BigDecimal interest = remaining.multiply(monthlyRate).setScale(2, RoundingMode.HALF_EVEN);
            BigDecimal principal = minimumPayment.subtract(interest);
            if (principal.signum() <= 0) return null;
            remaining = remaining.subtract(principal.min(remaining));
            if (remaining.signum() <= 0) return month;
        }
        return null;
    }

    private UUID setTenantAndGetMember(UUID tenantId, String subject, boolean requireOwner) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        List<UUID> users = jdbc.query("SELECT user_id FROM external_identities WHERE provider = 'keycloak' AND subject = ?",
                (rs, row) -> rs.getObject(1, UUID.class), subject);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        UUID userId = users.get(0);
        List<String> roles = jdbc.query("SELECT role FROM memberships WHERE tenant_id = ? AND user_id = ? AND status = 'active'",
                (rs, row) -> rs.getString(1), tenantId, userId);
        if (roles.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        String role = roles.get(0);
        if ("viewer".equals(role) || requireOwner && !List.of("owner", "admin").contains(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient debt permissions");
        }
        return userId;
    }

    private DebtResponse getInTransaction(UUID tenantId, UUID id) {
        List<DebtResponse> rows = queryDebt(tenantId, id, false);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Debt not found");
        return rows.get(0);
    }

    private List<DebtResponse> queryDebt(UUID tenantId, UUID id, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                SELECT id, tenant_id, name, opening_balance::numeric(20,2)::text AS opening_balance,
                       current_balance::numeric(20,2)::text AS current_balance,
                       interest_rate::numeric(7,4)::text AS interest_rate,
                       minimum_payment::numeric(20,2)::text AS minimum_payment, status, version, created_at
                FROM debts WHERE tenant_id = ? AND id = ?
                """ + suffix, MAPPER, tenantId, id);
    }

    private boolean reserve(UUID tenantId, String subject, String route, String key, String hash) {
        if (key == null || key.length() < 16 || key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid idempotency key");
        }
        return !jdbc.query("INSERT INTO idempotency_records (tenant_id, actor_subject, route, idempotency_key, request_hash) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, subject, route, key, hash).isEmpty();
    }

    private DebtResponse replayDebt(UUID tenantId, String subject, String route, String key, String hash) {
        return replay(tenantId, subject, route, key, hash, DebtResponse.class);
    }

    private PaymentResponse replayPayment(UUID tenantId, String subject, String route, String key, String hash) {
        return replay(tenantId, subject, route, key, hash, PaymentResponse.class);
    }

    private <T> T replay(UUID tenantId, String subject, String route, String key, String hash, Class<T> type) {
        var existing = jdbc.queryForMap("SELECT request_hash, response_body::text FROM idempotency_records "
                        + "WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                tenantId, subject, route, key);
        if (!hash.equals(existing.get("request_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key payload mismatch");
        try { return json.readValue((String) existing.get("response_body"), type); }
        catch (JacksonException ex) { throw new IllegalStateException("Stored debt response is invalid", ex); }
    }

    private void saveResponse(UUID tenantId, String subject, String route, String key, Object response) {
        jdbc.update("UPDATE idempotency_records SET response_status = 200, response_body = CAST(? AS jsonb) "
                        + "WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                serialize(response), tenantId, subject, route, key);
    }

    private void audit(UUID tenantId, String subject, UUID id, String action, Object before, Object after) {
        jdbc.update("""
                INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id, before_state, after_state, trace_id)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                """, tenantId, subject, action, action.startsWith("transaction") ? "transaction" : "debt", id,
                before == null ? null : serialize(before), serialize(after), UUID.randomUUID().toString());
    }

    private void writeDebtEvent(UUID tenantId, String subject, DebtResponse after, String action, DebtResponse before) {
        Instant now = Instant.now();
        String trace = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of("name", after.name(), "openingBalance", after.openingBalance(),
                "currentBalance", after.currentBalance(), "interestRate", after.interestRate() == null ? "" : after.interestRate(),
                "minimumPayment", after.minimumPayment(), "status", after.status(), "version", after.version(), "action", action);
        audit(tenantId, subject, after.id(), action, before, payload);
        UUID eventId = UUID.randomUUID();
        Map<String, Object> event = Map.ofEntries(Map.entry("event_id", eventId), Map.entry("event_type", action),
                Map.entry("schema_version", 1), Map.entry("tenant_id", tenantId), Map.entry("aggregate_type", "debt"),
                Map.entry("aggregate_id", after.id()), Map.entry("aggregate_version", after.version()),
                Map.entry("occurred_at", now), Map.entry("recorded_at", now), Map.entry("producer", "core"),
                Map.entry("correlation_id", trace), Map.entry("payload", payload));
        jdbc.update("""
                INSERT INTO outbox_events (event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload)
                VALUES (?, ?, 'debt', ?, ?, ?, CAST(? AS jsonb))
                """, eventId, tenantId, after.id(), after.version(), action, serialize(event));
    }

    private void writeTransactionEvent(UUID tenantId, UUID transactionId, UUID userId, BigDecimal amount,
                                       Instant occurredAt, DebtResponse debt, BigDecimal reduction, Instant now) {
        UUID eventId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.ofEntries(Map.entry("owner_user_id", userId), Map.entry("type", "debt_payment"),
                Map.entry("amount", amount.toPlainString()), Map.entry("currency", "RUB"),
                Map.entry("category_code", "долги"), Map.entry("description", "Платёж по долгу: " + debt.name()),
                Map.entry("source", "web"), Map.entry("status", "posted"),
                Map.entry("financial_occurred_at", occurredAt), Map.entry("debt_id", debt.id()),
                Map.entry("debt_balance_effect", reduction.toPlainString()));
        Map<String, Object> event = Map.ofEntries(Map.entry("event_id", eventId), Map.entry("event_type", "transaction.created"),
                Map.entry("schema_version", 1), Map.entry("tenant_id", tenantId), Map.entry("aggregate_type", "transaction"),
                Map.entry("aggregate_id", transactionId), Map.entry("aggregate_version", 1), Map.entry("occurred_at", now),
                Map.entry("recorded_at", now), Map.entry("producer", "core"), Map.entry("correlation_id", trace),
                Map.entry("payload", payload));
        jdbc.update("""
                INSERT INTO outbox_events (event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload)
                VALUES (?, ?, 'transaction', ?, 1, 'transaction.created', CAST(? AS jsonb))
                """, eventId, tenantId, transactionId, serialize(event));
    }

    private static BigDecimal positive(String value, String field) {
        try {
            var amount = MoneyAmount.parse(value);
            return amount.value();
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a positive decimal", ex);
        }
    }

    private static BigDecimal optionalNonNegative(String value, String field) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO.setScale(2);
        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() < 0 || amount.scale() > 4) throw new NumberFormatException("out of range");
            return amount;
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a non-negative decimal", ex);
        }
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException ex) { throw new IllegalStateException("Cannot serialize debt", ex); }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record DebtEffect(UUID debtId, BigDecimal amount) {}
}
