package com.decorix.finance.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class BudgetAlertPolicyTest {
    @Test
    void reportsNinetyAndOneHundredPercentOnlyWhenSpendCrossesThreshold() {
        assertThat(BudgetAlertPolicy.crossed("еда", "20000.00", "17800.00", "18000.00"))
                .contains(new BudgetAlertPolicy.Alert("еда", "near", "20000.00", "18000.00"));
        assertThat(BudgetAlertPolicy.crossed("еда", "20000.00", "19800.00", "20000.00"))
                .contains(new BudgetAlertPolicy.Alert("еда", "exceeded", "20000.00", "20000.00"));
    }

    @Test
    void skipsDisabledRepeatedOrDownwardCrossingsAndChoosesHighestThreshold() {
        assertThat(BudgetAlertPolicy.crossed("еда", "0.00", "0.00", "1000.00")).isEmpty();
        assertThat(BudgetAlertPolicy.crossed("еда", "20000.00", "18000.00", "19000.00")).isEmpty();
        assertThat(BudgetAlertPolicy.crossed("еда", "20000.00", "20000.00", "19000.00")).isEmpty();
        assertThat(BudgetAlertPolicy.crossed("еда", "20000.00", "1000.00", "22000.00"))
                .contains(new BudgetAlertPolicy.Alert("еда", "exceeded", "20000.00", "22000.00"));
    }
}
