package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiptCategoryPolicyTest {
    @Test
    void alcoholAtTenPercentPromotesTheReceiptToLeisure() {
        var result = ReceiptCategoryPolicy.classify("еда", new BigDecimal("1000.00"), List.of(
                new ReceiptCategoryPolicy.Item("Пиво 0.5л", new BigDecimal("100.00")),
                new ReceiptCategoryPolicy.Item("Молоко", new BigDecimal("900.00"))));

        assertEquals("досуг", result.category());
        assertEquals("rule", result.categorySource());
        assertEquals("0.1000", result.alcoholShare().toPlainString());
        assertEquals("0.1000", result.leisureShare().toPlainString());
    }

    @Test
    void leisureGoodsAtTwentyFivePercentPromoteTheReceiptAndSmallerSharesDoNot() {
        var atBoundary = ReceiptCategoryPolicy.classify("еда", new BigDecimal("1000.00"), List.of(
                new ReceiptCategoryPolicy.Item("Чипсы", new BigDecimal("250.00")),
                new ReceiptCategoryPolicy.Item("Молоко", new BigDecimal("750.00"))));
        var below = ReceiptCategoryPolicy.classify("еда", new BigDecimal("1000.00"), List.of(
                new ReceiptCategoryPolicy.Item("Чипсы", new BigDecimal("249.99")),
                new ReceiptCategoryPolicy.Item("Молоко", new BigDecimal("750.01"))));

        assertEquals("досуг", atBoundary.category());
        assertEquals("0.2500", atBoundary.leisureShare().toPlainString());
        assertEquals("еда", below.category());
        assertEquals("model", below.categorySource());
        assertEquals(true, below.leisure());
    }

    @Test
    void missingAmountsOrCashTotalNeverInventSharesOrReplaceTheCategory() {
        var missingItemAmount = ReceiptCategoryPolicy.classify("еда", new BigDecimal("100.00"), List.of(
                new ReceiptCategoryPolicy.Item("Пиво", null)));
        var missingCashTotal = ReceiptCategoryPolicy.classify("еда", null, List.of(
                new ReceiptCategoryPolicy.Item("Пиво", new BigDecimal("50.00"))));

        assertEquals("еда", missingItemAmount.category());
        assertNull(missingItemAmount.alcoholShare());
        assertNull(missingItemAmount.leisureShare());
        assertEquals("еда", missingCashTotal.category());
        assertNull(missingCashTotal.alcoholShare());
    }

    @Test
    void missingCategoryEvidenceRemainsUnknownInsteadOfInventingADefault() {
        var result = ReceiptCategoryPolicy.classify(null, null, List.of());

        assertNull(result.category());
        assertEquals("unknown", result.categorySource());
        assertNull(result.alcoholShare());
        assertNull(result.leisureShare());
    }

    @Test
    void manualCategoryOverrideWinsAndIsMarkedHuman() {
        var inferred = ReceiptCategoryPolicy.classify("еда", new BigDecimal("100.00"), List.of(
                new ReceiptCategoryPolicy.Item("Пиво", new BigDecimal("20.00"))));
        var selected = ReceiptCategoryPolicy.applyManualOverride(inferred, "еда");

        assertEquals("еда", selected.category());
        assertEquals("human", selected.categorySource());
        assertEquals("receipt-category.v1", selected.algorithmVersion());
    }

    @Test
    void nullItemIsRejectedAsInvalidReceiptEvidence() {
        assertThrows(IllegalArgumentException.class, () -> ReceiptCategoryPolicy.classify(
                "еда", new BigDecimal("100.00"), Arrays.asList((ReceiptCategoryPolicy.Item) null)));
    }
}
