package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/** A positive, exact amount in the initial RUB settlement scale. */
public final class MoneyAmount {
    private static final Pattern CANONICAL_DECIMAL = Pattern.compile(
            "^(?:0\\.(?:[0-9]?[1-9]|[1-9][0-9])|[1-9][0-9]{0,17}(?:\\.[0-9]{1,2})?)$");

    private final BigDecimal value;

    private MoneyAmount(BigDecimal value) {
        this.value = value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static MoneyAmount parse(String input) {
        if (input == null || !CANONICAL_DECIMAL.matcher(input).matches()) {
            throw new IllegalArgumentException("amount must be a positive decimal string with at most two fractional digits");
        }
        return new MoneyAmount(new BigDecimal(input));
    }

    public BigDecimal value() {
        return value;
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof MoneyAmount amount && value.equals(amount.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
