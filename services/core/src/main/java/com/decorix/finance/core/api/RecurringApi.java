package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RecurringApi {
    private RecurringApi() {}

    public record RecurringRequest(String tenantId, String ownerUserId, Instant asOf, String timeZone) {}

    public record RecurringProjection(String algorithmVersion, String completeness, String timeZone, Instant asOf,
                                      List<RecurringSeries> expenseSeries, List<RecurringSeries> incomeSeries,
                                      List<RecurringSeries> dueSoon, List<RecurringSeries> overdue,
                                      RecurringSeries nextIncome, String monthlyExpenseEstimate,
                                      Map<String, String> monthlyExpenseEstimates,
                                      List<RecurringSeries> mutedSeries) {}

    public record RecurringSeries(String id, String key, String name, String category, String type, String currency,
                                  String amount, String minAmount, String maxAmount, String periodCode,
                                  int periodDays, int minIntervalDays, int maxIntervalDays, int occurrences,
                                  String lastDate, String nextDate, int daysUntil) {}
}
