package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ProductApi.PriceCompareRequest;
import com.decorix.finance.core.api.ProductApi.PriceComparison;
import com.decorix.finance.core.api.ProductApi.ProductCatalogResponse;
import com.decorix.finance.core.api.ProductApi.ShoppingList;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductPriceHistoryService {
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}]");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ProductPriceHistoryClient analytics;

    public ProductPriceHistoryService(JdbcTemplate jdbc, TransactionTemplate transaction,
                                      ProductPriceHistoryClient analytics) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.analytics = analytics;
    }

    public PriceComparison get(UUID tenantId, String subject, UUID receiptId, UUID itemId) {
        if (tenantId == null || receiptId == null || itemId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt item not found");
        }
        CurrentPriceItem item = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = memberUserId(tenantId, subject);
            if (userId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt item not found");
            List<CurrentPriceItem> rows = jdbc.query("""
                    SELECT r.currency, ri.name, ri.quantity::text AS quantity, ri.line_sum::text AS line_sum,
                           t.occurred_at
                    FROM receipts r
                    JOIN receipt_items ri ON ri.tenant_id = r.tenant_id AND ri.receipt_id = r.id
                    JOIN transactions t ON t.tenant_id = r.tenant_id AND t.id = r.transaction_id
                    WHERE r.tenant_id = ? AND r.owner_user_id = ? AND r.id = ? AND ri.id = ?
                      AND r.state = 'confirmed' AND t.status = 'posted'
                    """, (rs, row) -> new CurrentPriceItem(rs.getString("currency"), rs.getString("name"),
                    rs.getString("quantity"), rs.getString("line_sum"), rs.getTimestamp("occurred_at").toInstant(), userId),
                    tenantId, userId, receiptId, itemId);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt item not found");
            return rows.get(0);
        });
        if (item == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt item not found");
        if (item.quantity() == null || item.lineSum() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Receipt item does not have a usable quantity and paid line total");
        }
        return analytics.compare(new PriceCompareRequest(tenantId.toString(), item.ownerUserId().toString(),
                receiptId.toString(), itemId.toString(), item.name(), item.quantity(), item.lineSum(),
                item.currency(), item.purchasedAt()));
    }

    public ProductCatalogResponse catalog(UUID tenantId, String subject, String query) {
        validateCatalogRequest(tenantId, query);
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product catalog not found");
        }
        UUID userId = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return memberUserId(tenantId, subject);
        });
        if (userId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product catalog not found");
        return analytics.catalog(tenantId.toString(), userId.toString(), normalizeCatalogQuery(query));
    }

    /** Uses a previously resolved Telegram actor, while rechecking current membership in this tenant. */
    public ProductCatalogResponse catalog(UUID tenantId, UUID ownerUserId, String query) {
        validateCatalogRequest(tenantId, query);
        if (ownerUserId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product catalog not found");
        UUID userId = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            List<UUID> members = jdbc.query("""
                    SELECT user_id FROM memberships WHERE tenant_id = ? AND user_id = ? AND status = 'active'
                    """, (rs, row) -> rs.getObject("user_id", UUID.class), tenantId, ownerUserId);
            return members.isEmpty() ? null : members.get(0);
        });
        if (userId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product catalog not found");
        return analytics.catalog(tenantId.toString(), userId.toString(), normalizeCatalogQuery(query));
    }

    public ShoppingList shopping(UUID tenantId, String subject) {
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shopping list not found");
        }
        UUID userId = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return memberUserId(tenantId, subject);
        });
        if (userId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shopping list not found");
        return analytics.shopping(tenantId.toString(), userId.toString());
    }

    /** Uses a resolved Telegram actor while rechecking active membership before reading history. */
    public ShoppingList shopping(UUID tenantId, UUID ownerUserId) {
        if (tenantId == null || ownerUserId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shopping list not found");
        }
        UUID userId = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            List<UUID> members = jdbc.query("""
                    SELECT user_id FROM memberships WHERE tenant_id = ? AND user_id = ? AND status = 'active'
                    """, (rs, row) -> rs.getObject("user_id", UUID.class), tenantId, ownerUserId);
            return members.isEmpty() ? null : members.get(0);
        });
        if (userId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shopping list not found");
        return analytics.shopping(tenantId.toString(), userId.toString());
    }

    private static void validateCatalogRequest(UUID tenantId, String query) {
        if (tenantId == null || query != null && (query.codePointCount(0, query.length()) > 80
                || CONTROL.matcher(query).find())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product search query is invalid");
        }
    }

    private static String normalizeCatalogQuery(String query) {
        return query == null ? "" : query.trim();
    }

    private UUID memberUserId(UUID tenantId, String subject) {
        List<UUID> users = jdbc.query("""
                SELECT i.user_id FROM external_identities i
                JOIN memberships m ON m.tenant_id = ? AND m.user_id = i.user_id
                WHERE i.provider = 'keycloak' AND i.subject = ? AND m.status = 'active'
                """, (rs, row) -> rs.getObject("user_id", UUID.class), tenantId, subject);
        return users.isEmpty() ? null : users.get(0);
    }

    private record CurrentPriceItem(String currency, String name, String quantity, String lineSum,
                                    Instant purchasedAt, UUID ownerUserId) {}
}
