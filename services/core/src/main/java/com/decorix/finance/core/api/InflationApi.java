package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class InflationApi {
    private InflationApi() {}

    public record PersonalInflationRequest(UUID tenantId, UUID ownerUserId, Instant asOf) {}

    public record PersonalInflation(boolean available, String reasonCode, Instant asOf, int windowDays,
                                    int productCount, String basketBefore, String basketNow, String indexPercent,
                                    List<PersonalInflationItem> rising, List<PersonalInflationItem> falling) {}

    public record PersonalInflationItem(String productName, String oldUnitPrice, String newUnitPrice,
                                         String oldSpendWeight, String changePercent,
                                         int olderPurchaseCount, int windowPurchaseCount) {}
}
