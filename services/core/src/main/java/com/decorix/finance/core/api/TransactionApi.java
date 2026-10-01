package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TransactionApi {
    private TransactionApi() {}

    public record CreateRequest(
            String type,
            String amount,
            String currency,
            String categoryCode,
            String subcategoryCode,
            String description,
            Instant occurredAt,
            UUID accountId) {}

    public record TransactionResponse(
            UUID id,
            UUID tenantId,
            String type,
            String amount,
            String currency,
            String categoryCode,
            String subcategoryCode,
            String description,
            Instant occurredAt,
            UUID accountId,
            String status,
            long version,
            Instant createdAt) {}

    public record Page(List<TransactionResponse> items, String nextCursor) {}

    public record Problem(String type, String title, int status, String code, String traceId) {}
}
