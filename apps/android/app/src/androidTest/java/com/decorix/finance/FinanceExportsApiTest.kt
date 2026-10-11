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
class FinanceExportsApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun createExportPostsExactCsvRequestWithAuthenticationAndParsesQueuedJob() {
        server.enqueue(MockResponse().setResponseCode(202).setBody(jobJson("queued")))
        val requestBody = FinanceExportModels.request("2026-10-01", "2026-10-31", "all")

        val job = api().createExport(TENANT_ID, requestBody)

        assertEquals(EXPORT_ID, job.id)
        assertEquals("queued", job.status)
        assertNull(job.downloadUrl)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/exports", request.path)
        assertEquals("Bearer exports-token", request.getHeader("Authorization"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals(
            """{"formatVersion":"csv-v1","fromDate":"2026-10-01","toDate":"2026-10-31","memberId":"all"}""",
            request.body.readUtf8(),
        )
    }

    @Test fun statusUsesTenantAndExportScopedAuthenticatedGetPath() {
        server.enqueue(MockResponse().setBody(jobJson("processing")))

        val job = api().exportStatus(TENANT_ID, EXPORT_ID)

        assertEquals("processing", job.status)
        assertNull(job.downloadUrl)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/exports/$EXPORT_ID", request.path)
        assertEquals("Bearer exports-token", request.getHeader("Authorization"))
        assertNull(request.requestUrl?.query)
    }

    @Test fun createAndStatusSurface401WithoutRefreshingOrReplayingRequest() {
        val api = api()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
        server.enqueue(MockResponse().setBody(
            """{"access_token":"refreshed-token","refresh_token":"next-refresh","expires_in":300}""",
        ))
        val createFailure = runCatching {
            api.createExport(TENANT_ID, FinanceExportModels.request("2026-10-01", "2026-10-01"))
        }.exceptionOrNull()

        assertTrue("export creation must surface the original 401, got $createFailure", createFailure is ApiFailure)
        assertEquals(401, (createFailure as ApiFailure).status)
        val createRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", createRequest.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/exports", createRequest.path)
        assertNull("durable export creation must not replay after a 401",
            server.takeRequest(250, TimeUnit.MILLISECONDS))

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
        server.enqueue(MockResponse().setBody(
            """{"access_token":"refreshed-token","refresh_token":"next-refresh","expires_in":300}""",
        ))
        val statusFailure = runCatching { api.exportStatus(TENANT_ID, EXPORT_ID) }.exceptionOrNull()

        assertTrue("status request must surface the original 401, got $statusFailure", statusFailure is ApiFailure)
        assertEquals(401, (statusFailure as ApiFailure).status)
        val statusRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", statusRequest.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/exports/$EXPORT_ID", statusRequest.path)
        assertNull("status request must not be replayed after a 401",
            server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun invalidIdentifiersFailBeforeAnyNetworkRequest() {
        val api = api()

        assertTrue(runCatching { api.createExport("bad-tenant", FinanceExportModels.request(
            "2026-10-01", "2026-10-01")) }.isFailure)
        assertTrue(runCatching { api.exportStatus(TENANT_ID, "bad-export") }.isFailure)
        assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient()).also {
            it.saveTokens("exports-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private fun jobJson(status: String) = """
        {"id":"$EXPORT_ID","status":"$status","formatVersion":"csv-v1",
         "fromDate":"2026-10-01","toDate":"2026-10-31","memberId":null,"allMembers":true,
         "rowCount":0,"snapshotAt":"2026-10-11T08:00:00Z","createdAt":"2026-10-11T08:00:01Z",
         "expiresAt":"2026-10-11T08:10:00Z","downloadUrl":null}
    """.trimIndent()

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000001"
        const val EXPORT_ID = "00000000-0000-4000-8000-000000000052"
    }
}
