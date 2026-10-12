package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FinanceHealthApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun statusUsesAuthenticatedTenantApiAndParsesOnlySanitizedCapabilities() {
        server.enqueue(MockResponse().setBody("""
            {"capabilities":{
              "localAi":{"status":"available","diagnosticCode":null},
              "receiptVision":{"status":"unavailable","diagnosticCode":"MODEL_MISSING"},
              "receiptOcr":{"status":"disabled","diagnosticCode":"VISION_DISABLED"}
            }}
        """.trimIndent()))

        val health = api().healthStatus(TENANT_ID)

        assertEquals("available", health.capabilities.getValue("localAi").status)
        assertEquals("MODEL_MISSING", health.capabilities.getValue("receiptVision").diagnosticCode)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/health", request.path)
        assertEquals("Bearer health-token", request.getHeader("Authorization"))
    }

    @Test fun malformedHealthResponseFailsClosedWithoutExposingRawProviderData() {
        server.enqueue(MockResponse().setBody("""
            {"providerUrl":"https://secret.invalid","exception":"private stack",
             "capabilities":{"localAi":{"status":"available","diagnosticCode":null},
             "receiptVision":{"status":"available","diagnosticCode":null},
             "receiptOcr":{"status":"available","diagnosticCode":null}}}
        """.trimIndent()))

        val failure = runCatching { api().healthStatus(TENANT_ID) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("health", ignoreCase = true))
        assertNull(failure?.message?.takeIf { it.contains("secret.invalid") || it.contains("private stack") })
    }

    @Test fun invalidTenantIsRejectedBeforeNetworkRequest() {
        assertTrue(runCatching { api().healthStatus("not-a-tenant") }.isFailure)
        assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient()).also {
            it.saveTokens("health-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000001"
    }
}
