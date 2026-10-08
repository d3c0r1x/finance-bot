package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TransactionApi.CreateRequest;
import com.decorix.finance.core.api.TransactionApi.DashboardSummary;
import com.decorix.finance.core.api.TransactionApi.Page;
import com.decorix.finance.core.api.TransactionApi.TransactionResponse;
import com.decorix.finance.core.api.TransactionApi.UpdateRequest;
import com.decorix.finance.core.domain.MoneyAmount;
import com.decorix.finance.core.domain.MonthlyPacePolicy;
import com.decorix.finance.core.domain.BudgetAlertPolicy;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
public class TransactionService {
    private static final List<String> TYPES = List.of("expense", "income", "refund", "debt_payment", "transfer");
    private static final RowMapper<TransactionResponse> TRANSACTION_MAPPER = (rs, row) -> new TransactionResponse(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("type"),
            rs.getString("amount"), rs.getString("currency"), rs.getString("category_code"),
            rs.getString("subcategory_code"), rs.getString("description"), rs.getString("source"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getObject("account_id", UUID.class), rs.getString("status"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), null, null, null);
    private static final RowMapper<TransactionResponse> TRANSACTION_LIST_MAPPER = (rs, row) -> new TransactionResponse(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("type"),
            rs.getString("amount"), rs.getString("currency"), rs.getString("category_code"),
            rs.getString("subcategory_code"), rs.getString("description"), rs.getString("source"),
            rs.getTimestamp("occurred_at").toInstant(), rs.getObject("account_id", UUID.class),
            rs.getString("status"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getString("member_name"), rs.getObject("debt_id", UUID.class),
            rs.getObject("owner_user_id", UUID.class));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final DebtService debts;
    private final BudgetService budgets;
    private final CashPlanningService cashPlanning;

    public TransactionService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json, DebtService debts,
                              BudgetService budgets, CashPlanningService cashPlanning) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
        this.debts = debts;
        this.budgets = budgets;
        this.cashPlanning = cashPlanning;
    }

