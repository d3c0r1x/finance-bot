package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MerchantCategoryPolicyTest {
    @Test
    void normalizesMerchantNamesAcrossUnicodeAndWhitespace() {
        assertEquals("пятёрочка 24", MerchantCategoryPolicy.normalizeMerchant("  ПЯТЁРОЧКА\u00a0  24 "));
    }

    @Test
    void humanMappingWinsOverCachedModelSuggestion() {
        assertEquals("еда", MerchantCategoryPolicy.resolveCategory("Market",
                Map.of("market", "еда"), Map.of("market", "прочее")));
        assertEquals("транспорт", MerchantCategoryPolicy.resolveCategory("Taxi",
                Map.of(), Map.of("taxi", "транспорт")));
        assertEquals("прочее", MerchantCategoryPolicy.resolveCategory("Unknown", Map.of(), Map.of()));
    }

    @Test
    void asksForAtMostSixUnmappedMerchantsAtThreeHundredRubles() {
        List<MerchantCategoryPolicy.MerchantSpend> spends = List.of(
                new MerchantCategoryPolicy.MerchantSpend("Alpha", new BigDecimal("299.99")),
                new MerchantCategoryPolicy.MerchantSpend("Beta", new BigDecimal("300.00")),
                new MerchantCategoryPolicy.MerchantSpend("Gamma", new BigDecimal("150.00")),
                new MerchantCategoryPolicy.MerchantSpend("Gamma", new BigDecimal("150.00")),
                new MerchantCategoryPolicy.MerchantSpend("Known", new BigDecimal("900.00")));

        var candidates = MerchantCategoryPolicy.clarificationCandidates(spends, Map.of("known", "еда"));

        assertEquals(List.of("beta", "gamma"), candidates.stream()
                .map(MerchantCategoryPolicy.ClarificationCandidate::normalizedMerchant).toList());
        assertEquals(new BigDecimal("300.00"), candidates.get(0).amount());
    }

    @Test
    void rejectsInvalidMerchantAndCategoryValues() {
        assertThrows(IllegalArgumentException.class, () -> MerchantCategoryPolicy.normalizeMerchant("  \n "));
        assertThrows(IllegalArgumentException.class, () -> MerchantCategoryPolicy.resolveCategory("Shop",
                Map.of("shop", "invalid"), Map.of()));
    }

    @Test
    void clarificationCandidatesAreCappedAtSix() {
        var spends = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> new MerchantCategoryPolicy.MerchantSpend("shop " + index,
                        new BigDecimal("500.00"))).toList();

        assertEquals(6, MerchantCategoryPolicy.clarificationCandidates(spends, Map.of()).size());
    }
}
