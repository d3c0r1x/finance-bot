package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.UUID;

public final class TransactionDraftApi {
    private TransactionDraftApi() {}

    public record CreateRequest(String text) {}
    public record UpdateRequest(String type, String amount, String currency, String categoryCode,
                                String subcategoryCode, String description, Instant occurredAt, UUID debtId) {}
    public record DraftResponse(UUID id, UUID tenantId, String type, String amount, String currency,
                                String categoryCode, String subcategoryCode, String description,
                                Instant occurredAt, UUID debtId, String state, long version, String provider,
                                String modelVersion, String promptVersion, Instant createdAt) {}
}
