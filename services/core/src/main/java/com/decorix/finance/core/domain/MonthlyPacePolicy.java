package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Calendar-day monthly pace. Forecast is informational and never mutates the ledger. */
public final class MonthlyPacePolicy {
    private MonthlyPacePolicy() {}

    public static String projectedSpend(String actualSpend, int elapsedDays, int daysInMonth) {
        BigDecimal actual = amount(actualSpend);
        if (daysInMonth < 1 || daysInMonth > 31 || elapsedDays < 0 || elapsedDays > daysInMonth) {
            throw new IllegalArgumentException("Invalid month day range");
        }
        if (elapsedDays < 2 || actual.signum() == 0) return null;
        return actual.multiply(BigDecimal.valueOf(daysInMonth))
                .divide(BigDecimal.valueOf(elapsedDays), 2, RoundingMode.HALF_EVEN).toPlainString();
    }

    public static String averageDailySpend(String actualSpend, int elapsedDays) {
        BigDecimal actual = amount(actualSpend);
        if (elapsedDays < 0 || elapsedDays > 31) throw new IllegalArgumentException("Invalid elapsed day count");
        if (elapsedDays == 0) return null;
        return actual.divide(BigDecimal.valueOf(elapsedDays), 2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static BigDecimal amount(String value) {
        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() < 0 || amount.scale() > 2) throw new NumberFormatException("Invalid amount");
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Spend must be a non-negative amount with at most two decimals", ex);
        }
    }
}
