package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ReceiptApi.CreateRequest;
import com.decorix.finance.core.api.ReceiptApi.CategorySelection;
import com.decorix.finance.core.api.ReceiptApi.ItemInput;
import com.decorix.finance.core.api.ReceiptApi.ReceiptItem;
import com.decorix.finance.core.api.ReceiptApi.ReceiptItemPage;
import com.decorix.finance.core.api.ReceiptApi.ReceiptResponse;
import com.decorix.finance.core.domain.ReceiptReconciliationPolicy;
import com.decorix.finance.core.domain.ReceiptCategoryPolicy;
import com.decorix.finance.core.domain.ReceiptBasketPolicy;
import com.decorix.finance.core.domain.ProductIdentityPolicy;
import com.decorix.finance.core.domain.ReceiptRepeatWarningPolicy;
import com.decorix.finance.core.domain.ReceiptDuplicatePolicy;
import com.decorix.finance.core.domain.ReceiptConfirmationPolicy;
import com.decorix.finance.core.api.ReceiptApi.RepeatWarning;
import com.decorix.finance.core.api.ReceiptApi.RepeatWarnings;
import com.decorix.finance.core.api.ReceiptApi.DuplicateCandidate;
import com.decorix.finance.core.api.ReceiptApi.DuplicateCandidates;
import com.decorix.finance.core.api.ReceiptApi.DuplicateDecisionSelection;
import com.decorix.finance.core.api.ProductApi.ProductDecisionResponse;
import com.decorix.finance.core.api.ProductApi.ProductDecisionSelection;
import com.decorix.finance.core.api.ProductApi.ProductDecisionKeys;
import com.decorix.finance.core.api.TransactionApi.TransactionResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReceiptService {
    private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999999999.99");
    private static final RowMapper<ReceiptHeader> HEADER_MAPPER = (rs, row) -> new ReceiptHeader(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
            rs.getObject("document_id", UUID.class), rs.getString("state"), rs.getLong("version"),
            rs.getObject("transaction_id", UUID.class),
            rs.getString("currency"), rs.getString("cash_total"), rs.getString("items_total"),
            rs.getString("merchant"), rs.getObject("receipt_date", LocalDate.class),
            rs.getString("selected_reader"), rs.getString("category_code"), rs.getString("category_source"),
            rs.getString("category_algorithm_version"), rs.getString("alcohol_share"),
            rs.getString("leisure_share"), rs.getBoolean("leisure"), rs.getString("duplicate_decision"),
            rs.getObject("duplicate_of_receipt_id", UUID.class), rs.getTimestamp("created_at").toInstant());
    private static final String HEADER_COLUMNS = "id, tenant_id, document_id, state, version, transaction_id, currency, "
            + "cash_total::text AS cash_total, items_total::text AS items_total, merchant, receipt_date, "
            + "selected_reader, category_code, category_source, category_algorithm_version, "
            + "alcohol_share::text AS alcohol_share, leisure_share::text AS leisure_share, leisure, "
            + "duplicate_decision, duplicate_of_receipt_id, created_at";
    private static final RowMapper<ReceiptItem> ITEM_MAPPER = (rs, row) -> new ReceiptItem(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("quantity"),
            rs.getString("unit_price"), rs.getString("line_sum"), ProductIdentityPolicy.productKey(rs.getString("name")),
            rs.getString("provenance"),
            rs.getObject("confidence", Double.class), rs.getString("category_code"), rs.getString("verdict"),
            rs.getString("advice"), rs.getString("review_reason"), rs.getString("review_action"),
            rs.getString("verdict_source"), rs.getString("review_provider"), rs.getString("review_model_version"),
            rs.getString("review_prompt_version"), rs.getString("review_algorithm_version"), rs.getLong("version"));
    private static final String ITEM_COLUMNS = "id, name, quantity::text AS quantity, unit_price::text AS unit_price, "
            + "line_sum::text AS line_sum, evidence ->> 'provenance' AS provenance, "
            + "nullif(evidence ->> 'confidence', '')::double precision AS confidence, category_code, verdict, advice, "
            + "review_reason, review_action, verdict_source, review_provider, review_model_version, "
            + "review_prompt_version, review_algorithm_version, version";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final ReceiptBasketAdvisor basketAdvisor;
    private final TransactionService transactions;

    public ReceiptService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json,
                          ReceiptBasketAdvisor basketAdvisor, TransactionService transactions) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
        this.basketAdvisor = basketAdvisor;
        this.transactions = transactions;
    }

    public ReceiptResponse create(UUID tenantId, String subject, String key, CreateRequest request) {
        validateKey(key);
        PreparedReceipt prepared = validateRequest(request);
        ReceiptCategoryPolicy.Result category = classifyPrepared(prepared);
        String requestHash = sha256(serialize(request));
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            requireWritePermission(tenantId, userId);
            ReceiptResponse replay = findByCreateKey(tenantId, userId, key, requestHash);
            if (replay != null) return replay;
            if (prepared.documentId() != null) requireReadyDocument(tenantId, userId, prepared.documentId());

            UUID id = UUID.randomUUID();
            List<UUID> inserted = jdbc.query("""
                    INSERT INTO receipts
                      (id, tenant_id, owner_user_id, owner_subject, document_id, state, cash_total, items_total,
                       merchant, receipt_date, selected_reader, algorithm_version, category_code, category_source,
                       category_algorithm_version, alcohol_share, leisure_share, leisure,
                       create_idempotency_key, create_request_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'manual', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, owner_user_id, create_idempotency_key) DO NOTHING
                    RETURNING id
                    """, (rs, row) -> rs.getObject("id", UUID.class), id, tenantId, userId, subject,
                    prepared.documentId(), prepared.state(), prepared.cashTotal(), prepared.itemsTotal(),
                    prepared.merchant(), prepared.receiptDate(), ReceiptReconciliationPolicy.ALGORITHM_VERSION,
                    category.category(), category.categorySource(), category.algorithmVersion(),
                    category.alcoholShare(), category.leisureShare(), category.leisure(), key, requestHash);
            if (inserted.isEmpty()) {
                ReceiptResponse raced = findByCreateKey(tenantId, userId, key, requestHash);
                if (raced != null) return raced;
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Receipt could not be created");
            }

            int ordinal = 1;
            for (PreparedItem item : prepared.items()) {
                jdbc.update("""
                        INSERT INTO receipt_items
                          (tenant_id, receipt_id, ordinal, name, quantity, unit_price, line_sum, evidence)
                        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                        """, tenantId, id, ordinal++, item.name(), item.quantity(), item.unitPrice(), item.lineSum(),
                        "{\"provenance\":\"manual\"}");
            }

            Map<String, Object> afterState = new LinkedHashMap<>();
            afterState.put("state", prepared.state());
            afterState.put("cashTotal", prepared.cashTotal() == null ? null : prepared.cashTotal().toPlainString());
            afterState.put("itemsTotal", prepared.itemsTotal() == null ? null : prepared.itemsTotal().toPlainString());
            afterState.put("categoryCode", category.category());
            afterState.put("categorySource", category.categorySource());
            jdbc.update("""
                    INSERT INTO receipt_reviews
                      (tenant_id, receipt_id, actor_subject, action, before_state, after_state, algorithm_version)
                    VALUES (?, ?, ?, 'receipt.created', '{}'::jsonb, CAST(? AS jsonb), ?)
                    """, tenantId, id, subject, serialize(afterState), ReceiptReconciliationPolicy.ALGORITHM_VERSION);
            return getWithinTransaction(tenantId, userId, id);
        });
    }

    public ReceiptResponse get(UUID tenantId, String subject, UUID receiptId) {
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            return getWithinTransaction(tenantId, userId, receiptId);
        });
    }

    public ReceiptItemPage getItems(UUID tenantId, String subject, UUID receiptId, int page) {
        if (page < 1 || page > 1_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid receipt item page");
        }
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            getHeader(tenantId, userId, receiptId, false);
            int totalItems = jdbc.queryForObject("SELECT count(*) FROM receipt_items WHERE tenant_id = ? AND receipt_id = ?",
                    Integer.class, tenantId, receiptId);
            long offset = (long) (page - 1) * 8;
            List<ReceiptItem> items = jdbc.query("SELECT " + ITEM_COLUMNS + " FROM receipt_items "
                            + "WHERE tenant_id = ? AND receipt_id = ? ORDER BY ordinal LIMIT 8 OFFSET ?",
                    ITEM_MAPPER, tenantId, receiptId, offset);
            return new ReceiptItemPage(List.copyOf(items), page, totalItems, offset + items.size() < totalItems);
        });
    }

    public ReceiptItemPage disputedItems(UUID tenantId, String subject, UUID receiptId, int page) {
        validatePage(page);
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            getHeader(tenantId, userId, receiptId, false);
            List<String> allowedKeys = jdbc.query("SELECT product_key FROM user_product_decisions "
                            + "WHERE tenant_id = ? AND user_id = ? AND decision = 'allowed'",
                    (rs, row) -> rs.getString("product_key"), tenantId, userId);
            java.util.Set<String> allowed = java.util.Set.copyOf(allowedKeys);
            List<ReceiptItem> disputed = jdbc.query("SELECT " + ITEM_COLUMNS + " FROM receipt_items "
                            + "WHERE tenant_id = ? AND receipt_id = ? AND verdict IN ('harmful', 'unnecessary') "
                            + "ORDER BY line_sum DESC NULLS LAST, ordinal ASC",
                    ITEM_MAPPER, tenantId, receiptId).stream()
                    .filter(item -> !item.productKey().isEmpty() && !allowed.contains(item.productKey())).toList();
            int offset = (page - 1) * 8;
            List<ReceiptItem> items = offset >= disputed.size() ? List.of()
                    : disputed.subList(offset, Math.min(offset + 8, disputed.size()));
            return new ReceiptItemPage(List.copyOf(items), page, disputed.size(), offset + items.size() < disputed.size());
        });
    }

    public RepeatWarnings repeatWarnings(UUID tenantId, String subject, UUID receiptId) {
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            getHeader(tenantId, userId, receiptId, false);
            List<ReceiptRepeatWarningPolicy.CurrentItem> current = loadReceiptLines(tenantId, receiptId).stream()
                    .map(item -> new ReceiptRepeatWarningPolicy.CurrentItem(item.id(), item.name())).toList();
            List<String> allowedKeys = jdbc.query("SELECT product_key FROM user_product_decisions "
                            + "WHERE tenant_id = ? AND user_id = ? AND decision = 'allowed'",
                    (rs, row) -> rs.getString("product_key"), tenantId, userId);
            ReceiptRepeatWarningPolicy.Accumulator warnings = new ReceiptRepeatWarningPolicy.Accumulator(
                    receiptId, current, java.util.Set.copyOf(allowedKeys));
            org.springframework.jdbc.core.PreparedStatementCreator historyQuery = connection -> {
                var statement = connection.prepareStatement("""
                        SELECT r.id AS receipt_id, ri.name, ri.verdict, ri.line_sum,
                               ri.advice, coalesce(r.receipt_date::timestamp AT TIME ZONE 'UTC', r.created_at) AS occurred_at
                        FROM receipts r JOIN receipt_items ri ON ri.tenant_id = r.tenant_id AND ri.receipt_id = r.id
                        WHERE r.tenant_id = ? AND r.owner_user_id = ? AND r.state = 'confirmed'
                          AND r.id <> ? AND ri.verdict IN ('harmful', 'unnecessary')
                        ORDER BY occurred_at DESC, r.created_at DESC, ri.ordinal ASC
                        """);
                statement.setFetchSize(500);
                statement.setObject(1, tenantId);
                statement.setObject(2, userId);
                statement.setObject(3, receiptId);
                return statement;
            };
            org.springframework.jdbc.core.RowCallbackHandler historyRows = rs -> warnings.add(new ReceiptRepeatWarningPolicy.HistoryItem(
                    rs.getObject("receipt_id", UUID.class), rs.getString("name"), rs.getString("verdict"),
                    rs.getBigDecimal("line_sum"), rs.getString("advice"), rs.getTimestamp("occurred_at").toInstant()));
            jdbc.query(historyQuery, historyRows);
            List<RepeatWarning> result = warnings.finish().stream().map(warning -> new RepeatWarning(
                    warning.itemId(), warning.name(), ProductIdentityPolicy.productKey(warning.name()),
                    warning.verdict(), warning.title(), warning.count(), warning.lastSum(), warning.advice())).toList();
            return new RepeatWarnings(List.copyOf(result));
        });
    }

    public DuplicateCandidates duplicateCandidates(UUID tenantId, String subject, UUID receiptId) {
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            ReceiptHeader receipt = getHeader(tenantId, userId, receiptId, false);
            List<DuplicateCandidate> candidates = duplicateCandidateRows(tenantId, userId, receipt).stream()
                    .map(candidate -> new DuplicateCandidate(candidate.id(), candidate.cashTotal(), candidate.merchant(),
                            candidate.createdAt())).toList();
            return new DuplicateCandidates(receipt.id(), receipt.duplicateDecision(), List.copyOf(candidates));
        });
    }

    public ReceiptResponse decideDuplicate(UUID tenantId, String subject, UUID receiptId, long expectedVersion,
                                           DuplicateDecisionSelection selection) {
        validateVersion(expectedVersion);
        if (selection == null || !("independent".equals(selection.decision()) || "duplicate".equals(selection.decision()))
                || ("independent".equals(selection.decision()) && selection.duplicateReceiptId() != null)
                || ("duplicate".equals(selection.decision()) && selection.duplicateReceiptId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt duplicate decision is invalid");
        }
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            requireWritePermission(tenantId, userId);
            ReceiptHeader before = lockEditableReceipt(tenantId, userId, receiptId, expectedVersion);
            if (selection.decision().equals(before.duplicateDecision())
                    && java.util.Objects.equals(selection.duplicateReceiptId(), before.duplicateOfReceiptId())) {
                return getWithinTransaction(tenantId, userId, receiptId);
            }
            if ("duplicate".equals(selection.decision())) {
                boolean validCandidate = duplicateCandidateRows(tenantId, userId, before).stream()
                        .anyMatch(candidate -> candidate.id().equals(selection.duplicateReceiptId()));
                if (!validCandidate) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected receipt is not a recent duplicate candidate");
                }
            }
            int updated = jdbc.update("""
                    UPDATE receipts SET duplicate_decision = ?, duplicate_of_receipt_id = ?,
                      version = version + 1, updated_at = now()
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND version = ?
                    """, selection.decision(), selection.duplicateReceiptId(), tenantId, userId, receiptId, expectedVersion);
            if (updated != 1) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Receipt version is stale");
            ReceiptHeader after = getHeader(tenantId, userId, receiptId, false);
            recordReview(tenantId, receiptId, subject, "receipt.duplicate_decision", null, null, null,
                    stateSnapshot(before), stateSnapshot(after), ReceiptDuplicatePolicy.ALGORITHM_VERSION);
            return getWithinTransaction(tenantId, userId, receiptId);
        });
    }

    public ReceiptResponse confirm(UUID tenantId, String subject, UUID receiptId, String idempotencyKey,
                                   long expectedVersion) {
        validateKey(idempotencyKey);
        validateVersion(expectedVersion);
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            requireWritePermission(tenantId, userId);
            ReceiptHeader before = getHeader(tenantId, userId, receiptId, true);
            if ("confirmed".equals(before.state())) {
                String savedKey = jdbc.queryForObject("SELECT confirm_idempotency_key FROM receipts "
                        + "WHERE tenant_id = ? AND owner_user_id = ? AND id = ?", String.class,
                        tenantId, userId, receiptId);
                if (!idempotencyKey.equals(savedKey)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Receipt was already confirmed");
                }
                return getWithinTransaction(tenantId, userId, receiptId);
            }
            if (before.version() != expectedVersion) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Receipt version is stale");
            }
            if (before.cashTotal() == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Receipt total is required");
            }
            List<ReceiptDuplicatePolicy.Candidate> candidates = duplicateCandidateRows(tenantId, userId, before);
            BigDecimal itemsTotal = before.itemsTotal() == null ? null : new BigDecimal(before.itemsTotal());
            if (!ReceiptConfirmationPolicy.canConfirm(before.state(), new BigDecimal(before.cashTotal()), itemsTotal,
                    before.duplicateDecision(), !candidates.isEmpty())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Reconcile receipt totals and review recent matching receipts before confirming");
            }

            String timezone = jdbc.queryForObject("SELECT COALESCE((SELECT timezone FROM member_profiles "
                    + "WHERE tenant_id = ? AND user_id = ?), 'UTC')", String.class, tenantId, userId);
            ZoneId zone = ZoneId.of(timezone);
            LocalDate receiptDate = before.receiptDate() == null
                    ? before.createdAt().atZone(zone).toLocalDate() : before.receiptDate();
            Instant occurredAt = receiptDate.atTime(LocalTime.NOON).atZone(zone).toInstant();
            String description = before.merchant() == null ? "Покупка по чеку" : "Покупка: " + before.merchant();
            TransactionResponse posted = transactions.create(tenantId, subject,
                    "receipt-" + sha256(tenantId + "|" + receiptId + "|" + idempotencyKey).substring(0, 48),
                    new com.decorix.finance.core.api.TransactionApi.CreateRequest("expense", before.cashTotal(),
                            before.currency(), before.categoryCode() == null ? "прочее" : before.categoryCode(),
                            null, description, "receipt", occurredAt, null, null));
            int updated = jdbc.update("""
                    UPDATE receipts SET state = 'confirmed', transaction_id = ?, confirm_idempotency_key = ?,
                      confirmed_at = now(), version = version + 1, updated_at = now()
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND version = ?
                      AND state IN ('draft', 'review_required')
                    """, posted.id(), idempotencyKey, tenantId, userId, receiptId, expectedVersion);
            if (updated != 1) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Receipt version is stale");
            }
            ReceiptHeader after = getHeader(tenantId, userId, receiptId, false);
            recordReview(tenantId, receiptId, subject, "receipt.confirmed", null, null, null,
                    stateSnapshot(before), stateSnapshot(after), ReceiptConfirmationPolicy.ALGORITHM_VERSION);
            recordConfirmedPriceProjectionEvent(tenantId, userId, after, posted.id(), receiptDate, occurredAt);
            return getWithinTransaction(tenantId, userId, receiptId);
        });
    }

    private void recordConfirmedPriceProjectionEvent(UUID tenantId, UUID userId, ReceiptHeader receipt,
                                                     UUID transactionId, LocalDate receiptDate, Instant occurredAt) {
        List<Map<String, Object>> items = jdbc.query("""
                SELECT id, name, quantity::text AS quantity, line_sum::text AS line_sum
                FROM receipt_items WHERE tenant_id = ? AND receipt_id = ? ORDER BY ordinal
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item_id", rs.getObject("id", UUID.class));
            item.put("name", rs.getString("name"));
            item.put("quantity", rs.getString("quantity"));
            item.put("line_sum", rs.getString("line_sum"));
            return item;
        }, tenantId, receipt.id());

        UUID eventId = UUID.randomUUID();
        Instant recordedAt = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("owner_user_id", userId);
        payload.put("transaction_id", transactionId);
        payload.put("receipt_date", receiptDate);
        payload.put("currency", receipt.currency());
        payload.put("merchant", receipt.merchant());
        payload.put("items", items);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", eventId);
        event.put("event_type", "receipt.confirmed");
        event.put("schema_version", 1);
        event.put("tenant_id", tenantId);
        event.put("aggregate_type", "receipt");
        event.put("aggregate_id", receipt.id());
        event.put("aggregate_version", receipt.version());
        event.put("occurred_at", occurredAt);
        event.put("recorded_at", recordedAt);
        event.put("producer", "core");
        event.put("correlation_id", eventId.toString());
        event.put("payload", payload);

        jdbc.update("""
                INSERT INTO outbox_events
                  (event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version,
                   event_type, schema_version, payload)
                VALUES (?, ?, 'receipt', ?, ?, 'receipt.confirmed', 1, CAST(? AS jsonb))
                """, eventId, tenantId, receipt.id(), receipt.version(), serialize(event));
    }

    private List<ReceiptDuplicatePolicy.Candidate> duplicateCandidateRows(UUID tenantId, UUID userId,
                                                                           ReceiptHeader receipt) {
        if (receipt.cashTotal() == null) return List.of();
        List<ReceiptDuplicatePolicy.Candidate> possible = jdbc.query("""
                SELECT id, created_at, cash_total::text AS cash_total, merchant FROM receipts
                WHERE tenant_id = ? AND owner_user_id = ? AND id <> ? AND cash_total = ?
                  AND created_at > CAST(? AS timestamptz) - interval '10 minutes' AND created_at < ?
                  AND state <> 'cancelled' AND duplicate_decision <> 'duplicate'
                ORDER BY created_at DESC, id
                """, (rs, row) -> new ReceiptDuplicatePolicy.Candidate(rs.getObject("id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getString("cash_total"), rs.getString("merchant")),
                tenantId, userId, receipt.id(), new BigDecimal(receipt.cashTotal()),
                java.sql.Timestamp.from(receipt.createdAt()), java.sql.Timestamp.from(receipt.createdAt()));
        return ReceiptDuplicatePolicy.candidates(receipt.id(), receipt.createdAt(), receipt.cashTotal(), possible);
    }

    public ProductDecisionResponse allowProduct(UUID tenantId, String subject, String productKey,
                                                ProductDecisionSelection selection) {
        String key = validateProductKey(productKey);
        if (selection == null || !"allowed".equals(selection.decision())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only allowed product decisions are supported");
        }
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            requireWritePermission(tenantId, userId);
            lockMembership(tenantId, userId);
            List<ProductDecisionState> previous = productDecisionState(tenantId, userId, key, true);
            if (!previous.isEmpty() && "allowed".equals(previous.get(0).decision())) {
                ProductDecisionState current = previous.get(0);
                return new ProductDecisionResponse(key, current.decision(), current.version(), current.updatedAt());
            }
            ProductDecisionState saved;
            if (previous.isEmpty()) {
                saved = jdbc.queryForObject("""
                        INSERT INTO user_product_decisions (tenant_id, user_id, product_key, decision)
                        VALUES (?, ?, ?, 'allowed') RETURNING decision, version, updated_at
                        """, (rs, row) -> new ProductDecisionState(rs.getString("decision"), rs.getLong("version"),
                        rs.getTimestamp("updated_at").toInstant()), tenantId, userId, key);
            } else {
                saved = jdbc.queryForObject("""
                        UPDATE user_product_decisions SET decision = 'allowed', version = version + 1, updated_at = now()
                        WHERE tenant_id = ? AND user_id = ? AND product_key = ?
                        RETURNING decision, version, updated_at
                        """, (rs, row) -> new ProductDecisionState(rs.getString("decision"), rs.getLong("version"),
                        rs.getTimestamp("updated_at").toInstant()), tenantId, userId, key);
            }
            jdbc.update("""
                    INSERT INTO user_product_decision_events
                      (tenant_id, user_id, product_key, before_decision, after_decision, actor_subject, action)
                    VALUES (?, ?, ?, ?, 'allowed', ?, 'allowed')
                    """, tenantId, userId, key, previous.isEmpty() ? null : previous.get(0).decision(), subject);
            return new ProductDecisionResponse(key, saved.decision(), saved.version(), saved.updatedAt());
        });
    }

    public ProductDecisionKeys allowedProducts(UUID tenantId, String subject) {
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            List<String> keys = jdbc.query("SELECT product_key FROM user_product_decisions "
                            + "WHERE tenant_id = ? AND user_id = ? AND decision = 'allowed' ORDER BY product_key",
                    (rs, row) -> rs.getString("product_key"), tenantId, userId);
            return new ProductDecisionKeys(List.copyOf(keys));
        });
    }

    public void revokeProduct(UUID tenantId, String subject, String productKey) {
        String key = validateProductKey(productKey);
        transaction.executeWithoutResult(status -> {
            UUID userId = requireMember(tenantId, subject);
            requireWritePermission(tenantId, userId);
            lockMembership(tenantId, userId);
            List<ProductDecisionState> previous = productDecisionState(tenantId, userId, key, true);
            if (previous.isEmpty()) return;
            jdbc.update("DELETE FROM user_product_decisions WHERE tenant_id = ? AND user_id = ? AND product_key = ?",
                    tenantId, userId, key);
            jdbc.update("""
                    INSERT INTO user_product_decision_events
                      (tenant_id, user_id, product_key, before_decision, after_decision, actor_subject, action)
                    VALUES (?, ?, ?, ?, NULL, ?, 'revoked')
                    """, tenantId, userId, key, previous.get(0).decision(), subject);
        });
    }

    private List<ProductDecisionState> productDecisionState(UUID tenantId, UUID userId, String key, boolean forUpdate) {
        return jdbc.query("SELECT decision, version, updated_at FROM user_product_decisions "
                        + "WHERE tenant_id = ? AND user_id = ? AND product_key = ?" + (forUpdate ? " FOR UPDATE" : ""),
                (rs, row) -> new ProductDecisionState(rs.getString("decision"), rs.getLong("version"),
                        rs.getTimestamp("updated_at").toInstant()), tenantId, userId, key);
    }

    private void lockMembership(UUID tenantId, UUID userId) {
        List<UUID> memberships = jdbc.query("SELECT id FROM memberships WHERE tenant_id = ? AND user_id = ? FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), tenantId, userId);
        if (memberships.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
    }

    private static String validateProductKey(String value) {
        try {
            return ProductIdentityPolicy.requireProductKey(value);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product key is invalid", invalid);
        }
    }

    public ReceiptResponse addItem(UUID tenantId, String subject, UUID receiptId, long expectedVersion,
                                   ItemInput input) {
        validateVersion(expectedVersion);
        PreparedItem item = validateItem(input);
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            requireWritePermission(tenantId, userId);
            ReceiptHeader before = lockEditableReceipt(tenantId, userId, receiptId, expectedVersion);
            Integer count = jdbc.queryForObject("SELECT count(*) FROM receipt_items WHERE tenant_id = ? AND receipt_id = ?",
                    Integer.class, tenantId, receiptId);
            if (count == null || count >= 200) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt item limit reached");
            }
            Integer ordinal = jdbc.queryForObject("SELECT coalesce(max(ordinal), 0) + 1 FROM receipt_items "
                    + "WHERE tenant_id = ? AND receipt_id = ?", Integer.class, tenantId, receiptId);
            UUID itemId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO receipt_items
                      (id, tenant_id, receipt_id, ordinal, name, quantity, unit_price, line_sum, evidence)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{"provenance":"manual"}'::jsonb)
                    """, itemId, tenantId, receiptId, ordinal, item.name(), item.quantity(), item.unitPrice(), item.lineSum());
            Map<String, Object> afterItem = itemSnapshot(tenantId, receiptId, itemId);
            updateTotalsAndRecord(tenantId, userId, subject, receiptId, before, "receipt.item_added", itemId,
                    null, afterItem);
            return getWithinTransaction(tenantId, userId, receiptId);
        });
    }

    public ReceiptResponse updateItem(UUID tenantId, String subject, UUID receiptId, UUID itemId,
                                      long expectedVersion, ItemInput input) {
        validateVersion(expectedVersion);
        PreparedItem item = validateItem(input);
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            requireWritePermission(tenantId, userId);
            ReceiptHeader before = lockEditableReceipt(tenantId, userId, receiptId, expectedVersion);
            Map<String, Object> beforeItem = itemSnapshot(tenantId, receiptId, itemId, true);
            jdbc.update("""
                    UPDATE receipt_items SET name = ?, quantity = ?, unit_price = ?, line_sum = ?,
                      category_code = NULL, verdict = NULL, advice = NULL, verdict_source = 'unknown',
                      review_reason = NULL, review_action = NULL, review_provider = NULL,
                      review_model_version = NULL, review_prompt_version = NULL,
                      review_algorithm_version = 'unknown',
                      evidence = '{"provenance":"manual"}'::jsonb, version = version + 1, updated_at = now()
                    WHERE tenant_id = ? AND receipt_id = ? AND id = ?
                    """, item.name(), item.quantity(), item.unitPrice(), item.lineSum(), tenantId, receiptId, itemId);
            Map<String, Object> afterItem = itemSnapshot(tenantId, receiptId, itemId);
            updateTotalsAndRecord(tenantId, userId, subject, receiptId, before, "receipt.item_updated", itemId,
                    beforeItem, afterItem);
            return getWithinTransaction(tenantId, userId, receiptId);
        });
    }

    public void deleteItem(UUID tenantId, String subject, UUID receiptId, UUID itemId, long expectedVersion) {
        validateVersion(expectedVersion);
        transaction.executeWithoutResult(status -> {
            UUID userId = requireMember(tenantId, subject);
            requireWritePermission(tenantId, userId);
            ReceiptHeader before = lockEditableReceipt(tenantId, userId, receiptId, expectedVersion);
            Map<String, Object> beforeItem = itemSnapshot(tenantId, receiptId, itemId, true);
            jdbc.update("DELETE FROM receipt_items WHERE tenant_id = ? AND receipt_id = ? AND id = ?",
                    tenantId, receiptId, itemId);
            updateTotalsAndRecord(tenantId, userId, subject, receiptId, before, "receipt.item_deleted", itemId,
                    beforeItem, null);
        });
    }

    public ReceiptResponse syncTotal(UUID tenantId, String subject, UUID receiptId, long expectedVersion) {
        validateVersion(expectedVersion);
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            requireWritePermission(tenantId, userId);
            ReceiptHeader before = lockEditableReceipt(tenantId, userId, receiptId, expectedVersion);
            BigDecimal itemsTotal = completeItemsTotal(tenantId, receiptId);
            if (itemsTotal == null || itemsTotal.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A positive complete item total is required");
            }
            ReceiptCategoryPolicy.Result category = categoryFor(tenantId, receiptId, before, itemsTotal);
            jdbc.update("""
                    UPDATE receipts SET cash_total = ?, items_total = ?, state = 'draft', selected_reader = 'manual',
                      category_code = ?, category_source = ?, category_algorithm_version = ?,
                      alcohol_share = ?, leisure_share = ?, leisure = ?,
                      duplicate_decision = CASE WHEN cash_total IS DISTINCT FROM ? THEN 'unknown' ELSE duplicate_decision END,
                      duplicate_of_receipt_id = CASE WHEN cash_total IS DISTINCT FROM ? THEN NULL ELSE duplicate_of_receipt_id END,
                      version = version + 1, updated_at = now()
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND version = ?
                    """, itemsTotal, itemsTotal, category.category(), category.categorySource(),
                    category.algorithmVersion(), category.alcoholShare(), category.leisureShare(), category.leisure(),
                    itemsTotal, itemsTotal,
                    tenantId, userId, receiptId, expectedVersion);
            ReceiptHeader after = getHeader(tenantId, userId, receiptId, false);
            recordReview(tenantId, receiptId, subject, "receipt.cash_total_synced", null, null, null,
                    stateSnapshot(before), stateSnapshot(after));
            return getWithinTransaction(tenantId, userId, receiptId);
        });
    }

    public ReceiptResponse selectCategory(UUID tenantId, String subject, UUID receiptId, long expectedVersion,
                                          CategorySelection selection) {
        validateVersion(expectedVersion);
        if (selection == null || selection.categoryCode() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt category is required");
        }
        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            requireWritePermission(tenantId, userId);
            ReceiptHeader before = lockEditableReceipt(tenantId, userId, receiptId, expectedVersion);
            ReceiptCategoryPolicy.Result inferred = categoryFor(tenantId, receiptId, before,
                    before.cashTotal() == null ? null : new BigDecimal(before.cashTotal()));
            ReceiptCategoryPolicy.Result category;
            try {
                category = ReceiptCategoryPolicy.applyManualOverride(inferred, selection.categoryCode());
            } catch (IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt category is not allowed", invalid);
            }
            int updated = jdbc.update("""
                    UPDATE receipts SET category_code = ?, category_source = 'human',
                      category_algorithm_version = ?, alcohol_share = ?, leisure_share = ?, leisure = ?,
                      version = version + 1, updated_at = now()
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND version = ?
                    """, category.category(), category.algorithmVersion(), category.alcoholShare(),
                    category.leisureShare(), category.leisure(), tenantId, userId, receiptId, expectedVersion);
            if (updated != 1) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Receipt version is stale");
            ReceiptHeader after = getHeader(tenantId, userId, receiptId, false);
            recordReview(tenantId, receiptId, subject, "receipt.category_selected", null, null, null,
                    stateSnapshot(before), stateSnapshot(after), ReceiptCategoryPolicy.ALGORITHM_VERSION);
            return getWithinTransaction(tenantId, userId, receiptId);
        });
    }

    public ReceiptResponse reviewBasket(UUID tenantId, String subject, UUID receiptId, long expectedVersion) {
        validateVersion(expectedVersion);
        BasketSnapshot snapshot = transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            requireWritePermission(tenantId, userId);
            ReceiptHeader receipt = lockEditableReceipt(tenantId, userId, receiptId, expectedVersion);
            List<ReceiptLine> items = jdbc.query("SELECT id, name FROM receipt_items "
                            + "WHERE tenant_id = ? AND receipt_id = ? ORDER BY ordinal",
                    (rs, row) -> new ReceiptLine(rs.getObject("id", UUID.class), rs.getString("name")),
                    tenantId, receiptId);
            if (items.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Receipt has no items to review");
            }
            return new BasketSnapshot(userId, receipt, List.copyOf(items));
        });

        List<ReceiptBasketPolicy.Proposal> proposals = new ArrayList<>(snapshot.items().size());
        Map<UUID, ReceiptBasketAdvisor.Advice> provenance = new LinkedHashMap<>();
        for (int start = 0; start < snapshot.items().size(); start += 80) {
            List<ReceiptLine> batch = snapshot.items().subList(start, Math.min(start + 80, snapshot.items().size()));
            List<ReceiptBasketAdvisor.ItemName> names = new ArrayList<>(batch.size());
            for (int index = 0; index < batch.size(); index++) {
                names.add(new ReceiptBasketAdvisor.ItemName(index + 1, batch.get(index).name()));
            }
            ReceiptBasketAdvisor.Advice advice = basketAdvisor.advise(new ReceiptBasketAdvisor.Context(List.copyOf(names)));
            for (ReceiptBasketAdvisor.Suggestion suggestion : advice.items()) {
                ReceiptLine item = batch.get(suggestion.ordinal() - 1);
                proposals.add(new ReceiptBasketPolicy.Proposal(item.id(), item.name(), suggestion.verdict(),
                        suggestion.reason(), suggestion.action()));
                provenance.put(item.id(), advice);
            }
        }
        List<ReceiptBasketPolicy.Review> reviews = ReceiptBasketPolicy.apply(proposals);

        return transaction.execute(status -> {
            UUID userId = requireMember(tenantId, subject);
            requireWritePermission(tenantId, userId);
            ReceiptHeader before = lockEditableReceipt(tenantId, userId, receiptId, expectedVersion);
            if (!snapshot.userId().equals(userId) || !snapshot.items().equals(loadReceiptLines(tenantId, receiptId))) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Receipt changed during basket review");
            }
            int updated = jdbc.update("UPDATE receipts SET state = 'review_required', version = version + 1, updated_at = now() "
                            + "WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND version = ?",
                    tenantId, userId, receiptId, expectedVersion);
            if (updated != 1) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Receipt version is stale");
            }
            ReceiptHeader after = getHeader(tenantId, userId, receiptId, false);
            Map<String, Object> beforeState = stateSnapshot(before);
            Map<String, Object> afterState = stateSnapshot(after);
            for (ReceiptBasketPolicy.Review review : reviews) {
                Map<String, Object> beforeItem = itemSnapshot(tenantId, receiptId, review.itemId(), true);
                ReceiptBasketAdvisor.Advice source = provenance.get(review.itemId());
                String adviceText = adviceText(review.reason(), review.action());
                int itemUpdated = jdbc.update("""
                        UPDATE receipt_items SET verdict = ?, advice = ?, review_reason = ?, review_action = ?,
                          verdict_source = ?, review_provider = ?, review_model_version = ?, review_prompt_version = ?,
                          review_algorithm_version = ?, version = version + 1, updated_at = now()
                        WHERE tenant_id = ? AND receipt_id = ? AND id = ?
                        """, review.verdict(), adviceText, emptyToNull(review.reason()), emptyToNull(review.action()),
                        review.source(), source.provider(), source.modelVersion(), source.promptVersion(),
                        ReceiptBasketPolicy.ALGORITHM_VERSION, tenantId, receiptId, review.itemId());
                if (itemUpdated != 1) {
                    throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Receipt item changed during basket review");
                }
                Map<String, Object> afterItem = itemSnapshot(tenantId, receiptId, review.itemId());
                recordReview(tenantId, receiptId, subject, "receipt.item_reviewed", review.itemId(), beforeItem,
                        afterItem, beforeState, afterState, review.source(), ReceiptBasketPolicy.ALGORITHM_VERSION);
            }
            return getWithinTransaction(tenantId, userId, receiptId);
        });
    }

    private List<ReceiptLine> loadReceiptLines(UUID tenantId, UUID receiptId) {
        return jdbc.query("SELECT id, name FROM receipt_items WHERE tenant_id = ? AND receipt_id = ? ORDER BY ordinal",
                (rs, row) -> new ReceiptLine(rs.getObject("id", UUID.class), rs.getString("name")), tenantId, receiptId);
    }

    static PreparedReceipt validateRequest(CreateRequest request) {
        if (request == null || request.items() == null || request.items().size() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid receipt fields");
        }
        String merchant = request.merchant() == null ? null : request.merchant().trim();
        if (merchant != null && (merchant.isEmpty() || merchant.length() > 200)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid receipt merchant");
        }
        BigDecimal cashTotal = decimal(request.cashTotal(), 2, 18, true, "cashTotal");
        List<PreparedItem> items = new ArrayList<>(request.items().size());
        BigDecimal itemsTotal = BigDecimal.ZERO.setScale(2);
        boolean allLineSumsPresent = !request.items().isEmpty();
        for (ItemInput item : request.items()) {
            if (item == null || item.name() == null || item.name().trim().isEmpty()
                    || item.name().trim().length() > 200) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid receipt item name");
            }
            PreparedItem preparedItem = validateItem(item);
            BigDecimal lineSum = preparedItem.lineSum();
            if (lineSum == null) allLineSumsPresent = false;
            else itemsTotal = itemsTotal.add(lineSum);
            items.add(preparedItem);
        }
        if (itemsTotal.compareTo(MAX_MONEY) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt item total is too large");
        }
        if (!allLineSumsPresent) itemsTotal = null;
        String state = ReceiptReconciliationPolicy.totalsReconcile(cashTotal, itemsTotal)
                ? "draft" : "review_required";
        return new PreparedReceipt(request.documentId(), cashTotal, itemsTotal, merchant, request.receiptDate(),
                List.copyOf(items), state);
    }

    static PreparedItem validateItem(ItemInput item) {
        if (item == null || item.name() == null || item.name().trim().isEmpty()
                || item.name().trim().length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid receipt item name");
        }
        BigDecimal quantity = decimal(item.quantity(), 6, 12, true, "quantity");
        BigDecimal unitPrice = decimal(item.unitPrice(), 2, 18, false, "unitPrice");
        BigDecimal lineSum = decimal(item.lineSum(), 2, 18, false, "lineSum");
        return new PreparedItem(item.name().trim(), quantity, unitPrice, lineSum);
    }

    private ReceiptResponse findByCreateKey(UUID tenantId, UUID userId, String key, String hash) {
        List<ReceiptKey> rows = jdbc.query("""
                SELECT id, create_request_hash FROM receipts
                WHERE tenant_id = ? AND owner_user_id = ? AND create_idempotency_key = ?
                """, (rs, row) -> new ReceiptKey(rs.getObject("id", UUID.class), rs.getString("create_request_hash")),
                tenantId, userId, key);
        if (rows.isEmpty()) return null;
        ReceiptKey saved = rows.get(0);
        if (!hash.equals(saved.hash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was reused with another receipt");
        }
        return getWithinTransaction(tenantId, userId, saved.id());
    }

    private ReceiptHeader lockEditableReceipt(UUID tenantId, UUID userId, UUID receiptId, long expectedVersion) {
        ReceiptHeader receipt = getHeader(tenantId, userId, receiptId, true);
        if (!"draft".equals(receipt.state()) && !"review_required".equals(receipt.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Receipt is not editable");
        }
        if (receipt.version() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Receipt version is stale");
        }
        return receipt;
    }

    private ReceiptHeader getHeader(UUID tenantId, UUID userId, UUID receiptId, boolean forUpdate) {
        List<ReceiptHeader> rows = jdbc.query("SELECT " + HEADER_COLUMNS + " FROM receipts "
                        + "WHERE tenant_id = ? AND owner_user_id = ? AND id = ?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                HEADER_MAPPER, tenantId, userId, receiptId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
        return rows.get(0);
    }

    private Map<String, Object> itemSnapshot(UUID tenantId, UUID receiptId, UUID itemId) {
        return itemSnapshot(tenantId, receiptId, itemId, false);
    }

    private Map<String, Object> itemSnapshot(UUID tenantId, UUID receiptId, UUID itemId, boolean forUpdate) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT id, name, quantity::text AS quantity, unit_price::text AS unit_price,
                       line_sum::text AS line_sum, category_code, verdict, advice, verdict_source,
                       review_reason, review_action, review_provider, review_model_version,
                       review_prompt_version, review_algorithm_version,
                       evidence ->> 'provenance' AS provenance, version
                FROM receipt_items WHERE tenant_id = ? AND receipt_id = ? AND id = ?
                """ + (forUpdate ? " FOR UPDATE" : ""), (rs, row) -> {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("id", rs.getObject("id", UUID.class));
            snapshot.put("name", rs.getString("name"));
            snapshot.put("quantity", rs.getString("quantity"));
            snapshot.put("unitPrice", rs.getString("unit_price"));
            snapshot.put("lineSum", rs.getString("line_sum"));
            snapshot.put("categoryCode", rs.getString("category_code"));
            snapshot.put("verdict", rs.getString("verdict"));
            snapshot.put("advice", rs.getString("advice"));
            snapshot.put("verdictSource", rs.getString("verdict_source"));
            snapshot.put("reviewReason", rs.getString("review_reason"));
            snapshot.put("reviewAction", rs.getString("review_action"));
            snapshot.put("reviewProvider", rs.getString("review_provider"));
            snapshot.put("reviewModelVersion", rs.getString("review_model_version"));
            snapshot.put("reviewPromptVersion", rs.getString("review_prompt_version"));
            snapshot.put("reviewAlgorithmVersion", rs.getString("review_algorithm_version"));
            snapshot.put("provenance", rs.getString("provenance"));
            snapshot.put("version", rs.getLong("version"));
            return snapshot;
        }, tenantId, receiptId, itemId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt item not found");
        return rows.get(0);
    }

    private void updateTotalsAndRecord(UUID tenantId, UUID userId, String subject, UUID receiptId,
                                       ReceiptHeader before, String action, UUID itemId,
                                       Map<String, Object> beforeItem, Map<String, Object> afterItem) {
        BigDecimal itemsTotal = completeItemsTotal(tenantId, receiptId);
        BigDecimal cashTotal = before.cashTotal() == null ? null : new BigDecimal(before.cashTotal());
        ReceiptCategoryPolicy.Result category = categoryFor(tenantId, receiptId, before, cashTotal);
        String newState = ReceiptReconciliationPolicy.totalsReconcile(cashTotal, itemsTotal)
                ? "draft" : "review_required";
        int updated = jdbc.update("""
                UPDATE receipts SET items_total = ?, state = ?, selected_reader = 'manual',
                  category_code = ?, category_source = ?, category_algorithm_version = ?,
                  alcohol_share = ?, leisure_share = ?, leisure = ?,
                  version = version + 1, updated_at = now()
                WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND version = ?
                """, itemsTotal, newState, category.category(), category.categorySource(), category.algorithmVersion(),
                category.alcoholShare(), category.leisureShare(), category.leisure(),
                tenantId, userId, receiptId, before.version());
        if (updated != 1) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Receipt version is stale");
        ReceiptHeader after = getHeader(tenantId, userId, receiptId, false);
        recordReview(tenantId, receiptId, subject, action, itemId, beforeItem, afterItem,
                stateSnapshot(before), stateSnapshot(after));
    }

    private BigDecimal completeItemsTotal(UUID tenantId, UUID receiptId) {
        List<ItemTotal> rows = jdbc.query("""
                SELECT count(*) AS item_count, count(line_sum) AS known_count, sum(line_sum) AS total
                FROM receipt_items WHERE tenant_id = ? AND receipt_id = ?
                """, (rs, row) -> new ItemTotal(rs.getInt("item_count"), rs.getInt("known_count"),
                rs.getBigDecimal("total")), tenantId, receiptId);
        if (rows.isEmpty()) return null;
        ItemTotal total = rows.get(0);
        if (total.itemCount() == 0 || total.knownCount() != total.itemCount() || total.total() == null) return null;
        if (total.total().compareTo(MAX_MONEY) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt item total is too large");
        }
        return total.total();
    }

    private ReceiptCategoryPolicy.Result categoryFor(UUID tenantId, UUID receiptId, ReceiptHeader receipt,
                                                     BigDecimal cashTotal) {
        String suggested = "human".equals(receipt.categorySource()) ? null : receipt.categoryCode();
        List<ReceiptCategoryPolicy.Item> items = jdbc.query("""
                SELECT name, line_sum FROM receipt_items WHERE tenant_id = ? AND receipt_id = ? ORDER BY ordinal
                """, (rs, row) -> new ReceiptCategoryPolicy.Item(rs.getString("name"), rs.getBigDecimal("line_sum")),
                tenantId, receiptId);
        ReceiptCategoryPolicy.Result inferred = ReceiptCategoryPolicy.classify(suggested, cashTotal, items);
        if ("human".equals(receipt.categorySource())) {
            return ReceiptCategoryPolicy.applyManualOverride(inferred, receipt.categoryCode());
        }
        return inferred;
    }

    private static ReceiptCategoryPolicy.Result classifyPrepared(PreparedReceipt receipt) {
        List<ReceiptCategoryPolicy.Item> items = receipt.items().stream()
                .map(item -> new ReceiptCategoryPolicy.Item(item.name(), item.lineSum())).toList();
        return ReceiptCategoryPolicy.classify(null, receipt.cashTotal(), items);
    }

    private void recordReview(UUID tenantId, UUID receiptId, String subject, String action, UUID itemId,
                              Map<String, Object> beforeItem, Map<String, Object> afterItem,
                              Map<String, Object> beforeState, Map<String, Object> afterState) {
        recordReview(tenantId, receiptId, subject, action, itemId, beforeItem, afterItem, beforeState, afterState,
                ReceiptReconciliationPolicy.ALGORITHM_VERSION);
    }

    private void recordReview(UUID tenantId, UUID receiptId, String subject, String action, UUID itemId,
                              Map<String, Object> beforeItem, Map<String, Object> afterItem,
                              Map<String, Object> beforeState, Map<String, Object> afterState, String algorithmVersion) {
        recordReview(tenantId, receiptId, subject, action, itemId, beforeItem, afterItem, beforeState, afterState,
                "human", algorithmVersion);
    }

    private void recordReview(UUID tenantId, UUID receiptId, String subject, String action, UUID itemId,
                              Map<String, Object> beforeItem, Map<String, Object> afterItem,
                              Map<String, Object> beforeState, Map<String, Object> afterState,
                              String verdictSource, String algorithmVersion) {
        Map<String, Object> itemSnapshot = new LinkedHashMap<>();
        itemSnapshot.put("before", beforeItem);
        itemSnapshot.put("after", afterItem);
        jdbc.update("""
                INSERT INTO receipt_reviews
                  (tenant_id, receipt_id, receipt_item_id, item_snapshot, actor_subject, action,
                   before_state, after_state, verdict_source, algorithm_version)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                """, tenantId, receiptId, itemId, serialize(itemSnapshot), subject, action,
                serialize(beforeState), serialize(afterState), verdictSource, algorithmVersion);
    }

    private static Map<String, Object> stateSnapshot(ReceiptHeader receipt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("state", receipt.state());
        snapshot.put("version", receipt.version());
        snapshot.put("cashTotal", receipt.cashTotal());
        snapshot.put("itemsTotal", receipt.itemsTotal());
        snapshot.put("selectedReader", receipt.selectedReader());
        snapshot.put("categoryCode", receipt.categoryCode());
        snapshot.put("categorySource", receipt.categorySource());
        snapshot.put("categoryAlgorithmVersion", receipt.categoryAlgorithmVersion());
        snapshot.put("alcoholShare", receipt.alcoholShare());
        snapshot.put("leisureShare", receipt.leisureShare());
        snapshot.put("leisure", receipt.leisure());
        snapshot.put("duplicateDecision", receipt.duplicateDecision());
        snapshot.put("duplicateOfReceiptId", receipt.duplicateOfReceiptId());
        snapshot.put("transactionId", receipt.transactionId());
        return snapshot;
    }

    private ReceiptResponse getWithinTransaction(UUID tenantId, UUID userId, UUID receiptId) {
        List<ReceiptHeader> rows = jdbc.query("""
                SELECT %s FROM receipts
                WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
                """.formatted(HEADER_COLUMNS), HEADER_MAPPER, tenantId, userId, receiptId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found");
        ReceiptHeader receipt = rows.get(0);
        Integer itemCount = jdbc.queryForObject("SELECT count(*) FROM receipt_items WHERE tenant_id = ? AND receipt_id = ?",
                Integer.class, tenantId, receiptId);
        List<ReceiptItem> items = jdbc.query("SELECT " + ITEM_COLUMNS + " FROM receipt_items "
                        + "WHERE tenant_id = ? AND receipt_id = ? ORDER BY ordinal LIMIT 8",
                ITEM_MAPPER, tenantId, receiptId);
        return new ReceiptResponse(receipt.id(), receipt.tenantId(), receipt.documentId(), receipt.state(),
                receipt.version(), receipt.transactionId(), receipt.currency(), receipt.cashTotal(), receipt.itemsTotal(), receipt.merchant(),
                receipt.receiptDate(), receipt.selectedReader(), receipt.categoryCode(), receipt.categorySource(),
                receipt.categoryAlgorithmVersion(), receipt.alcoholShare(), receipt.leisureShare(), receipt.leisure(),
                receipt.duplicateDecision(), receipt.duplicateOfReceiptId(), List.copyOf(items), itemCount == null ? 0 : itemCount,
                receipt.createdAt());
    }

    private UUID requireMember(UUID tenantId, String subject) {
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        }
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        List<UUID> ids = jdbc.query("""
                SELECT i.user_id FROM external_identities i
                JOIN memberships m ON m.tenant_id = ? AND m.user_id = i.user_id
                WHERE i.provider = 'keycloak' AND i.subject = ? AND m.status = 'active'
                """, (rs, row) -> rs.getObject("user_id", UUID.class), tenantId, subject);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        return ids.get(0);
    }

    private void requireWritePermission(UUID tenantId, UUID userId) {
        List<String> roles = jdbc.query("SELECT role FROM memberships WHERE tenant_id = ? AND user_id = ? AND status = 'active'",
                (rs, row) -> rs.getString("role"), tenantId, userId);
        if (roles.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        if ("viewer".equals(roles.get(0))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Viewer access is read-only");
        }
    }

    private void requireReadyDocument(UUID tenantId, UUID userId, UUID documentId) {
        List<String> states = jdbc.query("""
                SELECT scan_state FROM documents
                WHERE tenant_id = ? AND uploaded_by_user_id = ? AND id = ?
                """, (rs, row) -> rs.getString("scan_state"), tenantId, userId, documentId);
        if (states.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt document not found");
        if (!"ready".equals(states.get(0))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Receipt document is not ready for review");
        }
    }

    private static BigDecimal decimal(String value, int scale, int integerDigits, boolean positive, String field) {
        if (value == null) return null;
        String pattern = "^(?:0|[1-9][0-9]{0," + (integerDigits - 1) + "})(?:\\.[0-9]{1," + scale + "})?$";
        if (!value.matches(pattern)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid receipt " + field);
        BigDecimal parsed = new BigDecimal(value);
        if (positive && parsed.signum() <= 0 || parsed.scale() > scale) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid receipt " + field);
        }
        return parsed;
    }

    private static void validateVersion(long expectedVersion) {
        if (expectedVersion < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid receipt version");
        }
    }

    private static void validatePage(int page) {
        if (page < 1 || page > 1_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid receipt item page");
        }
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Cannot serialize receipt", ex);
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid idempotency key");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String adviceText(String reason, String action) {
        if (reason == null || reason.isBlank()) return emptyToNull(action);
        if (action == null || action.isBlank()) return reason;
        String text = reason + " — " + action;
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record PreparedReceipt(UUID documentId, BigDecimal cashTotal, BigDecimal itemsTotal, String merchant,
                           LocalDate receiptDate, List<PreparedItem> items, String state) {}

    record PreparedItem(String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineSum) {}

    private record ReceiptKey(UUID id, String hash) {}

    private record ItemTotal(int itemCount, int knownCount, BigDecimal total) {}
    private record ReceiptLine(UUID id, String name) {}
    private record BasketSnapshot(UUID userId, ReceiptHeader receipt, List<ReceiptLine> items) {}
    private record ProductDecisionState(String decision, long version, Instant updatedAt) {}

    private record ReceiptHeader(UUID id, UUID tenantId, UUID documentId, String state, long version, UUID transactionId,
                                 String currency, String cashTotal, String itemsTotal, String merchant,
                                 LocalDate receiptDate, String selectedReader, String categoryCode,
                                 String categorySource, String categoryAlgorithmVersion, String alcoholShare,
                                 String leisureShare, boolean leisure, String duplicateDecision,
                                 UUID duplicateOfReceiptId, Instant createdAt) {}
}
