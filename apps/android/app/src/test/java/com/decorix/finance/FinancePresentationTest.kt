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
}
