package com.decorix.finance.core.api;

import java.util.List;
import java.util.UUID;

public final class ReceiptRecalculationApi {
    private ReceiptRecalculationApi() {}

    public record Preview(UUID runId, String algorithmVersion, String state, int checked,
                          int updateCount, int changedCount, List<Change> changes) {}

    public record ApplyRequest(UUID runId) {}

    public record ApplyResult(UUID runId, String algorithmVersion, String state, int appliedCount,
                              int changedCount, List<Change> changes) {}

    public record Change(UUID itemId, String name, String lineSum, long itemVersion,
                         String beforeVerdict, String beforeReason, String beforeAction, String beforeSource,
                         String afterVerdict, String afterReason, String afterAction, String afterSource,
                         boolean changed) {}
}
