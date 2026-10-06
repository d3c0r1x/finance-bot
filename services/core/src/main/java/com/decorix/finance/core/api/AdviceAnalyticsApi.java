package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class AdviceAnalyticsApi {
    private AdviceAnalyticsApi() {}

    public record F43Request(String inputWatermark, Instant asOf, String timeZone, String income,
                             String monthlyLimit, List<F43Item> items, List<F43Recalculation> recalculations) {}
    public record F43Item(String itemId, String productKey, String name, String lineSum, String verdict,
                          String advice, boolean adviceGiven, boolean allowed, Instant purchasedAt) {}
    public record F43Recalculation(Instant changedAt, int changedItemCount, String optionalSpendDelta) {}
    public record Job(UUID id, String state, String inputWatermark, String algorithmVersion,
                      String completeness, String errorCode, JsonNode report, Instant updatedAt) {}
    public record ClaimedJob(UUID id, UUID tenantId, UUID ownerUserId, String inputWatermark,
                             String algorithmVersion, UUID leaseToken, int attemptCount, F43Request input) {}
    public record JobResult(UUID leaseToken, String inputWatermark, String algorithmVersion,
                            JsonNode report, String errorCode) {}
    public record JobInput(String inputHash, String inputWatermark, String algorithmVersion,
                           F43Request payload, boolean overflow) {}
    public record Error(String reasonCode) {}
}
