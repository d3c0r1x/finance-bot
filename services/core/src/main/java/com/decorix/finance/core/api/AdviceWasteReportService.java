package com.decorix.finance.core.api;

import com.decorix.finance.core.api.AdviceWasteApi.ReceiptLine;
import com.decorix.finance.core.api.AdviceWasteApi.Request;
import com.decorix.finance.core.api.AdviceWasteApi.WasteReport;
import com.decorix.finance.core.domain.ProductIdentityPolicy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdviceWasteReportService {
    private static final Logger log = LoggerFactory.getLogger(AdviceWasteReportService.class);
    private static final int MAX_ITEMS = 50_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AdviceWasteReportClient client;

    public AdviceWasteReportService(JdbcTemplate jdbc, TransactionTemplate transaction, AdviceWasteReportClient client) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.client = client;
    }

    public WasteReport get(UUID tenantId, String subject, LocalDate fromDate, LocalDate toDate,
                           String timeZone, boolean family) {
        ZoneId zone = ZoneId.of(timeZone);
        Instant asOf = toDate.plusDays(1).atStartOfDay(zone).toInstant();
        Selection selection;
        try {
            selection = transaction.execute(status -> select(tenantId, subject, fromDate, toDate, zone, family));
        } catch (DataAccessException exception) {
            log.warn("Advice receipt facts unavailable; finance report remains available ({})",
                    exception.getClass().getSimpleName());
            return WasteReport.unavailable(new Request(fromDate.toString(), toDate.toString(), asOf, timeZone, List.of()),
                    "analytics_unavailable", "partial");
        }
        Request request = new Request(fromDate.toString(), toDate.toString(), asOf, timeZone,
                selection.overflow() ? List.of() : selection.items());
        if (selection.overflow()) return WasteReport.unavailable(request, "too_many_items", "partial");
        if (selection.items().isEmpty()) return WasteReport.unavailable(request, "no_reviewed_items", "complete");
        try {
            return client.calculate(request);
        } catch (RuntimeException exception) {
            log.warn("Advice analytics unavailable; finance report remains available ({})",
                    exception.getClass().getSimpleName());
            return WasteReport.unavailable(request, "analytics_unavailable", "partial");
        }
    }

    private Selection select(UUID tenantId, String subject, LocalDate fromDate, LocalDate toDate,
                             ZoneId zone, boolean family) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        UUID requesterId = jdbc.queryForList("SELECT user_id FROM memberships WHERE tenant_id = ? AND subject = ? "
                        + "AND status = 'active'", UUID.class, tenantId, subject)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Active report membership disappeared"));
        UUID ownerFilter = family ? null : requesterId;
        Instant start = fromDate.atStartOfDay(zone).toInstant();
        Instant end = toDate.plusDays(1).atStartOfDay(zone).toInstant();
        List<ReceiptFact> facts = jdbc.query("""
                SELECT ri.id AS item_id, r.owner_user_id, ri.name, ri.line_sum, ri.verdict,
                       ri.verdict_source, t.occurred_at, ri.version AS item_version
                FROM receipts r
                JOIN transactions t ON t.tenant_id = r.tenant_id AND t.id = r.transaction_id
                JOIN receipt_items ri ON ri.tenant_id = r.tenant_id AND ri.receipt_id = r.id
                WHERE r.tenant_id = ? AND r.state = 'confirmed'
                  AND t.status = 'posted' AND t.type = 'expense'
                  AND (?::uuid IS NULL OR r.owner_user_id = ?)
                  AND t.occurred_at >= ? AND t.occurred_at < ?
                ORDER BY t.occurred_at, r.id, ri.ordinal, ri.id
                LIMIT 50001
                """, (rs, row) -> new ReceiptFact(
                rs.getObject("item_id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                rs.getString("name"), rs.getBigDecimal("line_sum"), rs.getString("verdict"),
                rs.getString("verdict_source"), rs.getTimestamp("occurred_at").toInstant(), rs.getLong("item_version")),
                tenantId, ownerFilter, ownerFilter, Timestamp.from(start), Timestamp.from(end));
        if (facts.size() > MAX_ITEMS) return new Selection(true, List.of());
        return new Selection(false, toLines(tenantId, facts));
    }

    private List<ReceiptLine> toLines(UUID tenantId, List<ReceiptFact> facts) {
        Set<UUID> owners = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (ReceiptFact fact : facts) {
            owners.add(fact.ownerUserId());
            keys.add(ProductIdentityPolicy.productKey(fact.name()));
        }
        Map<DecisionKey, Long> allowed = loadAllowedDecisions(tenantId, owners, keys);
        List<ReceiptLine> lines = new ArrayList<>(facts.size());
        for (ReceiptFact fact : facts) {
            String key = ProductIdentityPolicy.productKey(fact.name());
            Long decisionVersion = allowed.get(new DecisionKey(fact.ownerUserId(), key));
            BigDecimal lineSum = fact.lineSum();
            lines.add(new ReceiptLine(fact.itemId().toString(), key, fact.name(),
                    lineSum == null ? null : lineSum.toPlainString(), fact.verdict(), fact.verdictSource(),
                    fact.purchasedAt(), decisionVersion != null, fact.itemVersion(),
                    decisionVersion == null ? 0 : decisionVersion));
        }
        return List.copyOf(lines);
    }

    private Map<DecisionKey, Long> loadAllowedDecisions(UUID tenantId, Set<UUID> owners, Set<String> keys) {
        Map<DecisionKey, Long> result = new HashMap<>();
        if (owners.isEmpty() || keys.isEmpty()) return result;
        jdbc.query("""
                SELECT user_id, product_key, version
                FROM user_product_decisions
                WHERE tenant_id = ? AND decision = 'allowed'
                """, rs -> {
            while (rs.next()) {
                UUID ownerId = rs.getObject("user_id", UUID.class);
                String productKey = rs.getString("product_key");
                if (owners.contains(ownerId) && keys.contains(productKey)) {
                    result.put(new DecisionKey(ownerId, productKey), rs.getLong("version"));
                }
            }
            return null;
        }, tenantId);
        return result;
    }

    private record ReceiptFact(UUID itemId, UUID ownerUserId, String name, BigDecimal lineSum, String verdict,
                               String verdictSource, Instant purchasedAt, long itemVersion) {}
    private record DecisionKey(UUID ownerUserId, String productKey) {}
    private record Selection(boolean overflow, List<ReceiptLine> items) {}
}
