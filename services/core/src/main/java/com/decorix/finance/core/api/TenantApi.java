package com.decorix.finance.core.api;

import java.util.UUID;
import java.math.BigDecimal;

public final class TenantApi {
    private TenantApi() {}

    public record CreateTenantRequest(String displayName, String timezone, String memberDisplayName,
                                      BigDecimal plannedIncome) {}

    public record TenantResponse(UUID tenantId, String displayName, String role, String timezone, UUID userId) {}
}
