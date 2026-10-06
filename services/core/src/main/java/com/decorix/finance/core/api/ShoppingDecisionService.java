package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ProductApi.BlockedShoppingCandidate;
import com.decorix.finance.core.api.ProductApi.ShoppingCandidate;
import com.decorix.finance.core.api.ProductApi.ShoppingList;
import com.decorix.finance.core.domain.ProductIdentityPolicy;
import com.decorix.finance.core.domain.ShoppingDecisionPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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

/** Persists personal shopping decisions and overlays them on shared analytics candidates. */
@Service
public class ShoppingDecisionService {
    private static final String SECTION = "shopping";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ShoppingDecisionService(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public ShoppingList apply(UUID tenantId, UUID userId, ShoppingList analytics) {
        return apply(tenantId, userId, analytics, Set.of());
    }

    public ShoppingList apply(UUID tenantId, UUID userId, ShoppingList analytics, Set<String> evidenceBlocked) {
        if (analytics == null || analytics.candidates() == null || analytics.inventoryTracked()) {
            throw unavailable();
        }
        if (evidenceBlocked == null) throw unavailable();
        DecisionSnapshot decisions = transaction.execute(status -> {
            setTenant(tenantId);
            Map<String, Instant> marks = new HashMap<>();
            jdbc.query("SELECT product_key, marked_at FROM shopping_marks WHERE tenant_id = ? AND user_id = ?",
                    (org.springframework.jdbc.core.RowCallbackHandler)
                    rs -> marks.put(rs.getString("product_key"), rs.getTimestamp("marked_at").toInstant()),
                    tenantId, userId);
            Set<String> muted = new HashSet<>(jdbc.query("SELECT suggestion_key FROM muted_suggestions "
                            + "WHERE tenant_id = ? AND user_id = ? AND section = ?",
                    (rs, row) -> rs.getString("suggestion_key"), tenantId, userId, SECTION));
            Set<String> blocked = new HashSet<>(jdbc.query("SELECT product_key FROM user_product_decisions "
                            + "WHERE tenant_id = ? AND user_id = ? AND decision = 'confirmed'",
                    (rs, row) -> rs.getString("product_key"), tenantId, userId));
            Set<String> allowed = new HashSet<>(jdbc.query("SELECT product_key FROM user_product_decisions "
                            + "WHERE tenant_id = ? AND user_id = ? AND decision = 'allowed'",
                    (rs, row) -> rs.getString("product_key"), tenantId, userId));
            return new DecisionSnapshot(marks, muted, blocked, allowed);
        });
        if (decisions == null) throw unavailable();

        Instant now = Instant.now();
        List<ShoppingCandidate> visible = new ArrayList<>();
        List<ShoppingCandidate> bought = new ArrayList<>();
        List<ShoppingCandidate> muted = new ArrayList<>();
        List<BlockedShoppingCandidate> blocked = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (ShoppingCandidate raw : analytics.candidates()) {
            if (raw == null) throw unavailable();
            String key = productKey(raw.productName());
            ShoppingCandidate candidate = withKey(raw, key);
            Instant markedAt = decisions.marks().get(key);
            if (ShoppingDecisionPolicy.boughtMarkIsFresh(markedAt, raw.lastPurchasedAt(),
                    raw.medianIntervalDays(), now)) {
                bought.add(candidate);
            } else if (decisions.blocked().contains(key)) {
                blocked.add(new BlockedShoppingCandidate(key, raw.productName(), "confirmed_not_to_buy"));
            } else if (evidenceBlocked.contains(key) && !decisions.allowed().contains(key)) {
                blocked.add(new BlockedShoppingCandidate(key, raw.productName(), "rule_backed_not_to_buy"));
            } else if (decisions.muted().contains(key)) {
                muted.add(candidate);
            } else {
                visible.add(candidate);
                try {
                    total = total.add(new BigDecimal(raw.estimatedCost()).setScale(2, RoundingMode.UNNECESSARY));
                } catch (RuntimeException invalidMoney) {
                    throw unavailable();
                }
            }
        }
        return new ShoppingList(List.copyOf(visible), total.toPlainString(), false,
                List.copyOf(bought), List.copyOf(muted), List.copyOf(blocked));
    }

    public void markBought(UUID tenantId, UUID userId, String productKey) {
        String key = requireProductKey(productKey);
        transaction.executeWithoutResult(status -> {
            setTenant(tenantId);
            jdbc.update("INSERT INTO shopping_marks (tenant_id, user_id, product_key, marked_at) VALUES (?, ?, ?, now()) "
                            + "ON CONFLICT (tenant_id, user_id, product_key) DO UPDATE SET marked_at = EXCLUDED.marked_at",
                    tenantId, userId, key);
        });
    }

    public void mute(UUID tenantId, UUID userId, String productKey) {
        String key = requireProductKey(productKey);
        transaction.executeWithoutResult(status -> {
            setTenant(tenantId);
            jdbc.update("INSERT INTO muted_suggestions (tenant_id, user_id, section, suggestion_key) "
                            + "VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id, user_id, section, suggestion_key) "
                            + "DO UPDATE SET muted_at = now()",
                    tenantId, userId, SECTION, key);
        });
    }

    public void unmute(UUID tenantId, UUID userId, String productKey) {
        String key = requireProductKey(productKey);
        transaction.executeWithoutResult(status -> {
            setTenant(tenantId);
            jdbc.update("DELETE FROM muted_suggestions WHERE tenant_id = ? AND user_id = ? "
                    + "AND section = ? AND suggestion_key = ?", tenantId, userId, SECTION, key);
        });
    }

    private void setTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private static String productKey(String name) {
        String key = ProductIdentityPolicy.productKey(name);
        try {
            return ProductIdentityPolicy.requireProductKey(key);
        } catch (IllegalArgumentException invalid) {
            throw unavailable();
        }
    }

    private static String requireProductKey(String key) {
        try {
            return ProductIdentityPolicy.requireProductKey(key);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shopping product key is invalid");
        }
    }

    private static ShoppingCandidate withKey(ShoppingCandidate candidate, String key) {
        return new ShoppingCandidate(candidate.productName(), candidate.purchaseCount(), candidate.medianIntervalDays(),
                candidate.usualUnitPrice(), candidate.estimatedCost(), candidate.lastPurchasedAt(), candidate.dueAt(),
                candidate.daysUntilDue(), key);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Shopping candidates response is incomplete");
    }

    private record DecisionSnapshot(Map<String, Instant> marks, Set<String> muted, Set<String> blocked,
                                    Set<String> allowed) {}
}
