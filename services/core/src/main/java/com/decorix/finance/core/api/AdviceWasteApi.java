package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AdviceWasteApi {
    private AdviceWasteApi() {}

    public record Request(String fromDate, String toDate, Instant asOf, String timeZone, List<ReceiptLine> items) {}

    public record ReceiptLine(String itemId, String productKey, String name, String lineSum, String verdict,
                              String verdictSource, Instant purchasedAt, boolean allowed,
                              long itemVersion, long decisionVersion) {}

    public record WasteReport(boolean available, String reasonCode, String algorithmVersion, String completeness,
                              Instant asOf, String inputVersion, String fromDate, String toDate,
                              String reviewedSpend, String optionalSpend, String optionalShare,
                              int reviewedItemCount, int optionalItemCount, int missingAmountCount,
                              List<VerdictTotal> byVerdict, Map<String, String> bySource,
                              List<ItemSummary> topItems, List<ProductGroup> repeats,
                              List<ProductGroup> corrected, Map<String, String> optionalByDay) {
        public static WasteReport unavailable(Request request, String reasonCode, String completeness) {
            return new WasteReport(false, reasonCode, null, completeness, request.asOf(), null,
                    request.fromDate(), request.toDate(), null, null, null, 0, 0, 0,
                    List.of(), Map.of(), List.of(), List.of(), List.of(), Map.of());
        }
    }

    public record VerdictTotal(String verdict, String amount, int count) {}
    public record ItemSummary(String itemId, String name, String amount, String verdict, String source) {}
    public record ProductGroup(String productKey, String productName, int count, String amount) {}
}
