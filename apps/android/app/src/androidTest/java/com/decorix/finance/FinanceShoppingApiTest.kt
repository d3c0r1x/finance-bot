package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FinanceShoppingApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test
    fun authenticatedShoppingRequestPreservesCoreRhythmAndExactEstimate() {
        server.enqueue(MockResponse().setBody("""
            {"candidates":[{"productName":"Молоко 1 л","productKey":"milk","purchaseCount":7,
              "medianIntervalDays":10,"usualUnitPrice":"125.500000","estimatedCost":"125.50",
              "lastPurchasedAt":"2026-10-01T00:00:00Z","dueAt":"2026-10-09T00:00:00Z",
              "daysUntilDue":-2}],
             "estimatedListCost":"125.50","inventoryTracked":false,
             "boughtCandidates":[],"mutedCandidates":[],"blockedCandidates":[]}
        """.trimIndent()))
        val base = server.url("/").toString().removeSuffix("/")
        val api = FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient()).also {
            it.saveTokens("shopping-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }

        val result = api.shoppingCandidates("tenant-35")

        val candidate = result.candidates.single()
        assertEquals(7, candidate.purchaseCount)
        assertEquals(10, candidate.medianIntervalDays)
        assertEquals(-2, candidate.daysUntilDue)
        assertEquals("125.50", candidate.estimatedCost)
        assertEquals("125.50", result.estimatedListCost)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/v1/tenants/tenant-35/shopping", request.path)
        assertEquals("Bearer shopping-token", request.getHeader("Authorization"))
    }
}
