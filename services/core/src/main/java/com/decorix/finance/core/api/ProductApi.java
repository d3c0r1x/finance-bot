package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.List;

public final class ProductApi {
    private ProductApi() {}

    public record ProductDecisionSelection(String decision) {}

    public record ProductDecisionResponse(String productKey, String decision, long version, Instant updatedAt) {}

    public record ProductDecisionKeys(List<String> productKeys) {}

    public record ProductCatalogRequest(String tenantId, String ownerUserId, String query) {}

    public record ShoppingCandidatesRequest(String tenantId, String ownerUserId) {}

    public record ProductCatalogResponse(String mode, String query, List<ProductCard> products) {}

    public record ShoppingList(List<ShoppingCandidate> candidates, String estimatedListCost,
                               boolean inventoryTracked) {}

    public record ShoppingCandidate(String productName, int purchaseCount, int medianIntervalDays,
                                    String usualUnitPrice, String estimatedCost, Instant lastPurchasedAt,
                                    Instant dueAt, int daysUntilDue) {}

    public record ProductCard(String productName, int purchaseCount, String usualUnitPrice, boolean hasBaseline,
                              String baselineUnitPrice, String lastUnitPrice, Instant lastPurchasedAt,
                              String lastMerchant, String cheapestUnitPrice, String cheapestMerchant,
                              String totalSpent, String change, String relative, boolean signal,
                              String direction, int priorPurchases, boolean chartAvailable,
                              List<PriceHistoryPoint> history) {}

    public record PriceCompareRequest(String tenantId, String ownerUserId, String receiptId, String itemId,
                                      String name, String quantity, String lineSum, String currency,
                                      Instant purchasedAt) {}

    public record PriceHistoryPoint(String receiptId, String itemId, Instant purchasedAt, String merchant,
                                    String name, String unitPrice, boolean current) {}

    public record PriceComparison(String algorithmVersion, String productName, boolean hasBaseline,
                                  String currentUnitPrice, String baselineUnitPrice, String change,
                                  String relative, boolean signal, String direction, int priorPurchases,
                                  List<PriceHistoryPoint> history) {}
}
