package com.decorix.finance.core.api;

import java.util.List;

public final class MerchantMappingApi {
    private MerchantMappingApi() {}

    public record MappingRequest(String merchant, String categoryCode) {}
    public record Mapping(String merchant, String normalizedMerchant, String categoryCode,
                          String decisionSource, String decisionVersion, String updatedAt) {}
    public record MappingList(List<Mapping> mappings) {}
}
