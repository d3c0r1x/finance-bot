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
class FinanceTenantUserIdApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun tenantsPreservesCoreUserIdAlongsideTenantMembershipFields() {
        server.enqueue(MockResponse().setBody("""
            [{"tenantId":"$TENANT_ID","userId":"$USER_ID","displayName":"Personal finance",
              "role":"owner","timezone":"Europe/Moscow"}]
        """.trimIndent()))
        val base = server.url("/").toString().removeSuffix("/")
        val api = FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient())
        api.saveTokens("synthetic-access-token", "synthetic-refresh-token", System.currentTimeMillis() + 60_000)

        val tenant = api.tenants().single()

        assertEquals(TENANT_ID, tenant.id)
        assertEquals(USER_ID, tenant.userId)
        assertEquals("Personal finance", tenant.name)
        assertEquals("owner", tenant.role)
        assertEquals("Europe/Moscow", tenant.timezone)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/v1/me/tenants", request.path)
        assertEquals("Bearer synthetic-access-token", request.getHeader("Authorization"))
    }

    @Test fun olderTenantResponseWithoutUserIdRemainsReadableButCannotProvideRecoveryIdentity() {
        server.enqueue(MockResponse().setBody("""
            [{"tenantId":"$TENANT_ID","displayName":"Personal finance","role":"owner",
              "timezone":"Europe/Moscow"}]
        """.trimIndent()))
        val base = server.url("/").toString().removeSuffix("/")
        val api = FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient())
        api.saveTokens("synthetic-access-token", "synthetic-refresh-token", System.currentTimeMillis() + 60_000)

        val tenant = api.tenants().single()

        assertEquals(TENANT_ID, tenant.id)
        assertEquals("", tenant.userId)
    }

    private companion object {
        const val TENANT_ID = "7ed3e606-0000-4000-8000-000000000001"
        const val USER_ID = "7ed3e606-0000-4000-8000-000000000002"
    }
}
