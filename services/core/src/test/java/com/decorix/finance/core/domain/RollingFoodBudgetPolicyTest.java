package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RollingFoodBudgetPolicyTest {
    @Test
    void fixedLimitWorksWithoutHistoricalWeeks() {
        var status = RollingFoodBudgetPolicy.evaluate(LocalDate.parse("2026-10-01"), "1000.00", "400.00", Map.of());

        assertEquals(LocalDate.parse("2026-09-25"), status.fromDate());
        assertEquals(LocalDate.parse("2026-10-01"), status.toDate());
        assertEquals("600.00", status.remaining());
        assertEquals("normal", status.limitStatus());
        assertEquals(0, status.historyWeeks());
        assertNull(status.usualWeeklySpend());
        assertEquals("insufficient_history", status.paceStatus());
    }

    @Test
    void historyUsesUpperMedianOfPurchaseWeeksAndWarnsAtTwentyFivePercent() {
        var status = RollingFoodBudgetPolicy.evaluate(LocalDate.parse("2026-10-01"), "1000.00", "500.00",
                Map.of(1, "200.00", 2, "400.00", 3, "0.00"));

        assertEquals("400.00", status.usualWeeklySpend());
        assertEquals(2, status.historyWeeks());
        assertEquals("0.250", status.paceShare());
        assertEquals("over", status.paceStatus());
    }

    @Test
    void limitAlertAndDisabledStatusDoNotDependOnHistoricalPace() {
        var near = RollingFoodBudgetPolicy.evaluate(LocalDate.parse("2026-10-01"), "1000.00", "900.00", Map.of());
        var exceeded = RollingFoodBudgetPolicy.evaluate(LocalDate.parse("2026-10-01"), "1000.00", "1000.00", Map.of());
        var disabled = RollingFoodBudgetPolicy.evaluate(LocalDate.parse("2026-10-01"), "0.00", "1200.00", Map.of());

        assertEquals("near", near.limitStatus());
        assertEquals("exceeded", exceeded.limitStatus());
        assertEquals("disabled", disabled.limitStatus());
        assertNull(disabled.remaining());
        assertEquals("insufficient_history", disabled.paceStatus());
    }

    @Test
    void paceCanShowSavingAndRejectInvalidAmounts() {
        var under = RollingFoodBudgetPolicy.evaluate(LocalDate.parse("2026-10-01"), "1000.00", "300.00",
                Map.of(1, "400.00", 2, "400.00"));

        assertEquals("under", under.paceStatus());
        assertThrows(IllegalArgumentException.class,
                () -> RollingFoodBudgetPolicy.evaluate(LocalDate.parse("2026-10-01"), "-1.00", "0.00", Map.of()));
    }
}
