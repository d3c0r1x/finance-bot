package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class FinancePresentationTest {
    @Test fun formatsDecimalStringsWithoutDroppingFractionalDigits() {
        assertEquals("1 234,50 ₽", formatMoney("1234.50", "ru"))
        assertEquals("-12.30 RUB", formatMoney("-12.30", "en"))
    }

    @Test fun formatsShoppingPurchaseCountsWithRussianDeclension() {
        assertEquals("1 покупка", formatShoppingPurchaseCount(1, "ru"))
        assertEquals("2 покупки", formatShoppingPurchaseCount(2, "ru"))
        assertEquals("3 покупки", formatShoppingPurchaseCount(3, "ru"))
        assertEquals("4 покупки", formatShoppingPurchaseCount(4, "ru"))
        assertEquals("5 покупок", formatShoppingPurchaseCount(5, "ru"))
        assertEquals("11 покупок", formatShoppingPurchaseCount(11, "ru"))
        assertEquals("21 покупка", formatShoppingPurchaseCount(21, "ru"))
        assertEquals("22 покупки", formatShoppingPurchaseCount(22, "ru"))
        assertEquals("25 покупок", formatShoppingPurchaseCount(25, "ru"))
        assertEquals("5 purchases", formatShoppingPurchaseCount(5, "en"))
    }

    @Test fun translatesCoreCodesAndUsesSafeFallbackForFutureCodes() {
        assertEquals("Недостаточно истории", formatSemanticStatus("paceStatus", "insufficient_history", "ru"))
        assertEquals("Near limit", formatSemanticStatus("limitStatus", "near", "en"))
        assertEquals("Неизвестный статус", formatSemanticStatus("paceStatus", "future_code", "ru"))
    }

    @Test fun localizesKnownDebtForecastBasisAndHidesUnknownServerText() {
        val coreBasis = "Fixed minimum payment with monthly compound estimate; no interest is posted to ledger."
        assertEquals(
            "Прогноз по фиксированному минимальному платежу; проценты рассчитываются ежемесячно и не добавляются к остатку.",
            formatDebtForecastBasis(coreBasis, "ru"),
        )
        assertEquals(
            "Estimate uses a fixed minimum payment with monthly interest; interest is not posted to the ledger.",
            formatDebtForecastBasis(coreBasis, "en"),
        )
        assertEquals("Основание прогноза недоступно", formatDebtForecastBasis("future server copy", "ru"))
        assertEquals("Forecast basis unavailable", formatDebtForecastBasis("future server copy", "en"))
    }

    @Test fun priceChangeBarUsesAbsoluteCorePercentAndCapsOnlyTheVisualScale() {
        assertEquals(0.1f, priceChangeMagnitudeFraction("10.00"), 0.001f)
        assertEquals(0.1f, priceChangeMagnitudeFraction("-10.00"), 0.001f)
        assertEquals(1f, priceChangeMagnitudeFraction("250.00"), 0f)
        assertEquals(0f, priceChangeMagnitudeFraction("not-a-number"), 0f)
    }
}
