package com.decorix.finance.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MonthlyPacePolicyTest {
    @Test
    void projectsCurrentMonthFromCompletedCalendarDaysAndHandlesLeapFebruaryAndDecember() {
        assertThat(MonthlyPacePolicy.projectedSpend("100.00", 10, 29)).isEqualTo("290.00");
        assertThat(MonthlyPacePolicy.projectedSpend("100.00", 10, 31)).isEqualTo("310.00");
    }

    @Test
    void hasNoProjectionOnFirstDayOrWithoutSpending() {
        assertThat(MonthlyPacePolicy.projectedSpend("100.00", 1, 31)).isNull();
        assertThat(MonthlyPacePolicy.projectedSpend("0.00", 15, 30)).isNull();
    }

    @Test
    void rejectsInvalidCalendarInputsAndMoney() {
        assertThatThrownBy(() -> MonthlyPacePolicy.projectedSpend("-1.00", 2, 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonthlyPacePolicy.projectedSpend("1.00", 31, 30))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
