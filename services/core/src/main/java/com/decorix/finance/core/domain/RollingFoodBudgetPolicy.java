package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared rolling-seven-day food limit and purchase-week pace policy. */
public final class RollingFoodBudgetPolicy {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal NEAR_LIMIT = new BigDecimal("0.90");
    private static final BigDecimal PACE_ALERT = new BigDecimal("0.25");

    private RollingFoodBudgetPolicy() {}

    public static Status evaluate(LocalDate today, String limitValue, String currentSpendValue,
                                  Map<Integer, String> previousWeekSpend) {
        if (today == null || previousWeekSpend == null) throw new IllegalArgumentException("Food budget inputs are required");
        BigDecimal limit = amount(limitValue);
        BigDecimal currentSpend = amount(currentSpendValue);
        if (limit.signum() < 0 || currentSpend.signum() < 0) {
            throw new IllegalArgumentException("Food limit and spend must be non-negative");
        }

        List<BigDecimal> purchaseWeeks = new ArrayList<>();
        previousWeekSpend.forEach((week, value) -> {
            if (week == null || week < 1 || week > 5) throw new IllegalArgumentException("Food history week is invalid");
            BigDecimal spent = amount(value);
            if (spent.signum() < 0) throw new IllegalArgumentException("Food history spend must be non-negative");
            if (spent.signum() > 0) purchaseWeeks.add(spent);
        });

        String limitStatus = limit.signum() == 0 ? "disabled"
                : currentSpend.compareTo(limit) >= 0 ? "exceeded"
                : currentSpend.compareTo(limit.multiply(NEAR_LIMIT)) >= 0 ? "near" : "normal";
        String remaining = limit.signum() == 0 ? null : limit.subtract(currentSpend).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
        if (purchaseWeeks.size() < 2) {
            return new Status(today.minusDays(6), today, format(limit), format(currentSpend), remaining,
                    limitStatus, null, purchaseWeeks.size(), "insufficient_history", null);
        }

        purchaseWeeks.sort(BigDecimal::compareTo);
        BigDecimal usual = purchaseWeeks.get(purchaseWeeks.size() / 2).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal share = currentSpend.subtract(usual).divide(usual, 3, RoundingMode.HALF_EVEN);
        String paceStatus = share.compareTo(PACE_ALERT) >= 0 ? "over"
                : share.compareTo(PACE_ALERT.negate()) <= 0 ? "under" : "normal";
        return new Status(today.minusDays(6), today, format(limit), format(currentSpend), remaining,
                limitStatus, format(usual), purchaseWeeks.size(), paceStatus, share.toPlainString());
    }

    private static BigDecimal amount(String value) {
        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.scale() > 2 || amount.precision() > 20) throw new NumberFormatException("out of range");
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | NullPointerException | ArithmeticException ex) {
            throw new IllegalArgumentException("Food budget amounts must be decimals with at most two places", ex);
        }
    }

    private static String format(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    public record Status(LocalDate fromDate, LocalDate toDate, String limit, String spent, String remaining,
                         String limitStatus, String usualWeeklySpend, int historyWeeks,
                         String paceStatus, String paceShare) {}
}
