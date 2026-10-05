package com.decorix.finance.core.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.decorix.finance.core.domain.CashPlanningPolicy;
import com.decorix.finance.core.domain.BudgetAlertPolicy;

public final class TransactionApi {
    private TransactionApi() {}

    public record CreateRequest(
            String type,
            String amount,
            String currency,
            String categoryCode,
            String subcategoryCode,
            String description,
            String source,
            Instant occurredAt,
            UUID accountId,
            UUID ownerUserId) {}

    public record UpdateRequest(
            String type,
            String amount,
            String currency,
            String categoryCode,
            String subcategoryCode,
            String description,
            String source,
            Instant occurredAt,
            UUID accountId,
            UUID debtId,
            UUID ownerUserId) {}

    public record TransactionResponse(
            UUID id,
            UUID tenantId,
            String type,
            String amount,
            String currency,
            String categoryCode,
            String subcategoryCode,
            String description,
            String source,
            Instant occurredAt,
            UUID accountId,
            String status,
            long version,
            Instant createdAt,
            String memberName,
            UUID debtId,
            UUID ownerUserId,
            List<BudgetAlertPolicy.Alert> budgetAlerts) {
        public TransactionResponse {
            budgetAlerts = budgetAlerts == null ? List.of() : List.copyOf(budgetAlerts);
        }

        public TransactionResponse(UUID id, UUID tenantId, String type, String amount, String currency,
                                   String categoryCode, String subcategoryCode, String description, String source,
                                   Instant occurredAt, UUID accountId, String status, long version, Instant createdAt,
                                   String memberName, UUID debtId, UUID ownerUserId) {
            this(id, tenantId, type, amount, currency, categoryCode, subcategoryCode, description, source,
                    occurredAt, accountId, status, version, createdAt, memberName, debtId, ownerUserId, List.of());
        }
    }

    public record Page(List<TransactionResponse> items, String nextCursor) {}
    public record DashboardSummary(String month, String currency, String incomeTotal,
                                   String expenseTotal, int transactionCount, LocalDate asOfDate, int daysElapsed,
                                   int daysInMonth, int daysRemaining, String dailyExpensePace,
                                   String projectedExpenseTotal, BudgetApi.RollingFoodStatus rolling7FoodStatus,
                                   CashPlanningPolicy.SafeToSpend safeToSpend) {}

    public record Problem(String type, String title, int status, String code, String traceId) {}
}
