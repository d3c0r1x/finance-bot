package com.decorix.finance.core.api;

import com.decorix.finance.core.api.AdviceEvidenceApi.EvidenceLine;
import com.decorix.finance.core.api.AdviceEvidenceApi.GoResponse;
import com.decorix.finance.core.api.AdviceEvidenceApi.Report;
import com.decorix.finance.core.api.AdviceEvidenceApi.Request;
import com.decorix.finance.core.domain.ProductIdentityPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdviceEvidenceService {
    private static final Logger log = LoggerFactory.getLogger(AdviceEvidenceService.class);
    private static final int MAX_ITEMS = 50_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AdviceEvidenceClient client;

    public AdviceEvidenceService(JdbcTemplate jdbc, TransactionTemplate transaction, AdviceEvidenceClient client) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.client = client;
    }

    public Report get(UUID tenantId, String subject) {
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Do-not-buy list not found");
        }
        Selection selection;
        try {
            selection = transaction.execute(status -> select(tenantId, subject));
        } catch (DataAccessException exception) {
            log.warn("Advice evidence facts unavailable ({})", exception.getClass().getSimpleName());
            return Report.unavailable("analytics_unavailable");
        }
        return report(selection);
    }

    public Report get(UUID tenantId, UUID ownerUserId) {
        if (tenantId == null || ownerUserId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Do-not-buy list not found");
        }
        Selection selection;
        try {
            selection = transaction.execute(status -> select(tenantId, ownerUserId));
        } catch (DataAccessException exception) {
            log.warn("Advice evidence facts unavailable ({})", exception.getClass().getSimpleName());
            return Report.unavailable("analytics_unavailable");
        }
        return report(selection);
    }

    private Report report(Selection selection) {
        if (selection == null) return Report.unavailable("analytics_unavailable");
        if (selection.overflow()) return Report.unavailable("too_many_items");
        if (selection.items().isEmpty()) {
            return new Report(true, "no_optional_items", "advice-evidence.v1", null, List.of(), List.of());
        }
        try {
            GoResponse evidence = client.calculate(new Request(selection.items()));
            AdviceEvidencePolicy.Classified classified = AdviceEvidencePolicy.apply(
                    evidence.groups(), selection.decisions());
            return new Report(true, "available", evidence.algorithmVersion(), evidence.inputVersion(),
                    classified.banned(), classified.guesses());
        } catch (RuntimeException exception) {
            log.warn("Advice evidence analytics unavailable ({})", exception.getClass().getSimpleName());
            return Report.unavailable("analytics_unavailable");
        }
    }

    private Selection select(UUID tenantId, String subject) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        List<UUID> members = jdbc.query("""
                SELECT i.user_id FROM external_identities i
                JOIN memberships m ON m.tenant_id = ? AND m.user_id = i.user_id
                WHERE i.provider = 'keycloak' AND i.subject = ? AND m.status = 'active'
                """, (rs, row) -> rs.getObject("user_id", UUID.class), tenantId, subject);
        if (members.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Do-not-buy list not found");
        return selectFacts(tenantId, members.get(0));
    }

    private Selection select(UUID tenantId, UUID ownerUserId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        List<UUID> members = jdbc.query("SELECT user_id FROM memberships "
                        + "WHERE tenant_id = ? AND user_id = ? AND status = 'active'",
                (rs, row) -> rs.getObject("user_id", UUID.class), tenantId, ownerUserId);
        if (members.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Do-not-buy list not found");
        return selectFacts(tenantId, members.get(0));
    }

    private Selection selectFacts(UUID tenantId, UUID userId) {
        List<Fact> facts = jdbc.query("""
                SELECT ri.id AS item_id, ri.name, ri.line_sum, ri.verdict,
                       ri.verdict_source, ri.advice, ri.version AS item_version, t.occurred_at
                FROM receipts r
                JOIN transactions t ON t.tenant_id = r.tenant_id AND t.id = r.transaction_id
                JOIN receipt_items ri ON ri.tenant_id = r.tenant_id AND ri.receipt_id = r.id
                WHERE r.tenant_id = ? AND r.owner_user_id = ? AND r.state = 'confirmed'
                  AND t.status = 'posted' AND t.type = 'expense'
                  AND ri.verdict IN ('harmful', 'unnecessary')
                ORDER BY t.occurred_at, r.id, ri.ordinal, ri.id
                LIMIT 50001
                """, (rs, row) -> new Fact(rs.getObject("item_id", UUID.class), rs.getString("name"),
                rs.getBigDecimal("line_sum"), rs.getString("verdict"), rs.getString("verdict_source"),
                rs.getString("advice"), rs.getTimestamp("occurred_at").toInstant(), rs.getLong("item_version")),
                tenantId, userId);
        if (facts.size() > MAX_ITEMS) return new Selection(true, List.of(), Map.of());
        List<EvidenceLine> lines = new ArrayList<>(facts.size());
        for (Fact fact : facts) {
            String key = ProductIdentityPolicy.productKey(fact.name());
            if (key.isEmpty()) continue;
            lines.add(new EvidenceLine(fact.itemId().toString(), key, fact.name(),
                    fact.lineSum() == null ? null : fact.lineSum().toPlainString(), fact.verdict(),
                    fact.verdictSource() == null ? "unknown" : fact.verdictSource(),
                    fact.advice() == null ? "" : fact.advice(), fact.purchasedAt(), fact.itemVersion()));
        }
        Map<String, String> decisions = new HashMap<>();
        jdbc.query("SELECT product_key, decision FROM user_product_decisions WHERE tenant_id = ? AND user_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        decisions.put(rs.getString("product_key"), rs.getString("decision")), tenantId, userId);
        return new Selection(false, List.copyOf(lines), Map.copyOf(decisions));
    }

    private record Fact(UUID itemId, String name, BigDecimal lineSum, String verdict,
                        String verdictSource, String advice, java.time.Instant purchasedAt, long itemVersion) {}
    private record Selection(boolean overflow, List<EvidenceLine> items, Map<String, String> decisions) {}
}
