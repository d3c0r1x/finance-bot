package com.decorix.finance.core.api;

import java.util.List;
import java.util.UUID;

public final class BankImportApi {
    private BankImportApi() {}

    public record RowSelectionRequest(String transactionType) {}
    public record CategorySelectionRequest(String categoryCode) {}
    public record ConfirmRequest(boolean confirmed) {}
    public record UndoResult(UUID id, String state, long revision, int revertedCount, List<UUID> conflicts) {}

    public record ImportPreview(UUID id, UUID tenantId, String state, long revision, String quality,
                                String parseVersion, String periodStart, String periodEnd,
                                String parsedExpenseTotal, String parsedIncomeTotal,
                                String expectedExpenseTotal, String expectedIncomeTotal,
                                String expenseTotal, String incomeTotal, String refundTotal, String transferTotal,
                                String excludedTotal, int includedCount, int excludedCount,
                                int createdCount, int duplicateCount, List<ImportRow> rows) {}

    public record ImportRow(UUID id, int ordinal, String operationDate, String operationTime, String signedAmount,
                            String amount, String kind, String transactionType, boolean included,
                            String selectionSource, String exclusionReason, boolean duplicate,
                            UUID duplicateOfTransactionId, String outcome, UUID transactionId,
                            String merchant, String description, String cardLast4, String categoryCode,
                            String categorySource, String suggestedCategoryCode, String categoryConfidence,
                            boolean clarificationCandidate) {}
}
