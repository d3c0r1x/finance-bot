package com.decorix.finance.core.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class ReceiptReadingApi {
    private ReceiptReadingApi() {}

    public record ReceiptOcrReading(String text, List<Map<String, Object>> words, String provider,
                                    String modelVersion, String promptVersion, BigDecimal confidence,
                                    String ocrTotal, List<ReceiptLineItem> ocrItems,
                                    ReceiptReconciliation reconciliation, String visionFallbackReason,
                                    String ocrFallbackReason, VisionReceiptReading vision) {}

    public record ReceiptLineItem(String name, String quantity, String unitPrice, String lineSum) {}

    public record ReceiptReconciliation(String algorithmVersion, String decision, String selectedReader,
                                        List<String> mismatchFields, String ocrItemsTotal,
                                        String visionItemsTotal, String allowedDifference,
                                        boolean ocrItemsReconciled, boolean visionItemsReconciled,
                                        List<ReceiptItemEvidence> itemEvidence,
                                        List<ReceiptTopUpSuggestion> suggestedTopUps) {}

    public record ReceiptItemEvidence(int visionOrdinal, Integer ocrOrdinal, String status) {}

    public record ReceiptTopUpSuggestion(int ocrOrdinal, String name, String lineSum) {}

    public record VisionReceiptReading(String store, String date, String total, List<Map<String, Object>> items,
                                       String provider, String modelVersion, String promptVersion,
                                       String fallbackReason) {}
}
