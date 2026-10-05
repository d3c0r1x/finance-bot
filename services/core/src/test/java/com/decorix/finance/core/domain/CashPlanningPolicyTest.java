package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class CashPlanningPolicyTest {
    @Test
    void reservesTenPercentAndIncludesChargesDueOnPayday() {
        LocalDate today = LocalDate.parse("2026-10-01");
        var income = new RecurringProjectionPolicy.Projection("salary", "Зарплата", "income",
                new BigDecimal("95000.00"), today.plusDays(9), 30);
        var todayCharge = new RecurringProjectionPolicy.Projection("rent", "Аренда", "expense",
                new BigDecimal("1000.00"), today, 30);
        var paydayCharge = new RecurringProjectionPolicy.Projection("loan", "Кредит", "expense",
                new BigDecimal("5000.00"), today.plusDays(9), 30);

        var plan = CashPlanningPolicy.calculate(new BigDecimal("100000.00"), new BigDecimal("95000.00"),
                new BigDecimal("50000.00"), today, YearMonth.of(2026, 10),
                List.of(income, todayCharge, paydayCharge));

        assertEquals("2026-10-10", plan.horizonDate().toString());
        assertEquals(9, plan.daysRemaining());
        assertEquals("9500.00", plan.reserve());
        assertEquals("6000.00", plan.promisedPayments());
        assertEquals("29500.00", plan.safeTotal());
        assertEquals("3277.78", plan.safePerDay());
        assertEquals("actual_income", plan.incomeBasis());
    }

    @Test
    void ignoresMissedPaydayAndUsesMonthEndHorizon() {
        LocalDate today = LocalDate.parse("2026-10-25");
        var missedSalary = new RecurringProjectionPolicy.Projection("salary", "Зарплата", "income",
                new BigDecimal("100000.00"), today.minusDays(5), 30);
        var dueOnMonthEnd = new RecurringProjectionPolicy.Projection("bill", "Коммунальные", "expense",
                new BigDecimal("2000.00"), LocalDate.parse("2026-10-31"), 30);
        var overdueCharge = new RecurringProjectionPolicy.Projection("stale", "Старая подписка", "expense",
                new BigDecimal("600.00"), LocalDate.parse("2026-10-20"), 7);

        var plan = CashPlanningPolicy.calculate(new BigDecimal("100000.00"), BigDecimal.ZERO,
                new BigDecimal("20000.00"), today, YearMonth.of(2026, 10), List.of(missedSalary, dueOnMonthEnd, overdueCharge));

        assertEquals("2026-10-31", plan.horizonDate().toString());
        assertEquals(7, plan.daysRemaining());
        assertEquals("10000.00", plan.reserve());
        assertEquals("2000.00", plan.promisedPayments());
        assertEquals("68000.00", plan.safeTotal());
        assertEquals("planned_income", plan.incomeBasis());
    }

    @Test
    void omitsRecommendationWithoutAPlannedIncome() {
        assertNull(CashPlanningPolicy.calculate(null, new BigDecimal("90000.00"), BigDecimal.ZERO,
                LocalDate.parse("2026-10-01"), YearMonth.of(2026, 10), List.of()));
    }

    @Test
    void omitsCurrentCashGuidanceForHistoricalMonth() {
        assertNull(CashPlanningPolicy.calculate(new BigDecimal("90000.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, LocalDate.parse("2026-10-01"), YearMonth.of(2026, 9), List.of()));
    }
}
