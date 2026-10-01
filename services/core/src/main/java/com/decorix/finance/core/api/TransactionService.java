package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TransactionApi.CreateRequest;
import com.decorix.finance.core.api.TransactionApi.Page;
import com.decorix.finance.core.api.TransactionApi.TransactionResponse;
import com.decorix.finance.core.domain.MoneyAmount;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TransactionService {
    private static final List<String> TYPES = List.of("expense", "income", "refund", "debt_payment", "transfer");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public TransactionService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
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
                return replay(tenantId, subject, key, requestHash);
            }

            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO transactions
                      (id, tenant_id, owner_subject, owner_user_id, account_id, type, amount, currency, category_code,
                       subcategory_code, description, source, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'web', ?)
                    """, id, tenantId, subject, userId, request.accountId(), request.type(),
                    MoneyAmount.parse(request.amount()).value(),
                    request.currency(), request.categoryCode(),
                    request.subcategoryCode(), request.description(), Timestamp.from(request.occurredAt()));

            TransactionResponse response = new TransactionResponse(id, tenantId, request.type(),
                    MoneyAmount.parse(request.amount()).toString(), request.currency(), request.categoryCode(),
                    request.subcategoryCode(), request.description(), request.occurredAt(), request.accountId(),
                    "posted", 1, now);
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
                            "owner_user_id", userId,
                            "type", request.type(),
                            "amount", MoneyAmount.parse(request.amount()).toString(),
                            "currency", request.currency(),
                            "category_code", request.categoryCode(),
                            "description", request.description(),
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

    public Page list(UUID tenantId, String subject, int pageSize, String cursor) {
        if (pageSize < 1 || pageSize > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pageSize must be from 1 to 200");
        }
        Cursor after = decodeCursor(cursor);
        return transaction.execute(status -> {
            setTenantContext(tenantId);
            UUID userId = resolveUser(subject);
            if (userId == null || !isMember(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
            }
            String query = """
                    SELECT id, tenant_id, type, amount::text, currency, category_code, subcategory_code,
                           description, occurred_at, account_id, status, version, created_at
                    FROM transactions WHERE tenant_id = ? AND owner_user_id = ?
                    """;
            List<Object> args = new ArrayList<>(List.of(tenantId, userId));
            if (after != null) {
                query += " AND (occurred_at, id) < (?, ?)";
                args.add(Timestamp.from(after.occurredAt()));
                args.add(after.id());
            }
            query += " ORDER BY occurred_at DESC, id DESC LIMIT ?";
            args.add(pageSize + 1);
            List<TransactionResponse> items = jdbc.query(query, (rs, row) -> new TransactionResponse(
                    rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("type"),
                    rs.getString("amount"), rs.getString("currency"), rs.getString("category_code"),
                    rs.getString("subcategory_code"), rs.getString("description"), rs.getTimestamp("occurred_at").toInstant(),
                    rs.getObject("account_id", UUID.class), rs.getString("status"), rs.getLong("version"),
                    rs.getTimestamp("created_at").toInstant()), args.toArray());
            String nextCursor = null;
            if (items.size() > pageSize) {
                items = new ArrayList<>(items.subList(0, pageSize));
                nextCursor = encodeCursor(items.get(items.size() - 1));
            }
            return new Page(items, nextCursor);
        });
    }

    private TransactionResponse replay(UUID tenantId, String subject, String key, String requestHash) {
        var existing = jdbc.queryForMap("SELECT request_hash, response_body::text FROM idempotency_records WHERE tenant_id = ? AND actor_subject = ? AND route = '/transactions' AND idempotency_key = ?",
                tenantId, subject, key);
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

    private static void validate(UUID tenantId, String subject, String key, CreateRequest request) {
        if (tenantId == null || subject == null || subject.isBlank() || key == null || key.length() < 16 || key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tenant, subject or idempotency key");
        }
        if (request == null || !TYPES.contains(request.type()) || !"RUB".equals(request.currency())
                || request.categoryCode() == null || request.categoryCode().isBlank() || request.categoryCode().length() > 64
                || request.subcategoryCode() != null && request.subcategoryCode().length() > 64
                || request.description() == null || request.description().length() > 500 || request.occurredAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction fields");
        }
        try {
            MoneyAmount.parse(request.amount());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid positive decimal amount", ex);
        }
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
