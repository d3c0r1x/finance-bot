package com.decorix.finance.core.api;

import com.decorix.finance.core.api.MerchantMappingApi.Mapping;
import com.decorix.finance.core.api.MerchantReclassificationApi.ApplyRequest;
import com.decorix.finance.core.api.MerchantReclassificationApi.ApplyResult;
import com.decorix.finance.core.api.MerchantReclassificationApi.Candidate;
import com.decorix.finance.core.api.MerchantReclassificationApi.CandidateSelection;
import com.decorix.finance.core.api.MerchantReclassificationApi.Preview;
import com.decorix.finance.core.api.MerchantReclassificationApi.PreviewRequest;
import com.decorix.finance.core.api.TransactionApi.TransactionResponse;
import com.decorix.finance.core.api.TransactionApi.UpdateRequest;
import com.decorix.finance.core.domain.MerchantCategoryPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantReclassificationService {
    private static final int MAX_CANDIDATES = 10_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MerchantMappingService mappings;
    private final TransactionService transactions;

    public MerchantReclassificationService(JdbcTemplate jdbc, TransactionTemplate transaction,
                                           MerchantMappingService mappings, TransactionService transactions) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mappings = mappings;
        this.transactions = transactions;
    }

    public Preview preview(UUID tenantId, String subject, PreviewRequest request) {
        String normalized = normalizeRequestMerchant(request == null ? null : request.merchant());
        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, false);
            Mapping mapping = mapping(tenantId, actor.userId(), normalized);
            List<Candidate> candidates = eligibleCandidates(tenantId, actor.userId(), subject, normalized,
                    mapping.categoryCode(), false);
            return new Preview(mapping.merchant(), normalized, mapping.categoryCode(), List.copyOf(candidates));
        });
    }

    public ApplyResult apply(UUID tenantId, String subject, String idempotencyKey, ApplyRequest request) {
        String normalized = normalizeRequestMerchant(request == null ? null : request.merchant());
        if (!validIdempotencyKey(idempotencyKey) || request == null
                || !MerchantCategoryPolicy.CATEGORIES.contains(request.categoryCode())
                || request.candidates() == null || request.candidates().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A category, reviewed rows and idempotency key are required");
        }
        List<CandidateSelection> reviewed = validateReviewedCandidates(request.candidates());

        return transaction.execute(status -> {
            Actor actor = actor(tenantId, subject, true);
            MerchantCategoryLocks.acquire(jdbc, tenantId, actor.userId(), normalized);
            Mapping mapping = mapping(tenantId, actor.userId(), normalized);
            if (!mapping.categoryCode().equals(request.categoryCode())) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                        "Merchant category changed; review the transactions again");
            }

            List<Candidate> current = eligibleCandidates(tenantId, actor.userId(), subject, normalized,
                    mapping.categoryCode(), true);
            if (current.isEmpty() && allAlreadyApplied(tenantId, actor.userId(), subject, normalized,
                    mapping.categoryCode(), reviewed)) {
                return result(mapping.merchant(), mapping.categoryCode(), reviewed.stream()
                        .map(CandidateSelection::transactionId).toList());
            }
            if (!sameReviewedSet(reviewed, current)) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                        "Imported transactions changed; review the current list before applying");
            }

            List<UUID> changed = new ArrayList<>(current.size());
            for (Candidate candidate : current) {
                TransactionResponse before = transactions.get(tenantId, subject, candidate.transactionId());
                UpdateRequest update = new UpdateRequest(before.type(), before.amount(), before.currency(),
                        mapping.categoryCode(), before.subcategoryCode(), before.description(), before.source(),
                        before.occurredAt(), before.accountId(), before.debtId(), actor.userId());
                TransactionResponse after = transactions.update(tenantId, subject, candidate.transactionId(),
                        idempotencyKey + "-" + candidate.transactionId(), candidate.version(), update);
                int updated = jdbc.update("""
                        UPDATE bank_import_rows SET reclassification_version = ?
                        WHERE tenant_id = ? AND transaction_id = ? AND reclassification_version = ?
                          AND outcome = 'created' AND category_code IS NULL
                        """, after.version(), tenantId, candidate.transactionId(), candidate.version());
                if (updated != 1) {
                    throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                            "Imported transaction changed while applying its category");
                }
                changed.add(candidate.transactionId());
            }
            return result(mapping.merchant(), mapping.categoryCode(), changed);
        });
    }

    private Mapping mapping(UUID tenantId, UUID userId, String normalized) {
        return mappings.listForMember(tenantId, userId).stream()
                .filter(item -> item.normalizedMerchant().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant mapping not found"));
    }

    private List<Candidate> eligibleCandidates(UUID tenantId, UUID userId, String subject, String normalized,
                                                String targetCategory, boolean lock) {
        String sql = """
                SELECT t.id, t.version, t.category_code, t.amount::text, t.occurred_at, t.description, r.merchant
                FROM bank_import_rows r
                JOIN bank_imports b ON b.tenant_id = r.tenant_id AND b.id = r.import_id
                JOIN transactions t ON t.tenant_id = r.tenant_id AND t.id = r.transaction_id
                WHERE r.tenant_id = ? AND b.owner_user_id = ? AND b.owner_subject = ? AND b.state = 'committed'
                  AND t.owner_user_id = ? AND t.source = 'bank_import' AND t.type = 'expense' AND t.status = 'posted'
                  AND r.outcome = 'created' AND r.category_code IS NULL
                  AND r.reclassification_version = t.version AND t.category_code <> ?
                  AND r.merchant IS NOT NULL
                ORDER BY t.occurred_at, t.id
                """ + (lock ? " FOR UPDATE OF r, t" : "");
        List<CandidateRow> rows = jdbc.query(sql, (rs, index) -> new CandidateRow(
                rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("category_code"),
                rs.getString("amount"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("description"), rs.getString("merchant")),
                tenantId, userId, subject, userId, targetCategory);
        return rows.stream().filter(row -> normalized.equals(MerchantCategoryPolicy.normalizeMerchant(row.merchant())))
                .map(CandidateRow::candidate).toList();
    }

    private boolean allAlreadyApplied(UUID tenantId, UUID userId, String subject, String normalized,
                                      String targetCategory, List<CandidateSelection> reviewed) {
        for (CandidateSelection candidate : reviewed) {
            List<String> merchants = jdbc.query("""
                    SELECT r.merchant FROM bank_import_rows r
                    JOIN bank_imports b ON b.tenant_id = r.tenant_id AND b.id = r.import_id
                    JOIN transactions t ON t.tenant_id = r.tenant_id AND t.id = r.transaction_id
                    WHERE r.tenant_id = ? AND r.transaction_id = ? AND b.owner_user_id = ?
                      AND b.owner_subject = ? AND b.state = 'committed' AND t.owner_user_id = ?
                      AND t.source = 'bank_import' AND t.type = 'expense' AND t.status = 'posted'
                      AND r.outcome = 'created' AND r.category_code IS NULL
                      AND r.reclassification_version = t.version AND t.version = ?
                      AND t.category_code = ? AND r.merchant IS NOT NULL
                    """, (rs, row) -> rs.getString("merchant"), tenantId, candidate.transactionId(), userId, subject, userId,
                    candidate.version() + 1, targetCategory);
            if (merchants.size() != 1
                    || !normalized.equals(MerchantCategoryPolicy.normalizeMerchant(merchants.get(0)))) return false;
        }
        return true;
    }

    private static boolean sameReviewedSet(List<CandidateSelection> reviewed, List<Candidate> current) {
        Map<UUID, CandidateSelection> expected = new HashMap<>();
        for (CandidateSelection candidate : reviewed) {
            if (expected.put(candidate.transactionId(), candidate) != null) return false;
        }
        if (expected.size() != current.size()) return false;
        for (Candidate candidate : current) {
            CandidateSelection selection = expected.get(candidate.transactionId());
            if (selection == null || selection.version() != candidate.version()
                    || !selection.currentCategoryCode().equals(candidate.currentCategoryCode())) return false;
        }
        return true;
    }

    private static List<CandidateSelection> validateReviewedCandidates(List<CandidateSelection> candidates) {
        if (candidates.size() > MAX_CANDIDATES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reviewed transaction list is too large");
        }
        Set<UUID> ids = new HashSet<>();
        for (CandidateSelection candidate : candidates) {
            if (candidate == null || candidate.transactionId() == null || candidate.version() < 1
                    || !MerchantCategoryPolicy.CATEGORIES.contains(candidate.currentCategoryCode())
                    || !ids.add(candidate.transactionId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reviewed transaction list is invalid");
            }
        }
        return List.copyOf(candidates);
    }

    private static String normalizeRequestMerchant(String merchant) {
        try {
            return MerchantCategoryPolicy.normalizeMerchant(merchant);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Merchant name is invalid", exception);
        }
    }

    private Actor actor(UUID tenantId, String subject, boolean write) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
        List<Actor> actors = jdbc.query("""
                SELECT user_id, role FROM memberships
                WHERE tenant_id = ? AND subject = ? AND status = 'active'
                """, (rs, row) -> new Actor(rs.getObject("user_id", UUID.class), rs.getString("role")),
                tenantId, subject);
        if (actors.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        Actor actor = actors.get(0);
        if (write && "viewer".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Viewer access is read-only");
        }
        return actor;
    }

    private static boolean validIdempotencyKey(String key) {
        return key != null && key.length() >= 16 && key.length() <= 80 && key.matches("[A-Za-z0-9._:-]+");
    }

    private static ApplyResult result(String merchant, String categoryCode, List<UUID> ids) {
        return new ApplyResult(merchant, categoryCode, ids.size(), List.copyOf(ids));
    }

    private record Actor(UUID userId, String role) {}

    private record CandidateRow(UUID id, long version, String categoryCode, String amount,
                                java.time.Instant occurredAt, String description, String merchant) {
        Candidate candidate() {
            return new Candidate(id, version, categoryCode, amount, occurredAt, description);
        }
    }
}
