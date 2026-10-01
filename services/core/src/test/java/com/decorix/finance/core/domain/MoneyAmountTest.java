package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class MoneyAmountTest {
    @ParameterizedTest
    @MethodSource("validAmounts")
    void acceptsCanonicalPositiveAmountsAndNormalizesScale(String input, String expected) {
        MoneyAmount amount = MoneyAmount.parse(input);

        assertEquals(new BigDecimal(expected), amount.value());
        assertEquals(expected, amount.toString());
    }

    static Stream<Arguments> validAmounts() {
        return Stream.of(
                arguments("0.01", "0.01"),
                arguments("1", "1.00"),
                arguments("1250.5", "1250.50"),
                arguments("999999999999999999.99", "999999999999999999.99"));
    }

    @ParameterizedTest
    @MethodSource("invalidAmounts")
    void rejectsZeroNegativeMalformedAndUnrepresentableAmounts(String input) {
        assertThrows(IllegalArgumentException.class, () -> MoneyAmount.parse(input));
    }

    static Stream<String> invalidAmounts() {
        return Stream.of("0", "0.00", "-1", "+1", "01.00", "1,20", "1.234", "1e3", "", " ");
    }

    @Test
    void rejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> MoneyAmount.parse(null));
    }
}
