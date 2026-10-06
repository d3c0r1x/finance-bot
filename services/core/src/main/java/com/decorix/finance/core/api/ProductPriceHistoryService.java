package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ProductApi.PriceCompareRequest;
import com.decorix.finance.core.api.ProductApi.PriceComparison;
import com.decorix.finance.core.api.ProductApi.ProductCatalogResponse;
import com.decorix.finance.core.api.ProductApi.ShoppingCandidate;
import com.decorix.finance.core.api.ProductApi.ShoppingList;
import com.decorix.finance.core.api.InflationApi.PersonalInflation;
import com.decorix.finance.core.api.RecurringApi.RecurringProjection;
import com.decorix.finance.core.domain.ProductIdentityPolicy;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ShoppingDecisionService shoppingDecisions;
    private final RecurringDecisionService recurringDecisions;
    private final AdviceEvidenceService evidence;

    @Autowired
    public ProductPriceHistoryService(JdbcTemplate jdbc, TransactionTemplate transaction,
                                      ProductPriceHistoryClient analytics,
                                      ShoppingDecisionService shoppingDecisions,
                                      RecurringDecisionService recurringDecisions,
                                      AdviceEvidenceService evidence) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.analytics = analytics;
        this.shoppingDecisions = shoppingDecisions;
        this.recurringDecisions = recurringDecisions;
        this.evidence = evidence;
    }

    ProductPriceHistoryService(JdbcTemplate jdbc, TransactionTemplate transaction,
                               ProductPriceHistoryClient analytics,
                               ShoppingDecisionService shoppingDecisions,
                               RecurringDecisionService recurringDecisions) {
        this(jdbc, transaction, analytics, shoppingDecisions, recurringDecisions, null);
    }

    ProductPriceHistoryService(JdbcTemplate jdbc, TransactionTemplate transaction,
                               ProductPriceHistoryClient analytics) {
        this(jdbc, transaction, analytics, new ShoppingDecisionService(jdbc, transaction),
                new RecurringDecisionService(jdbc, transaction));
    }

    ProductPriceHistoryService(JdbcTemplate jdbc, TransactionTemplate transaction,
                               ProductPriceHistoryClient analytics, ShoppingDecisionService shoppingDecisions) {
        this(jdbc, transaction, analytics, shoppingDecisions, new RecurringDecisionService(jdbc, transaction));
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
        return shoppingForMember(tenantId, userId);
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
        return shoppingForMember(tenantId, userId);
    }

    public ShoppingList markShoppingBought(UUID tenantId, String subject, String productKey) {
        UUID userId = memberForSubject(tenantId, subject);
        return updateShoppingDecision(tenantId, userId, productKey, "bought");
    }

    public ShoppingList markShoppingBought(UUID tenantId, UUID ownerUserId, String productKey) {
        return updateShoppingDecision(tenantId, ownerUserId, productKey, "bought");
    }

    public ShoppingList muteShopping(UUID tenantId, String subject, String productKey) {
        UUID userId = memberForSubject(tenantId, subject);
        return updateShoppingDecision(tenantId, userId, productKey, "mute");
    }

    public ShoppingList muteShopping(UUID tenantId, UUID ownerUserId, String productKey) {
        return updateShoppingDecision(tenantId, ownerUserId, productKey, "mute");
    }

    public ShoppingList unmuteShopping(UUID tenantId, String subject, String productKey) {
        UUID userId = memberForSubject(tenantId, subject);
        return updateShoppingDecision(tenantId, userId, productKey, "unmute");
    }

    public ShoppingList unmuteShopping(UUID tenantId, UUID ownerUserId, String productKey) {
        return updateShoppingDecision(tenantId, ownerUserId, productKey, "unmute");
    }

    public PersonalInflation personalInflation(UUID tenantId, String subject) {
        UUID userId = memberForSubject(tenantId, subject);
        return analytics.personalInflation(tenantId, userId, Instant.now());
    }

    public PersonalInflation personalInflation(UUID tenantId, UUID ownerUserId) {
        UUID userId = activeMember(tenantId, ownerUserId);
        return analytics.personalInflation(tenantId, userId, Instant.now());
    }

    public RecurringProjection recurring(UUID tenantId, String subject) {
        MemberScope member = recurringMember(tenantId, subject);
        return recurringProjection(tenantId, member);
    }

    /** Rechecks the active Telegram member before requesting that member's recurring history. */
    public RecurringProjection recurring(UUID tenantId, UUID ownerUserId) {
        MemberScope member = recurringMember(tenantId, ownerUserId);
        return recurringProjection(tenantId, member);
    }

    public RecurringProjection muteRecurring(UUID tenantId, String subject, String seriesId) {
        return setRecurringMute(tenantId, recurringMember(tenantId, subject), seriesId, true);
    }

    public RecurringProjection muteRecurring(UUID tenantId, UUID ownerUserId, String seriesId) {
        return setRecurringMute(tenantId, recurringMember(tenantId, ownerUserId), seriesId, true);
    }

    public RecurringProjection unmuteRecurring(UUID tenantId, String subject, String seriesId) {
        return setRecurringMute(tenantId, recurringMember(tenantId, subject), seriesId, false);
    }

    public RecurringProjection unmuteRecurring(UUID tenantId, UUID ownerUserId, String seriesId) {
        return setRecurringMute(tenantId, recurringMember(tenantId, ownerUserId), seriesId, false);
    }

    private RecurringProjection recurringProjection(UUID tenantId, MemberScope member) {
        RecurringProjection source = analytics.recurring(tenantId, member.userId(), Instant.now(), member.timezone());
        return recurringDecisions.apply(tenantId, member.userId(), source);
    }

    private RecurringProjection setRecurringMute(UUID tenantId, MemberScope member, String seriesId, boolean mute) {
        String id = RecurringDecisionService.requireSeriesId(seriesId);
        RecurringProjection source = analytics.recurring(tenantId, member.userId(), Instant.now(), member.timezone());
        boolean exists = source.expenseSeries().stream().anyMatch(series -> id.equals(series.id()))
                || source.incomeSeries().stream().anyMatch(series -> id.equals(series.id()));
        if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurring series not found");
        if (mute) recurringDecisions.mute(tenantId, member.userId(), id);
        else recurringDecisions.unmute(tenantId, member.userId(), id);
        return recurringDecisions.apply(tenantId, member.userId(), source);
    }

    private MemberScope recurringMember(UUID tenantId, String subject) {
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurring projection not found");
        }
        MemberScope member = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = memberUserId(tenantId, subject);
            return userId == null ? null : new MemberScope(userId, profileTimezone(tenantId, userId));
        });
        if (member == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurring projection not found");
        return member;
    }

    private MemberScope recurringMember(UUID tenantId, UUID ownerUserId) {
        if (tenantId == null || ownerUserId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurring projection not found");
        }
        MemberScope member = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            List<UUID> members = jdbc.query("SELECT user_id FROM memberships WHERE tenant_id = ? AND user_id = ? "
                            + "AND status = 'active'",
                    (rs, row) -> rs.getObject("user_id", UUID.class), tenantId, ownerUserId);
            return members.isEmpty() ? null : new MemberScope(members.get(0), profileTimezone(tenantId, members.get(0)));
        });
        if (member == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurring projection not found");
        return member;
    }

    private ShoppingList shoppingForMember(UUID tenantId, UUID userId) {
        ShoppingList source = analytics.shopping(tenantId.toString(), userId.toString());
        return shoppingDecisions.apply(tenantId, userId, source, bannedEvidenceKeys(tenantId, userId));
    }

    private ShoppingList updateShoppingDecision(UUID tenantId, UUID userId, String productKey, String action) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shopping list not found");
        }
        ShoppingList source = analytics.shopping(tenantId.toString(), activeMember(tenantId, userId).toString());
        Set<String> blocked = bannedEvidenceKeys(tenantId, userId);
        ShoppingCandidate target = source.candidates().stream().filter(candidate ->
                productKey.equals(ProductIdentityPolicy.productKey(candidate.productName()))).findFirst().orElse(null);
        if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shopping suggestion not found");
        switch (action) {
            case "bought" -> shoppingDecisions.markBought(tenantId, userId, productKey);
            case "mute" -> shoppingDecisions.mute(tenantId, userId, productKey);
            case "unmute" -> shoppingDecisions.unmute(tenantId, userId, productKey);
            default -> throw new IllegalArgumentException("Unknown shopping decision");
        }
        return shoppingDecisions.apply(tenantId, userId, source, blocked);
    }

    private Set<String> bannedEvidenceKeys(UUID tenantId, UUID userId) {
        if (evidence == null) return Set.of();
        AdviceEvidenceApi.Report report = evidence.get(tenantId, userId);
        if (!report.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Shopping advice evidence is unavailable");
        }
        return report.banned().stream().map(AdviceEvidenceApi.EvidenceGroup::productKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private UUID activeMember(UUID tenantId, UUID userId) {
        UUID member = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            List<UUID> members = jdbc.query("SELECT user_id FROM memberships WHERE tenant_id = ? AND user_id = ? "
                            + "AND status = 'active'",
                    (rs, row) -> rs.getObject("user_id", UUID.class), tenantId, userId);
            return members.isEmpty() ? null : members.get(0);
        });
        if (member == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shopping list not found");
        return member;
    }

    private UUID memberForSubject(UUID tenantId, String subject) {
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shopping list not found");
        }
        UUID userId = transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return memberUserId(tenantId, subject);
        });
        if (userId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shopping list not found");
        return userId;
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

    private String profileTimezone(UUID tenantId, UUID userId) {
        String timezone = jdbc.queryForObject("SELECT timezone FROM member_profiles WHERE tenant_id = ? AND user_id = ?",
                String.class, tenantId, userId);
        try { return ZoneId.of(timezone).getId(); }
        catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Member timezone is invalid", invalid);
        }
    }

    private record MemberScope(UUID userId, String timezone) {}
    private record CurrentPriceItem(String currency, String name, String quantity, String lineSum,
                                    Instant purchasedAt, UUID ownerUserId) {}
}
