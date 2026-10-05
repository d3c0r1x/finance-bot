package com.decorix.finance.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DebtForecastTest {
    @Test
    void returnsZeroForClosedDebtAndNullWhenPaymentCannotClearTheDebt() {
        assertThat(DebtService.payoffMonths(BigDecimal.ZERO, BigDecimal.ZERO, null)).isZero();
        assertThat(DebtService.payoffMonths(new BigDecimal("1000.00"), BigDecimal.ZERO, null)).isNull();
        assertThat(DebtService.payoffMonths(new BigDecimal("1000.00"), new BigDecimal("10.00"),
                new BigDecimal("12.00"))).isNull();
        assertThat(DebtService.payoffMonths(new BigDecimal("1000.00"), BigDecimal.ONE, null)).isNull();
    }

    @Test
    void estimatesMonthsWithoutPostingInterestToBalance() {
        Integer months = DebtService.payoffMonths(new BigDecimal("1000.00"),
                new BigDecimal("100.00"), new BigDecimal("12.00"));

        assertThat(months).isBetween(1, 600);
    }
}
