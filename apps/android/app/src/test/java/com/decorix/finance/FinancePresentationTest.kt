package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class FinancePresentationTest {
    @Test fun formatsDecimalStringsWithoutDroppingFractionalDigits() {
        assertEquals("1 234,50 ₽", formatMoney("1234.50", "ru"))
        assertEquals("-12.30 RUB", formatMoney("-12.30", "en"))
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
