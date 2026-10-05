package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.UUID;

public final class ReceiptProcessingApi {
    private ReceiptProcessingApi() {}

    public record ReceiptProcessingJob(UUID id, UUID tenantId, UUID documentId, String state, String stage,
                                       int progressPercent, int attemptCount, boolean retryable, String errorCode,
                                       UUID receiptId, Instant createdAt, Instant updatedAt) {}
}
