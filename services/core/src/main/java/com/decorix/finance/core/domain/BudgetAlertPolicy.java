package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** Identifies a single upward crossing of the monthly 90% or 100% budget threshold. */
public final class BudgetAlertPolicy {
    private static final BigDecimal NINETY_PERCENT = new BigDecimal("0.90");

    private BudgetAlertPolicy() {}

    public static Optional<Alert> crossed(String budgetKey, String limitValue,
                                          String previousSpentValue, String currentSpentValue) {
        if (budgetKey == null || budgetKey.isBlank() || budgetKey.length() > 64) {
            throw new IllegalArgumentException("Budget key is invalid");
        }
        BigDecimal limit = amount(limitValue, false);
        BigDecimal previousSpent = amount(previousSpentValue, true).max(BigDecimal.ZERO);
        BigDecimal currentSpent = amount(currentSpentValue, true).max(BigDecimal.ZERO);
        if (limit.signum() == 0 || currentSpent.compareTo(previousSpent) <= 0) return Optional.empty();

        if (previousSpent.compareTo(limit) < 0 && currentSpent.compareTo(limit) >= 0) {
            return Optional.of(new Alert(budgetKey, "exceeded", format(limit), format(currentSpent)));
        }
        BigDecimal warningThreshold = limit.multiply(NINETY_PERCENT);
        if (previousSpent.compareTo(warningThreshold) < 0 && currentSpent.compareTo(warningThreshold) >= 0) {
            return Optional.of(new Alert(budgetKey, "near", format(limit), format(currentSpent)));
        }
        return Optional.empty();
    }

    private static BigDecimal amount(String value, boolean allowNegative) {
        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.scale() > 2 || !allowNegative && amount.signum() < 0) {
                throw new NumberFormatException("Invalid amount");
            }
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Budget alert values must be money with at most two decimals", error);
        }
    }

    private static String format(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    public record Alert(String budgetKey, String threshold, String limit, String spent) {}
}
