package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MerchantReclassificationApi {
    private MerchantReclassificationApi() {}

    public record PreviewRequest(String merchant) {}

    public record Candidate(UUID transactionId, long version, String currentCategoryCode,
                            String amount, Instant occurredAt, String description) {}

    public record Preview(String merchant, String normalizedMerchant, String categoryCode,
                          List<Candidate> candidates) {}

    public record CandidateSelection(UUID transactionId, long version, String currentCategoryCode) {}

    public record ApplyRequest(String merchant, String categoryCode, List<CandidateSelection> candidates) {}

    public record ApplyResult(String merchant, String categoryCode, int changedCount, List<UUID> transactionIds) {}
}
