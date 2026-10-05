package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProductIdentityPolicyTest {
    @Test
    void keyIgnoresCasePunctuationAndRussianYoLikeLegacyProductKey() {
        assertEquals(ProductIdentityPolicy.productKey("Молоко 3,2% 930мл"),
                ProductIdentityPolicy.productKey("МОЛОКО 3.2% 930МЛ"));
        assertEquals(ProductIdentityPolicy.productKey("Пятёрочка хлеб"),
                ProductIdentityPolicy.productKey("хлеб ПЯТЕРОЧКА"));
    }

    @Test
    void keyOmitsOnlyLegacyStopWordsAndRejectsNamesWithoutIdentityTokens() {
        assertEquals("хлеб", ProductIdentityPolicy.productKey("Хлеб 123"));
        assertEquals("молоко", ProductIdentityPolicy.productKey("Молоко молоко"));
        assertEquals("", ProductIdentityPolicy.productKey("Пятёрочка 123"));
        assertThrows(IllegalArgumentException.class, () -> ProductIdentityPolicy.requireProductKey("Пятёрочка 123"));
    }

    @Test
    void sameProductUsesLegacyConservativeTokenOverlapAndSignatureRatio() {
        assertEquals(true, ProductIdentityPolicy.sameProduct("Йогурт Активиа", "Йогурт Активиа 150г"));
        assertEquals(false, ProductIdentityPolicy.sameProduct("Молоко", "Хлеб"));
        assertEquals(false, ProductIdentityPolicy.sameProduct("Молоко 930мл", "Молоко 1л"));
    }
}
