package com.decorix.finance

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceProductCatalogModelsTest {
    @Test
    fun parserPreservesEveryExactCatalogCardAndChronologicalHistoryField() {
        val catalog = FinanceModels.productCatalog(JSONObject(responseJson(
            mode = "catalog", query = "", products = listOf(cardJson(
                history = listOf(pointJson(1, "2026-08-01T10:00:00Z", "100.000000"),
                    pointJson(2, "2026-09-01T10:00:00Z", "110.000000"),
                    pointJson(3, "2026-10-01T10:00:00Z", "125.000000", merchant = "North Shop")),
            )),
        )), requestedQuery = "")

        assertEquals("catalog", catalog.mode)
        assertEquals("", catalog.query)
        val card = catalog.products.single()
        assertEquals("Coffee 500g", card.productName)
        assertEquals(3, card.purchaseCount)
        assertEquals("110.000000", card.usualUnitPrice)
        assertTrue(card.hasBaseline)
        assertEquals("105.000000", card.baselineUnitPrice)
        assertEquals("125.000000", card.lastUnitPrice)
        assertEquals("2026-10-01T10:00:00Z", card.lastPurchasedAt)
        assertEquals("North Shop", card.lastMerchant)
        assertEquals("100.000000", card.cheapestUnitPrice)
        assertEquals("Market 1", card.cheapestMerchant)
        assertEquals("335.00", card.totalSpent)
        assertEquals("20.000000", card.change)
        assertEquals("0.190476", card.relative)
        assertTrue(card.signal)
        assertEquals("up", card.direction)
        assertEquals(2, card.priorPurchases)
        assertTrue(card.chartAvailable)
        assertEquals(3, card.history.size)
        val first = card.history.first()
        assertEquals("00000000-0000-4000-8000-000000000001", first.receiptId)
        assertEquals("00000000-0000-4000-8000-000000000011", first.itemId)
        assertEquals("2026-08-01T10:00:00Z", first.purchasedAt)
        assertEquals("Market 1", first.merchant)
        assertEquals("Coffee 500g", first.name)
        assertEquals("100.000000", first.unitPrice)
        assertFalse(first.current)
    }

    @Test
    fun oneActualPurchaseHasNoBaselineAndCannotClaimHistoryChart() {
        val catalog = FinanceModels.productCatalog(JSONObject(responseJson(
            mode = "search", query = "tea", products = listOf(cardJson(
                name = "Tea", purchaseCount = 1, usualUnitPrice = "8.500000", hasBaseline = false,
                baselineUnitPrice = null, lastUnitPrice = "8.500000", lastMerchant = null,
                lastPurchasedAt = "2026-10-03T10:00:00Z",
                cheapestUnitPrice = "8.500000", cheapestMerchant = null, totalSpent = "17.00",
                change = null, relative = null, signal = false, direction = null, priorPurchases = 0,
                chartAvailable = false,
                history = listOf(pointJson(5, "2026-10-03T10:00:00Z", "8.500000", name = "Tea", merchant = null)),
            )),
        )), requestedQuery = "tea")

        val card = catalog.products.single()
        assertEquals(1, card.purchaseCount)
        assertEquals("8.500000", card.usualUnitPrice)
        assertFalse(card.hasBaseline)
        assertNull(card.baselineUnitPrice)
        assertNull(card.change)
        assertNull(card.relative)
        assertFalse(card.signal)
        assertNull(card.direction)
        assertEquals(0, card.priorPurchases)
        assertFalse("a real single point must not be presented as a chart", card.chartAvailable)
        assertEquals(1, card.history.size)
        assertNull(card.history.single().merchant)
    }

    @Test
    fun twoOrMoreRealOrderedPointsCanExposeChartAndServerBaseline() {
        val catalog = FinanceModels.productCatalog(JSONObject(responseJson(
            mode = "search", query = "milk", products = listOf(cardJson(
                purchaseCount = 2, usualUnitPrice = "115.000000", baselineUnitPrice = "100.000000",
                lastUnitPrice = "130.000000", lastMerchant = "Market 7", cheapestMerchant = "Market 6",
                change = "30.000000", relative = "0.300000",
                signal = true, direction = "up", priorPurchases = 1, chartAvailable = true,
                history = listOf(pointJson(6, "2026-09-01T10:00:00Z", "100.000000"),
                    pointJson(7, "2026-10-01T10:00:00Z", "130.000000")),
            )),
        )), requestedQuery = "milk")

        val card = catalog.products.single()
        assertTrue(card.hasBaseline)
        assertEquals("100.000000", card.baselineUnitPrice)
        assertTrue(card.chartAvailable)
        assertEquals(2, card.history.size)
        assertEquals("100.000000", card.history.first().unitPrice)
        assertEquals("130.000000", card.history.last().unitPrice)
    }

    @Test
    fun parserRejectsQueryModeThresholdChartAndMalformedMoneyContradictions() {
        val onePurchaseCatalog = responseJson(mode = "catalog", query = "", products = listOf(
            cardJson(purchaseCount = 1, hasBaseline = false, baselineUnitPrice = null,
                change = null, relative = null, signal = false, direction = null, priorPurchases = 0,
                chartAvailable = false, history = listOf(pointJson(8, "2026-10-01T10:00:00Z", "10.000000"))),
        ))
        assertThrows(IllegalArgumentException::class.java) {
            FinanceModels.productCatalog(JSONObject(onePurchaseCatalog), requestedQuery = "")
        }

        val chartWithoutTwoPoints = responseJson(mode = "search", query = "milk", products = listOf(
            cardJson(purchaseCount = 1, hasBaseline = false, baselineUnitPrice = null,
                change = null, relative = null, signal = false, direction = null, priorPurchases = 0,
                chartAvailable = true, history = listOf(pointJson(9, "2026-10-01T10:00:00Z", "10.000000"))),
        ))
        assertThrows(IllegalArgumentException::class.java) {
            FinanceModels.productCatalog(JSONObject(chartWithoutTwoPoints), requestedQuery = "milk")
        }

        val wrongQuery = responseJson(mode = "search", query = "milk", products = emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            FinanceModels.productCatalog(JSONObject(wrongQuery), requestedQuery = "tea")
        }

        val malformedPrice = responseJson(mode = "search", query = "milk", products = listOf(
            cardJson(usualUnitPrice = "10.5", history = listOf(pointJson(10, "2026-10-01T10:00:00Z", "10.000000"))),
        ))
        assertThrows(IllegalArgumentException::class.java) {
            FinanceModels.productCatalog(JSONObject(malformedPrice), requestedQuery = "milk")
        }
    }

    @Test
    fun parserRejectsMoreThanFiveSearchResultsAndUnsupportedResponseMode() {
        val overLimit = responseJson(mode = "search", query = "coffee", products = (1..6).map { index ->
            cardJson(name = "Coffee $index", purchaseCount = 1,
                hasBaseline = false, baselineUnitPrice = null, change = null, relative = null,
                signal = false, direction = null, priorPurchases = 0, chartAvailable = false,
                history = listOf(pointJson(index + 20, "2026-10-01T10:00:00Z", "10.000000")))
        })
        assertThrows(IllegalArgumentException::class.java) {
            FinanceModels.productCatalog(JSONObject(overLimit), requestedQuery = "coffee")
        }

        val badMode = responseJson(mode = "other", query = "coffee", products = emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            FinanceModels.productCatalog(JSONObject(badMode), requestedQuery = "coffee")
        }
    }

    private fun responseJson(mode: String, query: String, products: List<String>) = """
        {"mode":"$mode","query":"$query","products":[${products.joinToString(",")}]}
    """.trimIndent()

    private fun cardJson(
        name: String = "Coffee 500g",
        purchaseCount: Int = 3,
        usualUnitPrice: String = "110.000000",
        hasBaseline: Boolean = true,
        baselineUnitPrice: String? = "105.000000",
        lastUnitPrice: String = "125.000000",
        lastPurchasedAt: String = "2026-10-01T10:00:00Z",
        lastMerchant: String? = "North Shop",
        cheapestUnitPrice: String = "100.000000",
        cheapestMerchant: String? = "Market 1",
        totalSpent: String = "335.00",
        change: String? = "20.000000",
        relative: String? = "0.190476",
        signal: Boolean = true,
        direction: String? = "up",
        priorPurchases: Int = 2,
        chartAvailable: Boolean = true,
        history: List<String>,
    ): String = """
        {
          "productName":"$name","purchaseCount":$purchaseCount,"usualUnitPrice":"$usualUnitPrice",
          "hasBaseline":$hasBaseline,"baselineUnitPrice":${jsonString(baselineUnitPrice)},
          "lastUnitPrice":"$lastUnitPrice","lastPurchasedAt":"$lastPurchasedAt",
          "lastMerchant":${jsonString(lastMerchant)},"cheapestUnitPrice":"$cheapestUnitPrice",
          "cheapestMerchant":${jsonString(cheapestMerchant)},"totalSpent":"$totalSpent",
          "change":${jsonString(change)},"relative":${jsonString(relative)},"signal":$signal,
          "direction":${jsonString(direction)},"priorPurchases":$priorPurchases,
          "chartAvailable":$chartAvailable,"history":[${history.joinToString(",")}]
        }
    """.trimIndent()

    private fun pointJson(
        index: Int,
        purchasedAt: String,
        unitPrice: String,
        name: String = "Coffee 500g",
        merchant: String? = "Market $index",
    ): String {
        val suffix = index.toString().padStart(12, '0')
        val itemSuffix = (index + 10).toString().padStart(12, '0')
        return """
            {"receiptId":"00000000-0000-4000-8000-$suffix",
             "itemId":"00000000-0000-4000-8000-$itemSuffix","purchasedAt":"$purchasedAt",
             "merchant":${jsonString(merchant)},"name":"$name","unitPrice":"$unitPrice","current":false}
        """.trimIndent()
    }

    private fun jsonString(value: String?): String = value?.let { "\"$it\"" } ?: "null"
}
