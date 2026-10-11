package com.decorix.finance

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** DTO boundary checks for the Core PriceComparison contract (price-projection.v1). */
class FinancePriceComparisonModelsTest {
    @Test
    fun priceComparisonPreservesExactCoreValuesAndOrderedReceiptHistory() {
        val result = FinanceModels.productPriceComparison(JSONObject(comparisonJson(
            hasBaseline = true,
            baselineUnitPrice = "100.000000",
            change = "12.000000",
            relative = "0.120000",
            signal = true,
            direction = "up",
            priorPurchases = 2,
            productName = "Coffee",
            currentUnitPrice = "112.000000",
            history = """
                {"receiptId":"00000000-0000-0000-0000-000000000001","itemId":"00000000-0000-0000-0000-000000000011","purchasedAt":"2026-08-01T10:00:00Z","merchant":"North Shop","name":"Coffee","unitPrice":"100.000000","current":false},
                {"receiptId":"00000000-0000-0000-0000-000000000002","itemId":"00000000-0000-0000-0000-000000000012","purchasedAt":"2026-09-01T10:00:00Z","merchant":null,"name":"Coffee","unitPrice":"112.000000","current":true}
            """.trimIndent(),
        )), "00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000012")

        assertEquals("price-projection.v1", result.algorithmVersion)
        assertEquals("Coffee", result.productName)
        assertTrue(result.hasBaseline)
        assertEquals("112.000000", result.currentUnitPrice)
        assertEquals("100.000000", result.baselineUnitPrice)
        assertEquals("12.000000", result.change)
        assertEquals("0.120000", result.relative)
        assertTrue(result.signal)
        assertEquals("up", result.direction)
        assertEquals(2, result.priorPurchases)
        assertEquals(2, result.history.size)
        assertEquals("100.000000", result.history[0].unitPrice)
        assertFalse(result.history[0].current)
        assertEquals("North Shop", result.history[0].merchant)
        assertEquals("112.000000", result.history[1].unitPrice)
        assertTrue(result.history[1].current)
        assertNull(result.history[1].merchant)
    }

    @Test
    fun priceComparisonWithoutEarlierPurchasesKeepsDerivedValuesNull() {
        val result = FinanceModels.productPriceComparison(JSONObject(comparisonJson(
            hasBaseline = false,
            baselineUnitPrice = null,
            change = null,
            relative = null,
            signal = false,
            direction = null,
            priorPurchases = 0,
            history = """
                {"receiptId":"00000000-0000-0000-0000-000000000001","itemId":"00000000-0000-0000-0000-000000000011","purchasedAt":"2026-08-30T10:00:00Z","merchant":"Corner Shop","name":"Tea","unitPrice":"0.000000","current":false},
                {"receiptId":"00000000-0000-0000-0000-000000000002","itemId":"00000000-0000-0000-0000-000000000012","purchasedAt":"2026-09-01T10:00:00Z","merchant":"Corner Shop","name":"Tea","unitPrice":"8.500000","current":true}
            """.trimIndent(),
            productName = "Tea",
            currentUnitPrice = "8.500000",
        )), "00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000012")

        assertFalse(result.hasBaseline)
        assertEquals("8.500000", result.currentUnitPrice)
        assertNull(result.baselineUnitPrice)
        assertNull(result.change)
        assertNull(result.relative)
        assertFalse(result.signal)
        assertNull(result.direction)
        assertEquals(0, result.priorPurchases)
        assertEquals(2, result.history.size)
        assertFalse(result.history.first().current)
        assertTrue(result.history.last().current)
    }

    @Test
    fun priceComparisonRejectsMalformedExactPriceAndUnsupportedProjectionVersion() {
        val malformedPrice = comparisonJson(currentUnitPrice = "8.5")
        assertThrows(IllegalArgumentException::class.java) {
            FinanceModels.productPriceComparison(JSONObject(malformedPrice),
                "00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000012")
        }

        val unsupportedVersion = comparisonJson().replace(
            "price-projection.v1", "price-projection.v2",
        )
        assertThrows(IllegalArgumentException::class.java) {
            FinanceModels.productPriceComparison(JSONObject(unsupportedVersion),
                "00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000012")
        }
    }

    private fun comparisonJson(
        hasBaseline: Boolean = true,
        baselineUnitPrice: String? = "10.000000",
        change: String? = "2.000000",
        relative: String? = "0.200000",
        signal: Boolean = true,
        direction: String? = "up",
        priorPurchases: Int = 1,
        history: String = """
            {"receiptId":"00000000-0000-0000-0000-000000000001","itemId":"00000000-0000-0000-0000-000000000011","purchasedAt":"2026-08-01T10:00:00Z","merchant":"Market","name":"Milk","unitPrice":"10.000000","current":false},
            {"receiptId":"00000000-0000-0000-0000-000000000002","itemId":"00000000-0000-0000-0000-000000000012","purchasedAt":"2026-09-01T10:00:00Z","merchant":"Market","name":"Milk","unitPrice":"12.000000","current":true}
        """.trimIndent(),
        productName: String = "Milk",
        currentUnitPrice: String = "12.000000",
    ): String = """
        {
          "algorithmVersion":"price-projection.v1",
          "productName":"$productName",
          "hasBaseline":$hasBaseline,
          "currentUnitPrice":"$currentUnitPrice",
          "baselineUnitPrice":${baselineUnitPrice?.let { "\"$it\"" } ?: "null"},
          "change":${change?.let { "\"$it\"" } ?: "null"},
          "relative":${relative?.let { "\"$it\"" } ?: "null"},
          "signal":$signal,
          "direction":${direction?.let { "\"$it\"" } ?: "null"},
          "priorPurchases":$priorPurchases,
          "history":[$history]
        }
    """.trimIndent()
}
