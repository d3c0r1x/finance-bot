package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReceiptBasketPolicyTest {
    @Test
    void junkRuleOverridesUsefulModelVerdictAndProvidesGroundedAdvice() {
        UUID itemId = UUID.randomUUID();
        var result = ReceiptBasketPolicy.apply(List.of(
                new ReceiptBasketPolicy.Proposal(itemId, "Чипсы 150 г", "useful", "полезный перекус", "есть каждый день")));

        assertEquals(new ReceiptBasketPolicy.Review(itemId, "harmful", "снек, много калорий",
                "взять маленькую пачку до 80 г", "rule"), result.get(0));
    }

    @Test
    void ordinaryFoodCannotBeMarkedUnnecessaryByModel() {
        UUID itemId = UUID.randomUUID();
        var result = ReceiptBasketPolicy.apply(List.of(
                new ReceiptBasketPolicy.Proposal(itemId, "Макароны перья", "unnecessary", "лишняя покупка", "не брать")));

        assertEquals("neutral", result.get(0).verdict());
        assertEquals("rule", result.get(0).source());
        assertEquals("", result.get(0).reason());
    }

    @Test
    void missingModelOpinionGetsDefaultNeutralAndUnknownVerdictGetsSafeDefault() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var result = ReceiptBasketPolicy.apply(List.of(
                new ReceiptBasketPolicy.Proposal(first, "Молоко", null, null, null),
                new ReceiptBasketPolicy.Proposal(second, "Хлеб", "maybe", "непонятно", "непонятно")));

        assertEquals(new ReceiptBasketPolicy.Review(first, "neutral", "", "", "default"), result.get(0));
        assertEquals(new ReceiptBasketPolicy.Review(second, "neutral", "", "", "default"), result.get(1));
    }

    @Test
    void duplicateModelAdviceIsRemovedAcrossDifferentProductsButKeptForSameProduct() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var result = ReceiptBasketPolicy.apply(List.of(
                new ReceiptBasketPolicy.Proposal(first, "Молоко", "harmful", "дорого", "купить реже"),
                new ReceiptBasketPolicy.Proposal(second, "Кефир", "harmful", "дорого", "купить реже")));

        assertEquals("", result.get(0).reason());
        assertEquals("", result.get(1).reason());
        var sameProduct = ReceiptBasketPolicy.apply(List.of(
                new ReceiptBasketPolicy.Proposal(first, "Молоко", "harmful", "дорого", "купить реже"),
                new ReceiptBasketPolicy.Proposal(second, "Молоко", "harmful", "дорого", "купить реже")));
        assertEquals("дорого", sameProduct.get(0).reason());
        assertEquals("дорого", sameProduct.get(1).reason());
    }

    @Test
    void duplicateItemIdsAreRejectedToKeepAdviceBoundToOneReceiptLine() {
        UUID itemId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> ReceiptBasketPolicy.apply(List.of(
                new ReceiptBasketPolicy.Proposal(itemId, "Молоко", "neutral", "", ""),
                new ReceiptBasketPolicy.Proposal(itemId, "Хлеб", "neutral", "", ""))));
    }
}
