package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Transparent cash guidance. It reports no amount when the member has no income plan. */
public final class CashPlanningPolicy {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal RESERVE_RATE = new BigDecimal("0.10");

    private CashPlanningPolicy() {}

    public static SafeToSpend calculate(BigDecimal plannedIncome, BigDecimal realizedIncome,
                                        BigDecimal monthlyExpenses, LocalDate today, YearMonth month,
                                        List<RecurringProjectionPolicy.Projection> projections) {
        if (plannedIncome == null || plannedIncome.signum() <= 0) return null;
        if (!month.equals(YearMonth.from(today))) return null;
        BigDecimal income = realizedIncome != null && realizedIncome.signum() > 0 ? realizedIncome : plannedIncome;
        String basis = realizedIncome != null && realizedIncome.signum() > 0 ? "actual_income" : "planned_income";
        LocalDate payday = projections.stream().filter(item -> "income".equals(item.type()))
                .map(RecurringProjectionPolicy.Projection::nextDueDate)
                .filter(date -> !date.isBefore(today)).min(LocalDate::compareTo).orElse(null);
        LocalDate horizon = payday == null ? month.atEndOfMonth() : payday;
        int distance = Math.toIntExact(ChronoUnit.DAYS.between(today, horizon));
        int daysRemaining = payday == null ? distance + 1 : Math.max(1, distance);
        BigDecimal promised = BigDecimal.ZERO;
        for (var projection : projections) {
            if (!"expense".equals(projection.type()) || projection.nextDueDate() == null || projection.periodDays() <= 0
                    || projection.nextDueDate().isBefore(today)) continue;
            LocalDate due = projection.nextDueDate();
            for (int count = 0; count < 8 && !due.isAfter(horizon); count++) {
                promised = promised.add(projection.amount());
                due = due.plusDays(projection.periodDays());
            }
        }
        BigDecimal reserve = income.multiply(RESERVE_RATE).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal spent = monthlyExpenses == null ? BigDecimal.ZERO : monthlyExpenses;
        BigDecimal available = income.subtract(spent).subtract(reserve).subtract(promised).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal perDay = available.divide(BigDecimal.valueOf(daysRemaining), 2, RoundingMode.HALF_UP);
        return new SafeToSpend(basis, income.setScale(2, RoundingMode.HALF_EVEN).toPlainString(),
                month.toString(), horizon, daysRemaining, spent.setScale(2, RoundingMode.HALF_EVEN).toPlainString(),
                reserve.toPlainString(), promised.setScale(2, RoundingMode.HALF_EVEN).toPlainString(),
                available.toPlainString(), perDay.toPlainString());
    }

    public record SafeToSpend(String incomeBasis, String incomeBase, String month, LocalDate horizonDate,
                              int daysRemaining, String monthlyExpenses, String reserve, String promisedPayments,
                              String safeTotal, String safePerDay) {}
}
