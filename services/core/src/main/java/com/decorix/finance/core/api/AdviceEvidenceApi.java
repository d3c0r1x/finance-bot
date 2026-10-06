package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.List;

public final class AdviceEvidenceApi {
    private AdviceEvidenceApi() {}

    public record Request(List<EvidenceLine> items) {}

    public record EvidenceLine(String itemId, String productKey, String name, String lineSum,
                               String verdict, String verdictSource, String advice,
                               Instant purchasedAt, long itemVersion) {}

    public record GoResponse(String algorithmVersion, String inputVersion, List<EvidenceGroup> groups) {}

    public record EvidenceGroup(String productKey, String productName, int count, String amount,
                                int missingAmountCount, int ruleCount, int modelCount,
                                int unmarkedCount, boolean modelOnly, String latestVerdict,
                                String latestAdvice, Instant lastPurchasedAt) {}

    public record Report(boolean available, String reasonCode, String algorithmVersion,
                         String inputVersion, List<EvidenceGroup> banned, List<EvidenceGroup> guesses) {
        public static Report unavailable(String reasonCode) {
            return new Report(false, reasonCode, null, null, List.of(), List.of());
        }
    }
}
