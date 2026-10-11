package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class FinanceProductCatalogApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test
    fun authenticatedTenantCatalogAndSearchPreserveCoreCardsAndEncodedQuery() {
        server.enqueue(MockResponse().setBody(catalogJson(
            mode = "catalog", query = "", products = listOf(productJson(
                name = "Coffee 500g", purchaseCount = 3,
                history = listOf(
                pointJson(1, "2026-08-01T10:00:00Z", "North Shop", "100.000000"),
                pointJson(2, "2026-09-01T10:00:00Z", "Corner Shop", "110.000000"),
                pointJson(3, "2026-10-01T10:00:00Z", "North Shop", "125.000000"),
            ), direction = "up",
            )),
        )))
        server.enqueue(MockResponse().setBody(catalogJson(
            mode = "search", query = "coffee & tea/500g", products = listOf(productJson(
                name = "Coffee & tea 500g", purchaseCount = 1, usualUnitPrice = "8.500000",
                lastUnitPrice = "8.500000", cheapestUnitPrice = "8.500000",
                lastPurchasedAt = "2026-10-02T10:00:00Z", lastMerchant = null,
                hasBaseline = false, baselineUnitPrice = null, change = null, relative = null,
                signal = false, direction = null, priorPurchases = 0, chartAvailable = false,
                history = listOf(pointJson(4, "2026-10-02T10:00:00Z", null, "8.500000",
                    name = "Coffee & tea 500g")),
            )),
        )))
        val api = api().also {
            it.saveTokens("product-catalog-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }

        val catalog = api.productCatalog("tenant-17", "")
        assertEquals("catalog", catalog.mode)
        assertEquals("", catalog.query)
        assertEquals(1, catalog.products.size)
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
        assertEquals("North Shop", card.cheapestMerchant)
        assertEquals("335.00", card.totalSpent)
        assertEquals("20.000000", card.change)
        assertEquals("0.190476", card.relative)
        assertTrue(card.signal)
        assertEquals("up", card.direction)
        assertEquals(2, card.priorPurchases)
        assertTrue("two or more real points make the chart available", card.chartAvailable)
        assertEquals(3, card.history.size)
        assertEquals("00000000-0000-4000-8000-000000000001", card.history.first().receiptId)
        assertEquals("00000000-0000-4000-8000-000000000011", card.history.first().itemId)
        assertEquals("2026-08-01T10:00:00Z", card.history.first().purchasedAt)
        assertEquals("North Shop", card.history.first().merchant)
        assertEquals("Coffee 500g", card.history.first().name)
        assertEquals("100.000000", card.history.first().unitPrice)
        assertFalse(card.history.first().current)
        assertFalse("catalog cards require the server's three-confirmed-purchase threshold",
            catalog.products.any { it.purchaseCount < 3 })

        val catalogRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", catalogRequest.method)
        assertEquals("/api/v1/tenants/tenant-17/products?query=", catalogRequest.path)
        assertEquals("Bearer product-catalog-token", catalogRequest.getHeader("Authorization"))

        val search = api.productCatalog("tenant-17", "coffee & tea/500g")
        assertEquals("search", search.mode)
        assertEquals("coffee & tea/500g", search.query)
        assertEquals(1, search.products.size)
        val onePurchase = search.products.single()
        assertEquals(1, onePurchase.purchaseCount)
        assertEquals("8.500000", onePurchase.usualUnitPrice)
        assertEquals("8.500000", onePurchase.lastUnitPrice)
        assertEquals("8.500000", onePurchase.cheapestUnitPrice)
        assertNull(onePurchase.baselineUnitPrice)
        assertNull(onePurchase.change)
        assertNull(onePurchase.relative)
        assertNull(onePurchase.direction)
        assertFalse(onePurchase.hasBaseline)
        assertFalse(onePurchase.signal)
        assertFalse("one actual point must not claim a chart", onePurchase.chartAvailable)
        assertEquals(1, onePurchase.history.size)
        assertNull(onePurchase.history.single().merchant)
        assertFalse(onePurchase.history.single().current)

        val searchRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", searchRequest.method)
        assertEquals("/api/v1/tenants/tenant-17/products?query=coffee%20%26%20tea%2F500g", searchRequest.path)
        assertEquals("Bearer product-catalog-token", searchRequest.getHeader("Authorization"))
    }

    @Test
    fun coreCatalogFailureKeepsHttpStatusForLocalizedUiHandling() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"member scope denied"}"""))
        val api = api().also {
            it.saveTokens("viewer-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }

        val failure = runCatching { api.productCatalog("tenant-17", "milk") }.exceptionOrNull()

        assertTrue("a viewer's tenant-scoped catalog denial must remain an API failure", failure is ApiFailure)
        assertEquals(403, (failure as ApiFailure).status)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/v1/tenants/tenant-17/products?query=milk", request.path)
        assertEquals("Bearer viewer-token", request.getHeader("Authorization"))
    }

    @Test
    fun productQueryValidatorCountsCodePointsAcceptsEightyAndRejectsEightyOneOrControls() {
        val api = api().also {
            it.saveTokens("query-validator-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
        val eightyCodePointsWithSupplementaryCharacter = "a".repeat(79) + "\uD83D\uDE00"
        assertEquals("UTF-16 stores the supplementary character as a surrogate pair", 81,
            eightyCodePointsWithSupplementaryCharacter.length)
        server.enqueue(MockResponse().setBody(catalogJson(
            mode = "search", query = eightyCodePointsWithSupplementaryCharacter, products = emptyList(),
        )))

        val accepted = api.productCatalog("tenant-17", eightyCodePointsWithSupplementaryCharacter)

        assertEquals(eightyCodePointsWithSupplementaryCharacter, accepted.query)
        val acceptedRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/v1/tenants/tenant-17/products?query=" + "a".repeat(79) + "%F0%9F%98%80",
            acceptedRequest.path)

        val eightyOneCodePoints = "a".repeat(80) + "\uD83D\uDE00"
        assertThrows(IllegalArgumentException::class.java) {
            api.productCatalog("tenant-17", eightyOneCodePoints)
        }
        assertNull("invalid query must be rejected before an HTTP request", server.takeRequest(200, TimeUnit.MILLISECONDS))

        assertThrows(IllegalArgumentException::class.java) {
            api.productCatalog("tenant-17", "milk\u0001tea")
        }
        assertNull("ISO control must be rejected before an HTTP request", server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun productQueryValidatorTrimsOuterWhitespaceBeforeRequestAndResponseBinding() {
        server.enqueue(MockResponse().setBody(catalogJson(mode = "search", query = "tea", products = emptyList())))
        val api = api().also {
            it.saveTokens("query-validator-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }

        val result = api.productCatalog("tenant-17", "  tea  ")

        assertEquals("tea", result.query)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/v1/tenants/tenant-17/products?query=tea", request.path)
    }

    @Test
    fun delayedUnauthorizedCatalogResponseIsNotRetriedUnderReplacementSession() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")
            .setHeadersDelay(1, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"wrong-session-replay"}"""))
        val api = api().also {
            it.saveTokens("old-session-token", "old-refresh-token", System.currentTimeMillis() + 60_000)
        }
        val oldGeneration = api.authSessionGeneration
        val executor = Executors.newSingleThreadExecutor()

        try {
            val operation = executor.submit<Throwable?> {
                runCatching { api.productCatalog("tenant-17", "milk") }.exceptionOrNull()
            }
            val originalRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("Bearer old-session-token", originalRequest.getHeader("Authorization"))

            api.saveTokens("new-session-token", "new-refresh-token", System.currentTimeMillis() + 60_000)
            val newGeneration = api.authSessionGeneration
            assertTrue("saving a replacement session advances its generation", newGeneration > oldGeneration)

            val failure = operation.get(5, TimeUnit.SECONDS)
            assertNotNull("the original unauthorized request must fail", failure)
            assertTrue(failure is ApiFailure)
            val apiFailure = failure as ApiFailure
            assertEquals(401, apiFailure.status)
            assertEquals("failure remains attached to the request's original auth generation",
                oldGeneration, apiFailure.authSessionGeneration)
            assertEquals("the API must retain and report the replacement session generation",
                newGeneration, api.authSessionGeneration)
            assertNull("an old-session request must not replay with replacement credentials",
                server.takeRequest(300, TimeUnit.MILLISECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun replacementSessionTokensCannotBeOverwrittenByInFlightRefresh() {
        server.enqueue(MockResponse().setBody(
            """{"access_token":"refreshed-old-session","refresh_token":"rotated-old-refresh","expires_in":300}"""
        ).setBodyDelay(1, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody(catalogJson(mode = "search", query = "milk", products = emptyList())))
        server.enqueue(MockResponse().setBody(catalogJson(mode = "search", query = "tea", products = emptyList())))
        val api = api().also {
            it.saveTokens("expired-old-session", "old-refresh-token", System.currentTimeMillis() - 1)
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val operation = executor.submit { api.productCatalog("tenant-17", "milk") }
            val refreshRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/protocol/openid-connect/token", refreshRequest.requestUrl?.encodedPath)
            assertEquals("refresh_token=old-refresh-token", refreshRequest.body.readUtf8()
                .split('&').firstOrNull { it.startsWith("refresh_token=") })

            api.saveTokens("new-session-token", "new-refresh-token", System.currentTimeMillis() + 60_000)
            operation.get(5, TimeUnit.SECONDS)

            api.productCatalog("tenant-17", "tea")
            val firstCatalogRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val secondCatalogRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("Bearer refreshed-old-session", firstCatalogRequest.getHeader("Authorization"))
            assertEquals("Bearer new-session-token", secondCatalogRequest.getHeader("Authorization"))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient())
    }

    private fun catalogJson(mode: String, query: String, products: List<String>) = """
        {"mode":"$mode","query":"$query","products":[${products.joinToString(",")}]}
    """.trimIndent()

    private fun productJson(
        name: String,
        purchaseCount: Int,
        usualUnitPrice: String = "110.000000",
        hasBaseline: Boolean = true,
        baselineUnitPrice: String? = "105.000000",
        lastUnitPrice: String = "125.000000",
        lastPurchasedAt: String = "2026-10-01T10:00:00Z",
        lastMerchant: String? = "North Shop",
        cheapestUnitPrice: String = "100.000000",
        cheapestMerchant: String? = "North Shop",
        totalSpent: String = "335.00",
        change: String? = "20.000000",
        relative: String? = "0.190476",
        signal: Boolean = true,
        direction: String? = null,
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
        merchant: String?,
        unitPrice: String,
        name: String = "Coffee 500g",
    ): String {
        val itemSuffix = (index + 10).toString().padStart(12, '0')
        val receiptSuffix = index.toString().padStart(12, '0')
        return """
            {"receiptId":"00000000-0000-4000-8000-$receiptSuffix",
             "itemId":"00000000-0000-4000-8000-$itemSuffix","purchasedAt":"$purchasedAt",
             "merchant":${jsonString(merchant)},"name":"$name","unitPrice":"$unitPrice","current":false}
        """.trimIndent()
    }

    private fun jsonString(value: String?): String = value?.let { "\"$it\"" } ?: "null"
}
