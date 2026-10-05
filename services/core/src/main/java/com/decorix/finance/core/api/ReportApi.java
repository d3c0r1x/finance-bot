package com.decorix.finance.core.api;

import java.time.LocalDate;
import java.util.Map;

public final class ReportApi {
    private ReportApi() {}

    public record Report(String period, String scope, LocalDate fromDate, LocalDate toDate,
                         LocalDate asOfDate, String timezone, String currency,
                         String incomeTotal, String expenseTotal, String debtPaymentTotal, String refundTotal,
                          int transactionCount, Map<String, String> expenseByCategory,
                          Map<String, String> expenseByDay, Integer weekendSharePercent, String monthlyBudgetLimit,
                          String monthlyBudgetRemaining, BudgetApi.RollingFoodStatus rolling7FoodStatus) {}
}
