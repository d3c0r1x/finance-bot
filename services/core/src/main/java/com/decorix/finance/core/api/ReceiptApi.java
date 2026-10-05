package com.decorix.finance.core.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ReceiptApi {
    private ReceiptApi() {}

    public record CreateRequest(UUID documentId, String cashTotal, String merchant, LocalDate receiptDate,
                                List<ItemInput> items) {}

    public record CategorySelection(String categoryCode) {}

    public record ItemInput(String name, String quantity, String unitPrice, String lineSum) {}

    public record ReceiptResponse(UUID id, UUID tenantId, UUID documentId, String state, long version,
                                  UUID transactionId, String currency, String cashTotal, String itemsTotal, String merchant,
                                  LocalDate receiptDate, String selectedReader, String categoryCode,
                                  String categorySource, String categoryAlgorithmVersion, String alcoholShare,
                                  String leisureShare, boolean leisure, String duplicateDecision,
                                  UUID duplicateOfReceiptId, List<ReceiptItem> items,
                                  int itemCount,
                                  Instant createdAt) {}

    public record ReceiptItem(UUID id, String name, String quantity, String unitPrice, String lineSum,
                              String productKey, String provenance, Double confidence, String categoryCode, String verdict,
                              String advice, String reviewReason, String reviewAction, String verdictSource,
                              String reviewProvider, String reviewModelVersion, String reviewPromptVersion,
                              String reviewAlgorithmVersion, long version) {}

    public record ReceiptItemPage(List<ReceiptItem> items, int page, int totalItems, boolean hasMore) {}

    public record RepeatWarning(UUID itemId, String name, String productKey, String verdict, String title,
                                int count, String lastSum, String advice) {}

    public record RepeatWarnings(List<RepeatWarning> warnings) {}

    public record DuplicateDecisionSelection(String decision, UUID duplicateReceiptId) {}

    public record DuplicateCandidate(UUID id, String cashTotal, String merchant, Instant createdAt) {}

    public record DuplicateCandidates(UUID receiptId, String decision, List<DuplicateCandidate> candidates) {}
}
