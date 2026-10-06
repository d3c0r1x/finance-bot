package com.decorix.finance.core.api;

import java.util.List;
import java.util.UUID;

public final class ReceiptRecalculationApi {
    private ReceiptRecalculationApi() {}

    public record Preview(UUID runId, String algorithmVersion, String state, int checked,
                          int updateCount, int changedCount, AdviceRecalculationImpactApi.Impact impact,
                          List<Change> changes) {}

    public record ApplyRequest(UUID runId) {}

    public record ApplyResult(UUID runId, String algorithmVersion, String state, int appliedCount,
                              int changedCount, AdviceRecalculationImpactApi.Impact impact,
                              List<Change> changes) {}

    public record RunSummary(UUID runId, String algorithmVersion, String state, int checked,
                             int updateCount, int changedCount, java.time.Instant createdAt,
                             java.time.Instant appliedAt, AdviceRecalculationImpactApi.Impact impact) {}

    public record HistoryPage(List<RunSummary> runs, String nextCursor) {}

    public record RunDetail(RunSummary run, List<Change> changes, String nextCursor) {}

    public record Change(UUID itemId, String name, String lineSum, long itemVersion,
                         String beforeVerdict, String beforeReason, String beforeAction, String beforeSource,
                         String afterVerdict, String afterReason, String afterAction, String afterSource,
                         boolean changed) {}
}
