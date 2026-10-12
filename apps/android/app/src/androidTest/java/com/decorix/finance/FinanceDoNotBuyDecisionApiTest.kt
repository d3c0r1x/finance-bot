package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FinanceDoNotBuyDecisionApiTest {
    private val server = MockWebServer()
    private val tokenVault by lazy {
        TokenVault.isolatedForTesting(
            ApplicationProvider.getApplicationContext(),
            "finance_do_not_buy_decision_api_test_session",
            "finance-do-not-buy-decision-api-test-v1",
        )
    }

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() {
            tokenVault.clear()
            server.start()
        }
        override fun after() {
            server.shutdown()
            tokenVault.clear()
        }
    }

    @Test fun confirmUsesTenantScopedAuthenticatedDecisionAndReadsBackConfirmedKey() {
        server.enqueue(MockResponse().setBody(
            """{"productKey":"tea500g","decision":"confirmed","version":1,"updatedAt":"2026-10-06T10:00:00Z"}""",
        ))
        server.enqueue(MockResponse().setBody(
            """{"productKeys":[],"confirmedProductKeys":["tea500g"]}""",
        ))
        val api = api()

        api.decideDoNotBuy(TENANT_ID, PRODUCT_KEY, "confirm")
        val decisions = api.productDecisions(TENANT_ID)

        assertEquals(listOf(PRODUCT_KEY), decisions.confirmedProductKeys)
        val write = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PUT", write.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/products/$PRODUCT_KEY/decision", write.path)
        assertEquals("Bearer f41-test-token", write.getHeader("Authorization"))
        assertEquals("application/json", write.getHeader("Content-Type")?.substringBefore(';'))
        assertEquals("confirmed", JSONObject(write.body.readUtf8()).getString("decision"))

        val readBack = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", readBack.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/products/decisions", readBack.path)
        assertEquals("Bearer f41-test-token", readBack.getHeader("Authorization"))
        assertEquals(2, server.requestCount)
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient(), tokenVault).also {
            it.saveTokens("f41-test-token", "f41-refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private companion object {
        const val TENANT_ID = "tenant-f41-test"
        const val PRODUCT_KEY = "tea500g"
    }
}
