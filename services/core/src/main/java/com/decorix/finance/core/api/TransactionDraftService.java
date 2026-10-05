package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TransactionApi.CreateRequest;
import com.decorix.finance.core.api.TransactionApi.TransactionResponse;
import com.decorix.finance.core.api.DebtApi.DebtResponse;
import com.decorix.finance.core.api.DebtApi.PaymentRequest;
import com.decorix.finance.core.api.DebtApi.PaymentResponse;
import com.decorix.finance.core.api.TransactionDraftApi.DraftResponse;
import com.decorix.finance.core.api.TransactionDraftApi.UpdateRequest;
import com.decorix.finance.core.domain.MoneyAmount;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TransactionDraftService {
    private static final RowMapper<DraftResponse> DRAFT_MAPPER = (rs, row) -> new DraftResponse(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("type"),
            rs.getString("amount"), rs.getString("currency"), rs.getString("category_code"),
            rs.getString("subcategory_code"), rs.getString("description"), rs.getTimestamp("occurred_at").toInstant(),
            rs.getObject("debt_id", UUID.class), rs.getString("state"), rs.getLong("version"), rs.getString("provider"),
            rs.getString("model_version"), rs.getString("prompt_version"), rs.getTimestamp("created_at").toInstant());
    private static final String DRAFT_COLUMNS = "id, tenant_id, type, amount::text, currency, category_code, "
            + "subcategory_code, description, occurred_at, debt_id, state, version, provider, model_version, prompt_version, created_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MemberProfileService profiles;
    private final TransactionDraftAdvisor advisor;
    private final TransactionService transactions;
    private final DebtService debts;

    public TransactionDraftService(JdbcTemplate jdbc, TransactionTemplate transaction, MemberProfileService profiles,
                                   TransactionDraftAdvisor advisor, TransactionService transactions, DebtService debts) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.profiles = profiles;
        this.advisor = advisor;
        this.transactions = transactions;
        this.debts = debts;
    }

    public DraftResponse create(UUID tenantId, String subject, String key, TransactionDraftApi.CreateRequest request) {
        validateKey(key);
        if (tenantId == null || subject == null || subject.isBlank() || request == null || request.text() == null
                || request.text().isBlank() || request.text().trim().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction draft text");
        }
        String text = request.text().trim();
        String hash = sha256(text);
        MemberProfileApi.ProfileResponse profile = profiles.get(tenantId, subject);
        DraftResponse existing = transaction.execute(status -> {
            setContext(tenantId, subject);
            UUID userId = userId(subject);
            return findByCreateKey(tenantId, userId, key, hash);
        });
        if (existing != null) return existing;

        String now = OffsetDateTime.now(ZoneId.of(profile.timezone())).toString();
        TransactionDraftAdvisor.Advice advice = advisor.advise(
                new TransactionDraftAdvisor.Context(text, profile.timezone(), now));
        validateAdvice(advice);

        return transaction.execute(status -> {
            setContext(tenantId, subject);
            UUID userId = userId(subject);
            DraftResponse duplicate = findByCreateKey(tenantId, userId, key, hash);
            if (duplicate != null) return duplicate;
            UUID id = UUID.randomUUID();
            List<UUID> inserted = jdbc.query("""
                    INSERT INTO transaction_drafts
                      (id, tenant_id, owner_user_id, owner_subject, type, amount, currency, category_code,
                       subcategory_code, description, occurred_at, provider, model_version, prompt_version,
                       create_idempotency_key, create_request_hash)
                    VALUES (?, ?, ?, ?, ?, ?, 'RUB', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, owner_user_id, create_idempotency_key) DO NOTHING RETURNING id
                    """, (rs, row) -> rs.getObject("id", UUID.class), id, tenantId, userId, subject,
                    advice.type(), MoneyAmount.parse(advice.amount()).value(),
                    advice.categoryCode(), advice.subcategoryCode(), advice.description(),
                    Timestamp.from(OffsetDateTime.parse(advice.occurredAt()).toInstant()), advice.provider(),
                    advice.modelVersion(), advice.promptVersion(), key, hash);
            if (inserted.isEmpty()) {
                DraftResponse raced = findByCreateKey(tenantId, userId, key, hash);
                if (raced != null) return raced;
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft could not be created");
            }
            return getWithinTransaction(tenantId, userId, id, false);
        });
    }

    /** Build a reviewable, AI-free draft from one of the member's posted expenses. */
    public DraftResponse repeat(UUID tenantId, String subject, String key, UUID sourceTransactionId) {
        validateKey(key);
        if (tenantId == null || subject == null || subject.isBlank() || sourceTransactionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid repeat transaction request");
        }
        String hash = sha256("repeat|" + sourceTransactionId);
        return transaction.execute(status -> {
            setContext(tenantId, subject);
            UUID userId = userId(subject);
            DraftResponse existing = findByCreateKey(tenantId, userId, key, hash);
            if (existing != null) return existing;
            List<RepeatSource> sources = jdbc.query("""
                    SELECT type, amount::text, currency, category_code, subcategory_code, description
                    FROM transactions
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND status = 'posted' AND type = 'expense'
                    FOR SHARE
                    """, (rs, row) -> new RepeatSource(rs.getString("type"), rs.getString("amount"),
                    rs.getString("currency"), rs.getString("category_code"), rs.getString("subcategory_code"),
                    rs.getString("description")), tenantId, userId, sourceTransactionId);
            if (sources.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found");
            RepeatSource source = sources.get(0);
            UUID id = UUID.randomUUID();
            List<UUID> inserted = jdbc.query("""
                    INSERT INTO transaction_drafts
                      (id, tenant_id, owner_user_id, owner_subject, type, amount, currency, category_code,
                       subcategory_code, description, occurred_at, provider, model_version, prompt_version,
                       create_idempotency_key, create_request_hash)
                    VALUES (?, ?, ?, ?, 'expense', ?, ?, ?, ?, ?, now(), 'manual', 'repeat',
                            'transaction-repeat.v1', ?, ?)
                    ON CONFLICT (tenant_id, owner_user_id, create_idempotency_key) DO NOTHING RETURNING id
                    """, (rs, row) -> rs.getObject("id", UUID.class), id, tenantId, userId, subject,
                    MoneyAmount.parse(source.amount()).value(), source.currency(), source.categoryCode(),
                    source.subcategoryCode(), source.description(), key, hash);
            if (inserted.isEmpty()) {
                DraftResponse raced = findByCreateKey(tenantId, userId, key, hash);
                if (raced != null) return raced;
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Repeat draft could not be created");
            }
            return getWithinTransaction(tenantId, userId, id, false);
        });
    }

    public DraftResponse get(UUID tenantId, String subject, UUID id) {
        return transaction.execute(status -> {
            setContext(tenantId, subject);
            UUID userId = userId(subject);
            return getWithinTransaction(tenantId, userId, id, false);
        });
    }

    public DraftResponse update(UUID tenantId, String subject, UUID id, long expectedVersion, UpdateRequest request) {
        validateUpdate(request);
        if (expectedVersion < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid draft version");
        return transaction.execute(status -> {
            setContext(tenantId, subject);
            UUID userId = userId(subject);
            DraftResponse before = getWithinTransaction(tenantId, userId, id, true);
            requirePending(before, expectedVersion);
            if (request.debtId() != null && jdbc.queryForObject("SELECT count(*) FROM debts WHERE tenant_id = ? AND id = ?",
                    Integer.class, tenantId, request.debtId()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Debt not found");
            }
            jdbc.update("""
                    UPDATE transaction_drafts SET type = ?, amount = ?, currency = ?, category_code = ?,
                      subcategory_code = ?, description = ?, occurred_at = ?, debt_id = ?, version = version + 1, updated_at = now()
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND version = ? AND state = 'pending'
                    """, request.type(), MoneyAmount.parse(request.amount()).value(), request.currency(),
                    request.categoryCode().trim(), request.subcategoryCode() == null ? null : request.subcategoryCode().trim(),
                    request.description().trim(), Timestamp.from(request.occurredAt()), request.debtId(), tenantId, userId, id, expectedVersion);
            return getWithinTransaction(tenantId, userId, id, false);
        });
    }

    public DraftResponse updateAmount(UUID tenantId, String subject, UUID id, long expectedVersion, String amount) {
        if (expectedVersion < 1 || amount == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid draft amount update");
        }
        var parsedAmount = MoneyAmount.parse(amount).value();
        return transaction.execute(status -> {
            setContext(tenantId, subject);
            UUID userId = userId(subject);
            DraftResponse before = getWithinTransaction(tenantId, userId, id, true);
            requirePending(before, expectedVersion);
            jdbc.update("UPDATE transaction_drafts SET amount = ?, version = version + 1, updated_at = now() "
                            + "WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND version = ? AND state = 'pending'",
                    parsedAmount, tenantId, userId, id, expectedVersion);
            return getWithinTransaction(tenantId, userId, id, false);
        });
    }

    public TransactionResponse confirm(UUID tenantId, String subject, UUID id, String key, long expectedVersion) {
        validateKey(key);
        if (expectedVersion < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid draft version");
        return transaction.execute(status -> {
            setContext(tenantId, subject);
            UUID userId = userId(subject);
            DraftResponse draft = getWithinTransaction(tenantId, userId, id, true);
            String state = state(tenantId, userId, id);
            if ("confirmed".equals(state)) {
                String savedKey = jdbc.queryForObject("SELECT confirm_idempotency_key FROM transaction_drafts WHERE tenant_id = ? AND owner_user_id = ? AND id = ?",
                        String.class, tenantId, userId, id);
                if (!key.equals(savedKey)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft was already confirmed");
                UUID transactionId = jdbc.queryForObject("SELECT transaction_id FROM transaction_drafts WHERE tenant_id = ? AND owner_user_id = ? AND id = ?",
                        UUID.class, tenantId, userId, id);
                return transactions.get(tenantId, subject, transactionId);
            }
            requirePending(draft, expectedVersion);
            String transactionKey = "draft-" + sha256(tenantId + "|" + id + "|" + key).substring(0, 48);
            TransactionResponse posted;
            if ("debt_payment".equals(draft.type())) {
                if (draft.debtId() == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a debt before confirming payment");
                DebtResponse debt = debts.get(tenantId, subject, draft.debtId());
                PaymentResponse payment = debts.pay(tenantId, subject, draft.debtId(), transactionKey, debt.version(),
                        new PaymentRequest(draft.amount(), draft.occurredAt()));
                posted = transactions.get(tenantId, subject, payment.transactionId());
            } else {
                CreateRequest create = new CreateRequest(draft.type(), draft.amount(), draft.currency(), draft.categoryCode(),
                        draft.subcategoryCode(), draft.description(), "transaction-repeat.v1".equals(draft.promptVersion())
                                ? "repeat" : "text_ai", draft.occurredAt(), null, null);
                posted = transactions.create(tenantId, subject, transactionKey, create);
            }
            jdbc.update("""
                    UPDATE transaction_drafts SET state = 'confirmed', transaction_id = ?, confirm_idempotency_key = ?,
                      version = version + 1, updated_at = now()
                    WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND state = 'pending' AND version = ?
                    """, posted.id(), key, tenantId, userId, id, expectedVersion);
            return posted;
        });
    }

    public void cancel(UUID tenantId, String subject, UUID id, long expectedVersion) {
        if (expectedVersion < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid draft version");
        transaction.executeWithoutResult(status -> {
            setContext(tenantId, subject);
            UUID userId = userId(subject);
            DraftResponse draft = getWithinTransaction(tenantId, userId, id, true);
            requirePending(draft, expectedVersion);
            jdbc.update("UPDATE transaction_drafts SET state = 'cancelled', version = version + 1, updated_at = now() "
                            + "WHERE tenant_id = ? AND owner_user_id = ? AND id = ? AND version = ? AND state = 'pending'",
                    tenantId, userId, id, expectedVersion);
        });
    }

    private DraftResponse findByCreateKey(UUID tenantId, UUID userId, String key, String hash) {
        List<DraftWithHash> rows = jdbc.query("SELECT " + DRAFT_COLUMNS + ", create_request_hash FROM transaction_drafts "
                        + "WHERE tenant_id = ? AND owner_user_id = ? AND create_idempotency_key = ?",
                (rs, row) -> new DraftWithHash(DRAFT_MAPPER.mapRow(rs, row), rs.getString("create_request_hash")),
                tenantId, userId, key);
        if (rows.isEmpty()) return null;
        DraftWithHash saved = rows.get(0);
        if (!hash.equals(saved.hash())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was reused with different text");
        return saved.draft();
    }

    private DraftResponse getWithinTransaction(UUID tenantId, UUID userId, UUID id, boolean forUpdate) {
        List<DraftResponse> rows = jdbc.query("SELECT " + DRAFT_COLUMNS + " FROM transaction_drafts "
                        + "WHERE tenant_id = ? AND owner_user_id = ? AND id = ?" + (forUpdate ? " FOR UPDATE" : ""),
                DRAFT_MAPPER, tenantId, userId, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction draft not found");
        return rows.get(0);
    }

    private String state(UUID tenantId, UUID userId, UUID id) {
        return jdbc.queryForObject("SELECT state FROM transaction_drafts WHERE tenant_id = ? AND owner_user_id = ? AND id = ?",
                String.class, tenantId, userId, id);
    }

    private UUID userId(String subject) {
        List<UUID> ids = jdbc.query("SELECT user_id FROM external_identities WHERE provider = 'keycloak' AND subject = ?",
                (rs, row) -> rs.getObject(1, UUID.class), subject);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        return ids.get(0);
    }

    private void setContext(UUID tenantId, String subject) {
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        }
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
        if (jdbc.queryForObject("SELECT count(*) FROM memberships WHERE tenant_id = ? AND subject = ? AND status = 'active'",
                Integer.class, tenantId, subject) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        }
        String role = jdbc.queryForObject("SELECT role FROM memberships WHERE tenant_id = ? AND subject = ? AND status = 'active'",
                String.class, tenantId, subject);
        if ("viewer".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Viewer access is read-only");
        }
    }

    private static void requirePending(DraftResponse draft, long expectedVersion) {
        if (!"pending".equals(draft.state())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft is not pending");
        if (draft.version() != expectedVersion) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Draft version is stale");
    }

    private static void validateAdvice(TransactionDraftAdvisor.Advice advice) {
        if (advice == null || !("expense".equals(advice.type()) || "income".equals(advice.type()) || "debt_payment".equals(advice.type()))
                || advice.categoryCode() == null || advice.categoryCode().isBlank() || advice.categoryCode().length() > 64
                || "debt_payment".equals(advice.type()) && !"долги".equals(advice.categoryCode())
                || advice.subcategoryCode() != null && (advice.subcategoryCode().isBlank() || advice.subcategoryCode().length() > 64)
                || advice.description() == null || advice.description().length() > 500
                || advice.provider() == null || advice.provider().isBlank()
                || advice.provider().length() > 64
                || advice.modelVersion() == null || advice.modelVersion().isBlank()
                || advice.modelVersion().length() > 128
                || advice.promptVersion() == null || advice.promptVersion().isBlank()
                || advice.promptVersion().length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Transaction AI response failed validation");
        }
        try {
            MoneyAmount.parse(advice.amount());
            OffsetDateTime.parse(advice.occurredAt());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Transaction AI response failed validation", ex);
        }
    }

    private static void validateUpdate(UpdateRequest request) {
        if (request == null || !("expense".equals(request.type()) || "income".equals(request.type()) || "debt_payment".equals(request.type()))
                || !"RUB".equals(request.currency()) || request.categoryCode() == null || request.categoryCode().isBlank()
                || request.categoryCode().trim().length() > 64
                || request.subcategoryCode() != null && request.subcategoryCode().trim().length() > 64
                || request.description() == null || request.description().trim().length() > 500
                || request.occurredAt() == null
                || request.subcategoryCode() != null && request.subcategoryCode().trim().isEmpty()
                || !"debt_payment".equals(request.type()) && request.debtId() != null
                || "debt_payment".equals(request.type()) && !"долги".equals(request.categoryCode().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction draft fields");
        }
        try {
            MoneyAmount.parse(request.amount());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid positive decimal amount", ex);
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

    private record DraftWithHash(DraftResponse draft, String hash) {}
    private record RepeatSource(String type, String amount, String currency, String categoryCode,
                                String subcategoryCode, String description) {}
}