    public TransactionResponse create(UUID tenantId, String subject, String key, CreateRequest request) {
        validate(tenantId, subject, key, request);
        String requestHash = sha256(serialize(request));
        return transaction.execute(status -> {
            setTenantContext(tenantId);
            UUID userId = resolveUser(subject);
            if (userId == null || !isMember(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            }
            requireWritePermission(tenantId, userId);
            UUID transactionOwnerId = request.ownerUserId() == null ? userId : request.ownerUserId();
            if (!transactionOwnerId.equals(userId) && !canManageFamilyTransactions(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owners and admins may assign transactions");
            }
            if (!isMember(tenantId, transactionOwnerId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
            }
            if (request.accountId() != null && jdbc.queryForObject(
                    "SELECT count(*) FROM accounts WHERE tenant_id = ? AND id = ?", Integer.class,
                    tenantId, request.accountId()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
            }

            List<UUID> inserted = jdbc.query(
                    "INSERT INTO idempotency_records (tenant_id, actor_subject, route, idempotency_key, request_hash) "
                            + "VALUES (?, ?, '/transactions', ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                    (rs, row) -> rs.getObject(1, UUID.class), tenantId, subject, key, requestHash);
            if (inserted.isEmpty()) {
                return replay(tenantId, subject, "/transactions", key, requestHash);
            }

            String transactionOwnerSubject = subjectForUser(transactionOwnerId);
            BudgetApi.Overview budgetBefore = null;
            if ("expense".equals(request.type())) {
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))",
                        (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> {
                            if (!rs.next()) throw new IllegalStateException("Budget alert lock was not acquired");
                            return Boolean.TRUE;
                        }, tenantId + ":" + transactionOwnerId);
                budgetBefore = budgets.get(tenantId, transactionOwnerSubject);
            }

            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO transactions
                      (id, tenant_id, owner_subject, owner_user_id, account_id, type, amount, currency, category_code,
                       subcategory_code, description, source, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, tenantId, transactionOwnerSubject, transactionOwnerId, request.accountId(), request.type(),
                    MoneyAmount.parse(request.amount()).value(),
                    request.currency(), request.categoryCode(),
                    request.subcategoryCode(), request.description(), sourceForCreate(request.source()),
                    Timestamp.from(request.occurredAt()));

            List<BudgetAlertPolicy.Alert> budgetAlerts = budgetBefore == null ? List.of()
                    : crossedBudgetAlerts(budgetBefore, budgets.get(tenantId, transactionOwnerSubject),
                            request.categoryCode());
            TransactionResponse response = new TransactionResponse(id, tenantId, request.type(),
                    MoneyAmount.parse(request.amount()).toString(), request.currency(), request.categoryCode(),
                    request.subcategoryCode(), request.description(), sourceForCreate(request.source()),
                    request.occurredAt(), request.accountId(),
                    "posted", 1, now, memberDisplayName(tenantId, transactionOwnerId), null, transactionOwnerId,
                    budgetAlerts);
            String responseJson = serialize(response);
            UUID eventId = UUID.randomUUID();
            String correlationId = UUID.randomUUID().toString();
            String payload = serialize(Map.ofEntries(
                    Map.entry("event_id", eventId),
                    Map.entry("event_type", "transaction.created"),
                    Map.entry("schema_version", 1),
                    Map.entry("tenant_id", tenantId),
                    Map.entry("aggregate_type", "transaction"),
                    Map.entry("aggregate_id", id),
                    Map.entry("aggregate_version", 1),
                    Map.entry("occurred_at", now),
                    Map.entry("recorded_at", now),
                    Map.entry("producer", "core"),
                    Map.entry("correlation_id", correlationId),
                    Map.entry("payload", Map.of(
                            "owner_user_id", transactionOwnerId,
                            "type", request.type(),
                            "amount", MoneyAmount.parse(request.amount()).toString(),
                            "currency", request.currency(),
                            "category_code", request.categoryCode(),
                            "description", request.description(),
                            "source", response.source(),
                            "status", "posted",
                            "financial_occurred_at", request.occurredAt()))));

            jdbc.update("""
                    INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id, after_state, trace_id)
                    VALUES (?, ?, 'transaction.created', 'transaction', ?, CAST(? AS jsonb), ?)
                    """, tenantId, subject, id, responseJson, UUID.randomUUID().toString());
            jdbc.update("""
                    INSERT INTO outbox_events
                      (event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload)
                    VALUES (?, ?, 'transaction', ?, 1, 'transaction.created', CAST(? AS jsonb))
                    """, eventId, tenantId, id, payload);
            jdbc.update("UPDATE idempotency_records SET response_status = 201, response_body = CAST(? AS jsonb) WHERE tenant_id = ? AND actor_subject = ? AND route = '/transactions' AND idempotency_key = ?",
                    responseJson, tenantId, subject, key);
            return response;
        });
    }

    public TransactionResponse get(UUID tenantId, String subject, UUID id) {
        return transaction.execute(status -> {
            setTenantContext(tenantId);
            UUID userId = resolveUser(subject);
            if (userId == null || !isMember(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
            }
            List<TransactionResponse> rows = jdbc.query("""
                    SELECT id, tenant_id, type, amount::text, currency, category_code, subcategory_code,
                           description, source, occurred_at, account_id, status, version, created_at
                    FROM transactions WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
                    """, TRANSACTION_MAPPER, tenantId, userId, id);
            if (rows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
            }
            return rows.get(0);
        });
    }

    public TransactionResponse update(UUID tenantId, String subject, UUID id, String key,
                                      long expectedVersion, UpdateRequest request) {
        validateUpdate(tenantId, subject, key, request);
        if (expectedVersion < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction version");
        }
        String route = "/transactions/" + id;
        String requestHash = sha256(id + "|" + expectedVersion + "|" + serialize(request));
        return transaction.execute(status -> {
            setTenantContext(tenantId);
            UUID userId = resolveUser(subject);
            if (userId == null || !isMember(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
            }
            requireWritePermission(tenantId, userId);
            List<UUID> inserted = jdbc.query(
                    "INSERT INTO idempotency_records (tenant_id, actor_subject, route, idempotency_key, request_hash) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                    (rs, row) -> rs.getObject(1, UUID.class), tenantId, subject, route, key, requestHash);
            if (inserted.isEmpty()) {
                return replay(tenantId, subject, route, key, requestHash);
            }
            UUID transactionOwnerId = transactionOwnerId(tenantId, id);
            if (transactionOwnerId == null || !transactionOwnerId.equals(userId)
                    && !canManageFamilyTransactions(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
            }
            List<TransactionResponse> rows = jdbc.query("""
                    SELECT t.id, t.tenant_id, t.type, t.amount::text, t.currency, t.category_code, t.subcategory_code,
                           t.description, t.source, t.occurred_at, t.account_id, t.status, t.version, t.created_at,
                           p.display_name AS member_name, t.debt_id, t.owner_user_id
                    FROM transactions t JOIN member_profiles p ON p.tenant_id = t.tenant_id AND p.user_id = t.owner_user_id
                    WHERE t.tenant_id = ? AND t.id = ? FOR UPDATE OF t
                    """, TRANSACTION_LIST_MAPPER, tenantId, id);
            if (rows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
            }
            TransactionResponse before = rows.get(0);
            if (before.version() != expectedVersion || !"posted".equals(before.status())) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Transaction version is stale");
            }
            UUID nextOwnerId = request.ownerUserId() == null ? transactionOwnerId : request.ownerUserId();
            if (!nextOwnerId.equals(transactionOwnerId) && !canManageFamilyTransactions(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owners and admins may reassign transactions");
            }
            if (!isMember(tenantId, nextOwnerId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
            }
            if (request.accountId() != null && jdbc.queryForObject(
                    "SELECT count(*) FROM accounts WHERE tenant_id = ? AND id = ?", Integer.class,
                    tenantId, request.accountId()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
            }
            Instant now = Instant.now();
            String source = request.source() == null ? before.source() : request.source().trim();
            UUID nextDebtId = null;
            java.math.BigDecimal nextDebtEffect = java.math.BigDecimal.ZERO.setScale(2);
            if ("debt_payment".equals(before.type())) debts.reversePayment(tenantId, subject, id);
            if ("debt_payment".equals(request.type())) {
                nextDebtId = request.debtId() == null ? before.debtId() : request.debtId();
                if (nextDebtId == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debt payment requires a debtId");
                }
                nextDebtEffect = debts.applyEditedPayment(tenantId, subject, nextDebtId,
                        MoneyAmount.parse(request.amount()).value());
            }
            String nextOwnerSubject = subjectForUser(nextOwnerId);
            int updated = jdbc.update("""
                    UPDATE transactions SET type = ?, amount = ?, currency = ?, category_code = ?,
                      subcategory_code = ?, description = ?, source = ?, occurred_at = ?, account_id = ?,
                      debt_id = ?, debt_balance_effect = ?, owner_user_id = ?, owner_subject = ?,
                      version = version + 1, updated_at = ?
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = 'posted'
                    """, request.type(), MoneyAmount.parse(request.amount()).value(), request.currency(),
                    request.categoryCode(), request.subcategoryCode(), request.description(), source,
                    Timestamp.from(request.occurredAt()), request.accountId(), nextDebtId, nextDebtEffect,
                    nextOwnerId, nextOwnerSubject, Timestamp.from(now), tenantId, id, expectedVersion);
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Transaction version is stale");
            }
            TransactionResponse after = jdbc.queryForObject("""
                    SELECT t.id, t.tenant_id, t.type, t.amount::text, t.currency, t.category_code, t.subcategory_code,
                           t.description, t.source, t.occurred_at, t.account_id, t.status, t.version, t.created_at,
                           p.display_name AS member_name, t.debt_id, t.owner_user_id
                    FROM transactions t JOIN member_profiles p ON p.tenant_id = t.tenant_id AND p.user_id = t.owner_user_id
                    WHERE t.tenant_id = ? AND t.id = ?
                    """, TRANSACTION_LIST_MAPPER, tenantId, id);
            String beforeJson = serialize(before);
            String afterJson = serialize(after);
            UUID eventId = UUID.randomUUID();
            String traceId = UUID.randomUUID().toString();
            Map<String, Object> transactionPayload = new LinkedHashMap<>();
            transactionPayload.put("owner_user_id", nextOwnerId);
            transactionPayload.put("type", after.type());
            transactionPayload.put("amount", after.amount());
            transactionPayload.put("currency", after.currency());
            transactionPayload.put("category_code", after.categoryCode());
            transactionPayload.put("description", after.description());
            transactionPayload.put("source", after.source());
            transactionPayload.put("status", after.status());
            transactionPayload.put("financial_occurred_at", after.occurredAt());
            if (after.debtId() != null) {
                transactionPayload.put("debt_id", after.debtId());
                transactionPayload.put("debt_balance_effect", nextDebtEffect.toPlainString());
            }
            String payload = serialize(Map.ofEntries(
                    Map.entry("event_id", eventId), Map.entry("event_type", "transaction.updated"),
                    Map.entry("schema_version", 1), Map.entry("tenant_id", tenantId),
                    Map.entry("aggregate_type", "transaction"), Map.entry("aggregate_id", id),
                    Map.entry("aggregate_version", after.version()), Map.entry("occurred_at", now),
                    Map.entry("recorded_at", now), Map.entry("producer", "core"),
                    Map.entry("correlation_id", traceId), Map.entry("payload", transactionPayload)));
            jdbc.update("""
                    INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id,
                                           before_state, after_state, trace_id)
                    VALUES (?, ?, 'transaction.updated', 'transaction', ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                    """, tenantId, subject, id, beforeJson, afterJson, traceId);
            jdbc.update("""
                    INSERT INTO outbox_events
                      (event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload)
                    VALUES (?, ?, 'transaction', ?, ?, 'transaction.updated', CAST(? AS jsonb))
                    """, eventId, tenantId, id, after.version(), payload);
            jdbc.update("UPDATE idempotency_records SET response_status = 200, response_body = CAST(? AS jsonb) "
                            + "WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                    afterJson, tenantId, subject, route, key);
            return after;
        });
    }

    public TransactionResponse voidTransaction(UUID tenantId, String subject, UUID id, String key, long expectedVersion) {
        if (expectedVersion < 1 || key == null || key.length() < 16 || key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid version or idempotency key");
        }
        String route = "/transactions/" + id + "/void";
        String requestHash = sha256(id + "|" + expectedVersion);
        return transaction.execute(status -> {
            setTenantContext(tenantId);
            UUID userId = resolveUser(subject);
            if (userId == null || !isMember(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
            }
            requireWritePermission(tenantId, userId);
            List<UUID> inserted = jdbc.query(
                    "INSERT INTO idempotency_records (tenant_id, actor_subject, route, idempotency_key, request_hash) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id",
                    (rs, row) -> rs.getObject(1, UUID.class), tenantId, subject, route, key, requestHash);
            if (inserted.isEmpty()) {
                return replay(tenantId, subject, route, key, requestHash);
            }
            UUID transactionOwnerId = transactionOwnerId(tenantId, id);
            if (transactionOwnerId == null || !transactionOwnerId.equals(userId)
                    && !canManageFamilyTransactions(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
            }
            List<TransactionResponse> rows = jdbc.query("""
                    SELECT t.id, t.tenant_id, t.type, t.amount::text, t.currency, t.category_code, t.subcategory_code,
                           t.description, t.source, t.occurred_at, t.account_id, t.status, t.version, t.created_at,
                           p.display_name AS member_name, t.debt_id, t.owner_user_id
                    FROM transactions t JOIN member_profiles p ON p.tenant_id = t.tenant_id AND p.user_id = t.owner_user_id
                    WHERE t.tenant_id = ? AND t.id = ? FOR UPDATE OF t
                    """, TRANSACTION_LIST_MAPPER, tenantId, id);
            if (rows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
            }
            TransactionResponse before = rows.get(0);
            if (before.version() != expectedVersion || !"posted".equals(before.status())) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Transaction version is stale");
            }
            Instant now = Instant.now();
            int updated = jdbc.update("""
                    UPDATE transactions SET status = 'voided', version = version + 1, updated_at = ?
                    WHERE tenant_id = ? AND id = ? AND version = ? AND status = 'posted'
                    """, Timestamp.from(now), tenantId, id, expectedVersion);
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Transaction version is stale");
            }
            TransactionResponse after = jdbc.queryForObject("""
                    SELECT t.id, t.tenant_id, t.type, t.amount::text, t.currency, t.category_code, t.subcategory_code,
                           t.description, t.source, t.occurred_at, t.account_id, t.status, t.version, t.created_at,
                           p.display_name AS member_name, t.debt_id, t.owner_user_id
                    FROM transactions t JOIN member_profiles p ON p.tenant_id = t.tenant_id AND p.user_id = t.owner_user_id
                    WHERE t.tenant_id = ? AND t.id = ?
                    """, TRANSACTION_LIST_MAPPER, tenantId, id);
            debts.reversePayment(tenantId, subject, id);
            String beforeJson = serialize(before);
            String afterJson = serialize(after);
            UUID eventId = UUID.randomUUID();
            String traceId = UUID.randomUUID().toString();
            Map<String, Object> transactionPayload = new LinkedHashMap<>();
            transactionPayload.put("owner_user_id", transactionOwnerId);
            transactionPayload.put("type", after.type());
            transactionPayload.put("amount", after.amount());
            transactionPayload.put("currency", after.currency());
            transactionPayload.put("category_code", after.categoryCode());
            transactionPayload.put("description", after.description());
            transactionPayload.put("source", after.source());
            transactionPayload.put("status", after.status());
            transactionPayload.put("financial_occurred_at", after.occurredAt());
            jdbc.query("SELECT debt_id, debt_balance_effect::numeric(20,2)::text AS effect FROM transactions WHERE tenant_id = ? AND id = ?",
                    rs -> {
                        if (rs.next() && rs.getObject("debt_id", UUID.class) != null) {
                            transactionPayload.put("debt_id", rs.getObject("debt_id", UUID.class));
                            transactionPayload.put("debt_balance_effect", rs.getString("effect"));
                        }
                        return null;
                    }, tenantId, id);
            String payload = serialize(Map.ofEntries(
                    Map.entry("event_id", eventId), Map.entry("event_type", "transaction.voided"),
                    Map.entry("schema_version", 1), Map.entry("tenant_id", tenantId),
                    Map.entry("aggregate_type", "transaction"), Map.entry("aggregate_id", id),
                    Map.entry("aggregate_version", after.version()), Map.entry("occurred_at", now),
                    Map.entry("recorded_at", now), Map.entry("producer", "core"),
                    Map.entry("correlation_id", traceId), Map.entry("payload", transactionPayload)));
            jdbc.update("""
                    INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id, before_state, after_state, trace_id)
                    VALUES (?, ?, 'transaction.voided', 'transaction', ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                    """, tenantId, subject, id, beforeJson, afterJson, traceId);
            jdbc.update("""
                    INSERT INTO outbox_events
                      (event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload)
                    VALUES (?, ?, 'transaction', ?, ?, 'transaction.voided', CAST(? AS jsonb))
                    """, eventId, tenantId, id, after.version(), payload);
            jdbc.update("UPDATE idempotency_records SET response_status = 200, response_body = CAST(? AS jsonb) WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                    afterJson, tenantId, subject, route, key);
            return after;
        });
    }

    public Page list(UUID tenantId, String subject, int pageSize, String cursor) {
        return list(tenantId, subject, pageSize, cursor, null, null, null, null);
    }

    /** Recent posted operations for the requesting member, ordered by when Core recorded them. */
    public List<TransactionResponse> recent(UUID tenantId, String subject, int limit) {
        if (limit < 1 || limit > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "History limit must be from 1 to 20");
        }
        return transaction.execute(status -> {
            setTenantContext(tenantId);
            UUID userId = resolveUser(subject);
            if (userId == null || !isMember(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            }
            return jdbc.query("""
                    SELECT id, tenant_id, type, amount::text, currency, category_code, subcategory_code,
                           description, source, occurred_at, account_id, status, version, created_at
                    FROM transactions WHERE tenant_id = ? AND owner_user_id = ? AND status = 'posted'
                    ORDER BY created_at DESC, id DESC LIMIT ?
                    """, TRANSACTION_MAPPER, tenantId, userId, limit);
        });
    }

    /** Undo only the latest posted operation visible to this member; retries replay the original result. */
    public TransactionResponse voidLatest(UUID tenantId, String subject, UUID id, String key, long expectedVersion) {
        if (id == null || expectedVersion < 1 || key == null || key.length() < 16 || key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction, version or idempotency key");
        }
        String route = "/transactions/" + id + "/void";
        String requestHash = sha256(id + "|" + expectedVersion);
        return transaction.execute(status -> {
            setTenantContext(tenantId);
            UUID userId = resolveUser(subject);
            if (userId == null || !isMember(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
            }
            requireWritePermission(tenantId, userId);
            Integer prior = jdbc.queryForObject("SELECT count(*) FROM idempotency_records "
                    + "WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                    Integer.class, tenantId, subject, route, key);
            if (prior != null && prior > 0) return replay(tenantId, subject, route, key, requestHash);

            List<TransactionResponse> latest = jdbc.query("""
                    SELECT id, tenant_id, type, amount::text, currency, category_code, subcategory_code,
                           description, source, occurred_at, account_id, status, version, created_at
                    FROM transactions WHERE tenant_id = ? AND owner_user_id = ? AND status = 'posted'
                    ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE
                    """, TRANSACTION_MAPPER, tenantId, userId);
            if (latest.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No posted transaction to undo");
            TransactionResponse current = latest.get(0);
            if (!current.id().equals(id) || current.version() != expectedVersion) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Latest transaction has changed");
            }
            return voidTransaction(tenantId, subject, id, key, expectedVersion);
        });
    }

    public Page list(UUID tenantId, String subject, int pageSize, String cursor,
                     LocalDate from, LocalDate to, String type, String search) {
        return list(tenantId, subject, pageSize, cursor, from, to, type, search, null);
    }

    /** Managers may select one active member or the full tenant; other roles remain member-scoped. */
    public Page list(UUID tenantId, String subject, int pageSize, String cursor,
                     LocalDate from, LocalDate to, String type, String search, String memberId) {
        if (pageSize < 1 || pageSize > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pageSize must be from 1 to 200");
        }
        if (from != null && to != null && from.isAfter(to)
                || type != null && !TYPES.contains(type)
                || search != null && search.length() > 120
                || memberId != null && memberId.length() > 36) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction filters");
        }
        Cursor after = decodeCursor(cursor);
        return transaction.execute(status -> {
            setTenantContext(tenantId);
            UUID userId = resolveUser(subject);
            if (userId == null || !isMember(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            }
            String requesterRole = jdbc.queryForObject("SELECT role FROM memberships WHERE tenant_id = ? AND user_id = ? "
                    + "AND status = 'active'", String.class, tenantId, userId);
            boolean canViewFamily = "owner".equals(requesterRole) || "admin".equals(requesterRole);
            UUID ownerFilter = userId;
            if (memberId != null) {
                if ("all".equals(memberId)) {
                    if (!canViewFamily) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Family transaction access is restricted");
                    ownerFilter = null;
                } else {
                    UUID requestedMember;
                    try {
                        requestedMember = UUID.fromString(memberId);
                    } catch (IllegalArgumentException invalidMember) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid memberId", invalidMember);
                    }
                    if (!requestedMember.equals(userId) && !canViewFamily) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Family transaction access is restricted");
                    }
                    if (!isMember(tenantId, requestedMember)) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
                    }
                    ownerFilter = requestedMember;
                }
            }
            ZoneId memberZone = ZoneId.of(jdbc.queryForObject(
                    "SELECT timezone FROM member_profiles WHERE tenant_id = ? AND user_id = ?",
                    String.class, tenantId, userId));
            String query = """
                    SELECT t.id, t.tenant_id, t.type, t.amount::text, t.currency, t.category_code, t.subcategory_code,
                           t.description, t.source, t.occurred_at, t.account_id, t.status, t.version, t.created_at,
                           p.display_name AS member_name, t.debt_id, t.owner_user_id
                    FROM transactions t
                    JOIN member_profiles p ON p.tenant_id = t.tenant_id AND p.user_id = t.owner_user_id
                    WHERE t.tenant_id = ?
                    """;
            List<Object> args = new ArrayList<>(List.of(tenantId));
            if (ownerFilter != null) {
                query += " AND t.owner_user_id = ?";
                args.add(ownerFilter);
            }
            if (from != null) {
                query += " AND t.occurred_at >= ?";
                args.add(Timestamp.from(from.atStartOfDay(memberZone).toInstant()));
            }
            if (to != null) {
                query += " AND t.occurred_at < ?";
                args.add(Timestamp.from(to.plusDays(1).atStartOfDay(memberZone).toInstant()));
            }
            if (type != null) {
                if ("expense".equals(type)) {
                    query += " AND t.type IN ('expense', 'debt_payment')";
                } else if ("income".equals(type)) {
                    query += " AND t.type IN ('income', 'refund')";
                } else {
                    query += " AND t.type = ?";
                    args.add(type);
                }
            }
            if (search != null && !search.isBlank()) {
                query += " AND strpos(lower(coalesce(t.description, '') || ' ' || t.category_code || ' ' "
                        + "|| coalesce(p.display_name, '')), lower(?)) > 0";
                args.add(search.trim());
            }
            if (after != null) {
                query += " AND (t.occurred_at, t.id) < (?, ?)";
                args.add(Timestamp.from(after.occurredAt()));
                args.add(after.id());
            }
            query += " ORDER BY t.occurred_at DESC, t.id DESC LIMIT ?";
            args.add(pageSize + 1);
            List<TransactionResponse> items = jdbc.query(query, TRANSACTION_LIST_MAPPER, args.toArray());
            String nextCursor = null;
            if (items.size() > pageSize) {
                items = new ArrayList<>(items.subList(0, pageSize));
                nextCursor = encodeCursor(items.get(items.size() - 1));
            }
            return new Page(items, nextCursor);
        });
    }

    public DashboardSummary summary(UUID tenantId, String subject, YearMonth requestedMonth) {
        return transaction.execute(status -> {
            setTenantContext(tenantId);
            UUID userId = resolveUser(subject);
            if (userId == null || !isMember(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            }
            ZoneId memberZone = ZoneId.of(jdbc.queryForObject(
                    "SELECT timezone FROM member_profiles WHERE tenant_id = ? AND user_id = ?",
                    String.class, tenantId, userId));
            YearMonth month = requestedMonth == null ? YearMonth.now(memberZone) : requestedMonth;
            Instant start = month.atDay(1).atStartOfDay(memberZone).toInstant();
            Instant end = month.plusMonths(1).atDay(1).atStartOfDay(memberZone).toInstant();
            DashboardSummary totals = jdbc.queryForObject("""
                    SELECT ? AS month,
                           COALESCE(sum(amount) FILTER (WHERE type = 'income'), 0.00)::numeric(20,2)::text AS income_total,
                           COALESCE(sum(amount) FILTER (WHERE type = 'expense'), 0.00)::numeric(20,2)::text AS expense_total,
                           count(*)::integer AS transaction_count
                    FROM transactions
                    WHERE tenant_id = ? AND owner_user_id = ? AND status = 'posted'
                      AND type IN ('income', 'expense') AND occurred_at >= ? AND occurred_at < ?
                    """, (rs, row) -> new DashboardSummary(rs.getString("month"), "RUB",
                     rs.getString("income_total"), rs.getString("expense_total"), rs.getInt("transaction_count"),
                     null, 0, 0, 0, null, null, null, null),
                    month.toString(), tenantId, userId, Timestamp.from(start), Timestamp.from(end));
            LocalDate today = LocalDate.now(memberZone);
            YearMonth currentMonth = YearMonth.from(today);
            int daysInMonth = month.lengthOfMonth();
            int daysElapsed = month.isBefore(currentMonth) ? daysInMonth
                    : month.isAfter(currentMonth) ? 0 : today.getDayOfMonth();
            String dailyPace = MonthlyPacePolicy.averageDailySpend(totals.expenseTotal(), daysElapsed);
            String projection = month.equals(currentMonth)
                    ? MonthlyPacePolicy.projectedSpend(totals.expenseTotal(), daysElapsed, daysInMonth) : null;
            var safeToSpend = cashPlanning.safeToSpend(tenantId, userId, memberZone, today, month,
                    totals.incomeTotal(), totals.expenseTotal());
            return new DashboardSummary(totals.month(), totals.currency(), totals.incomeTotal(), totals.expenseTotal(),
                    totals.transactionCount(), today, daysElapsed, daysInMonth, Math.max(0, daysInMonth - daysElapsed),
                    dailyPace, projection, budgets.get(tenantId, subject).rolling7FoodStatus(), safeToSpend);
        });
    }

    private static List<BudgetAlertPolicy.Alert> crossedBudgetAlerts(BudgetApi.Overview before,
                                                                     BudgetApi.Overview after,
                                                                     String categoryCode) {
        if (!before.month().equals(after.month())) return List.of();
        List<BudgetAlertPolicy.Alert> alerts = new ArrayList<>(2);
        BudgetAlertPolicy.crossed(categoryCode,
                after.effectiveLimits().getOrDefault(categoryCode, "0.00"),
                before.monthlySpent().getOrDefault(categoryCode, "0.00"),
                after.monthlySpent().getOrDefault(categoryCode, "0.00")).ifPresent(alerts::add);
        BudgetAlertPolicy.crossed("__total__", after.effectiveTotalLimit(), before.totalMonthlySpent(),
                after.totalMonthlySpent()).ifPresent(alerts::add);
        return List.copyOf(alerts);
    }

    private TransactionResponse replay(UUID tenantId, String subject, String route, String key, String requestHash) {
        var existing = jdbc.queryForMap("SELECT request_hash, response_body::text FROM idempotency_records WHERE tenant_id = ? AND actor_subject = ? AND route = ? AND idempotency_key = ?",
                tenantId, subject, route, key);
        if (!requestHash.equals(existing.get("request_hash"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was used with another request");
        }
        try {
            return json.readValue((String) existing.get("response_body"), TransactionResponse.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Stored idempotency response is invalid", ex);
        }
    }

    private void setTenantContext(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

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

    private boolean canManageFamilyTransactions(UUID tenantId, UUID userId) {
        String role = jdbc.queryForObject("SELECT role FROM memberships WHERE tenant_id = ? AND user_id = ? AND status = 'active'",
                String.class, tenantId, userId);
        return "owner".equals(role) || "admin".equals(role);
    }

    private UUID transactionOwnerId(UUID tenantId, UUID transactionId) {
        List<UUID> owners = jdbc.query("SELECT owner_user_id FROM transactions WHERE tenant_id = ? AND id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, transactionId);
        return owners.isEmpty() ? null : owners.get(0);
    }

    private String subjectForUser(UUID userId) {
        List<String> subjects = jdbc.query("SELECT subject FROM external_identities "
                        + "WHERE provider = 'keycloak' AND user_id = ? LIMIT 1",
                (rs, row) -> rs.getString("subject"), userId);
        if (subjects.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member identity not found");
        return subjects.get(0);
    }

    private String memberDisplayName(UUID tenantId, UUID userId) {
        return jdbc.queryForObject("SELECT display_name FROM member_profiles WHERE tenant_id = ? AND user_id = ?",
                String.class, tenantId, userId);
    }

    private void requireWritePermission(UUID tenantId, UUID userId) {
        String role = jdbc.queryForObject("SELECT role FROM memberships WHERE tenant_id = ? AND user_id = ? AND status = 'active'",
                String.class, tenantId, userId);
        if ("viewer".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Viewer access is read-only");
        }
    }

    private static void validate(UUID tenantId, String subject, String key, CreateRequest request) {
        if (tenantId == null || subject == null || subject.isBlank() || key == null || key.length() < 16 || key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tenant, subject or idempotency key");
        }
        if (request == null || !TYPES.contains(request.type()) || "debt_payment".equals(request.type()) || !"RUB".equals(request.currency())
                || request.categoryCode() == null || request.categoryCode().isBlank() || request.categoryCode().length() > 64
                || request.subcategoryCode() != null && request.subcategoryCode().length() > 64
                || request.source() != null && (request.source().isBlank() || request.source().trim().length() > 64)
                || request.description() == null || request.description().length() > 500 || request.occurredAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction fields");
        }
        try {
            MoneyAmount.parse(request.amount());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid positive decimal amount", ex);
        }
    }

    private static void validateUpdate(UUID tenantId, String subject, String key, UpdateRequest request) {
        if (tenantId == null || subject == null || subject.isBlank() || key == null || key.length() < 16 || key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tenant, subject or idempotency key");
        }
        if (request == null || !TYPES.contains(request.type()) || !"RUB".equals(request.currency())
                || request.categoryCode() == null || request.categoryCode().isBlank() || request.categoryCode().length() > 64
                || request.subcategoryCode() != null && request.subcategoryCode().length() > 64
                || request.source() != null && (request.source().isBlank() || request.source().trim().length() > 64)
                || request.description() == null || request.description().length() > 500 || request.occurredAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction fields");
        }
        try {
            MoneyAmount.parse(request.amount());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid positive decimal amount", ex);
        }
    }

    private static String sourceForCreate(String source) {
        return source == null ? "web" : source.trim();
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Cannot serialize transaction", ex);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Cursor decodeCursor(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\n", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Malformed cursor");
            }
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor", ex);
        }
    }

    private static String encodeCursor(TransactionResponse last) {
        String raw = last.occurredAt() + "\n" + last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(Instant occurredAt, UUID id) {}
}
