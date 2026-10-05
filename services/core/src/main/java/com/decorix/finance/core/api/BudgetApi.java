package com.decorix.finance.core.api;

import java.util.Map;
import java.time.Instant;
import java.util.UUID;

public final class BudgetApi {
    private BudgetApi() {}

    public record Overview(
            String currency,
            String month,
            Map<String, String> familyLimits,
            Map<String, String> personalOverrides,
            Map<String, String> effectiveLimits,
            Map<String, String> monthlySpent,
            Map<String, String> limitStatus,
            Map<String, Long> familyVersions,
            Map<String, Long> personalVersions,
            String familyTotalLimit,
            String personalTotalOverride,
            String effectiveTotalLimit,
            String totalMonthlySpent,
            String totalLimitStatus,
            long familyTotalVersion,
            long personalTotalVersion,
            String rolling7FoodLimit,
            String personalRolling7FoodOverride,
            String effectiveRolling7FoodLimit,
            String rolling7FoodSpent,
            String rolling7FoodLimitStatus,
            long familyRolling7FoodVersion,
            long personalRolling7FoodVersion,
            RollingFoodStatus rolling7FoodStatus) {}

    public record RollingFoodStatus(java.time.LocalDate fromDate, java.time.LocalDate toDate,
            String limit, String spent, String remaining, String limitStatus,
            String usualWeeklySpend, int historyWeeks, String paceStatus, String paceShare) {}

    public record UpdateRequest(String scope, String amount, String period) {}
    public record BudgetProposalRequest(String monthlyIncome) {}
    public record BudgetProposalResponse(UUID id, String monthlyIncome, String totalLimit,
            Map<String, String> limits, Map<String, Long> baseVersions, long baseTotalVersion,
            String status, Instant createdAt, String proposalSource, int historyDays,
            String modelVersion, String promptVersion) {}
}
