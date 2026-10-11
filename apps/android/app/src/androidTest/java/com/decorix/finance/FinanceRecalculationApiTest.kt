package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FinanceRecalculationApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test
    fun previewUsesAuthenticatedTenantRouteAndPreservesExactImpactAndChanges() {
        server.enqueue(MockResponse().setBody(previewJson()))
        val api = authenticatedApi()

        val result = api.previewReceiptRecalculation(TENANT_ID)

        assertEquals(RUN_ID, result.runId)
        assertEquals("receipt-basket.v1", result.algorithmVersion)
        assertEquals("previewed", result.state)
        assertEquals(3, result.checked)
        assertEquals(2, result.updateCount)
        assertEquals(1, result.changedCount)
        val impact = requireNotNull(result.impact)
        assertEquals("receipt-recalculation-impact.v1", impact.algorithmVersion)
        assertEquals("0.10", impact.optionalSpendBefore)
        assertEquals("100000000000000000000.99", impact.optionalSpendAfter)
        assertEquals("100000000000000000000.89", impact.optionalSpendDelta)
        assertEquals("RUB", impact.currency)
        assertEquals("10.01", result.changes.single().lineSum)
        assertEquals("neutral", result.changes.single().beforeVerdict)
        assertEquals("useful", result.changes.single().afterVerdict)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/review-recalculations/preview", request.path)
        assertEquals("Bearer recalculation-token", request.getHeader("Authorization"))
    }

    @Test
    fun previewUnauthorizedIsReturnedWithoutSilentlyRepeatingNonIdempotentRequest() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
        // A valid refresh response makes an accidental automatic replay observable.
        server.enqueue(MockResponse().setBody(
            """{"access_token":"refreshed-token","refresh_token":"next-refresh","expires_in":300}""",
        ))
        server.enqueue(MockResponse().setBody(previewJson()))
        val api = authenticatedApi()

        val failure = runCatching { api.previewReceiptRecalculation(TENANT_ID) }.exceptionOrNull()

        assertNotNull("preview must surface the original unauthorized response", failure)
        assertTrue("401 should remain an API status, got $failure", failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
        val original = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", original.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/review-recalculations/preview", original.path)
        assertEquals("Bearer recalculation-token", original.getHeader("Authorization"))
        assertNull("preview must not trigger refresh or replay after 401",
            server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test
    fun applySendsOnlySelectedRunIdAndParsesAppliedResultWithNullableImpact() {
        server.enqueue(MockResponse().setBody(applyJson()))
        val api = authenticatedApi()

        val result = api.applyReceiptRecalculation(TENANT_ID, RUN_ID)

        assertEquals(RUN_ID, result.runId)
        assertEquals("applied", result.state)
        assertEquals(2, result.appliedCount)
        assertEquals(1, result.changedCount)
        assertNull("Core may report impact unavailable", result.impact)
        assertEquals("10.01", result.changes.single().lineSum)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/review-recalculations/apply", request.path)
        assertEquals("Bearer recalculation-token", request.getHeader("Authorization"))
        assertEquals("""{"runId":"$RUN_ID"}""", request.body.readUtf8())
    }

    @Test
    fun historyAndDetailEncodeLimitsAndOpaqueCursors() {
        server.enqueue(MockResponse().setBody(historyJson()))
        server.enqueue(MockResponse().setBody(detailJson()))
        val api = authenticatedApi()
        val historyCursor = "after/run +&="
        val detailCursor = "00000000-0000-4000-8000-000000000011"

        val history = api.receiptRecalculationHistory(TENANT_ID, 17, historyCursor)
        assertEquals(RUN_ID, history.runs.single().runId)
        assertEquals("applied", history.runs.single().state)
        assertNull(history.runs.single().impact)
        assertNull(history.runs.single().appliedAt)
        assertEquals("next/history", history.nextCursor)
        val historyRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", historyRequest.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/review-recalculations?limit=17&cursor=after%2Frun%20%2B%26%3D",
            historyRequest.path)
        assertEquals("Bearer recalculation-token", historyRequest.getHeader("Authorization"))

        val detail = api.receiptRecalculationDetail(TENANT_ID, RUN_ID, 23, detailCursor)
        assertEquals(RUN_ID, detail.run.runId)
        assertEquals(1, detail.changes.size)
        assertEquals("10.01", detail.changes.single().lineSum)
        assertEquals("next/detail", detail.nextCursor)
        val detailRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", detailRequest.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/review-recalculations/$RUN_ID?limit=23&cursor=$detailCursor",
            detailRequest.path)
        assertEquals("Bearer recalculation-token", detailRequest.getHeader("Authorization"))
    }

    @Test
    fun stalePreviewAndViewerHistoryPreserveCore412And403Statuses() {
        server.enqueue(MockResponse().setResponseCode(412).setBody("""{"detail":"stale_preview"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"forbidden"}"""))
        val api = authenticatedApi()

        val stale = runCatching { api.applyReceiptRecalculation(TENANT_ID, RUN_ID) }.exceptionOrNull()
        assertTrue("stale preview should remain an ApiFailure", stale is ApiFailure)
        assertEquals(412, (stale as ApiFailure).status)
        val applyRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/v1/tenants/$TENANT_ID/review-recalculations/apply", applyRequest.path)

        val denied = runCatching { api.receiptRecalculationHistory(TENANT_ID, 20, null) }.exceptionOrNull()
        assertTrue("viewer denial should remain an ApiFailure", denied is ApiFailure)
        assertEquals(403, (denied as ApiFailure).status)
        val historyRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/v1/tenants/$TENANT_ID/review-recalculations?limit=20", historyRequest.path)
    }

    @Test
    fun malformedRequiredPreviewFieldsFailClosed() {
        server.enqueue(MockResponse().setBody("""
            {"algorithmVersion":"receipt-basket.v1","state":"previewed","checked":1,
             "updateCount":1,"changedCount":1,"impact":$IMPACT_JSON,"changes":[]}
        """.trimIndent()))
        val api = authenticatedApi()

        val failure = runCatching { api.previewReceiptRecalculation(TENANT_ID) }.exceptionOrNull()

        assertNotNull("missing required runId must reject the server payload", failure)
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
    }

    @Test
    fun finalHistoryAndDetailPagesAcceptCoreOmittedCursors() {
        server.enqueue(MockResponse().setBody("""{"runs":[],"nextCursor":null}"""))
        server.enqueue(MockResponse().setBody("""{"runs":[]}"""))
        server.enqueue(MockResponse().setBody("""{"run":${runJson()},"changes":[]}"""))
        val api = authenticatedApi()

        assertNull(api.receiptRecalculationHistory(TENANT_ID).nextCursor)
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertNull(api.receiptRecalculationHistory(TENANT_ID).nextCursor)
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertNull(api.receiptRecalculationDetail(TENANT_ID, RUN_ID).nextCursor)
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
    }

    @Test
    fun fractionalReceiptItemVersionFailsClosed() {
        server.enqueue(MockResponse().setBody("""
            {"runId":"$RUN_ID","algorithmVersion":"receipt-basket.v1","state":"previewed",
             "checked":1,"updateCount":1,"changedCount":1,"impact":null,
             "changes":[{"itemId":"00000000-0000-4000-8000-000000000011","name":"Tea",
              "lineSum":"10.01","itemVersion":4.5,"beforeVerdict":null,"beforeReason":null,
              "beforeAction":null,"beforeSource":null,"afterVerdict":"useful","afterReason":"new",
              "afterAction":null,"afterSource":"rule","changed":true}]}
        """.trimIndent()))
        val api = authenticatedApi()

        val failure = runCatching { api.previewReceiptRecalculation(TENANT_ID) }.exceptionOrNull()

        assertNotNull("fractional itemVersion must not be truncated", failure)
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
    }

    @Test
    fun inconsistentUnavailableImpactFailsClosed() {
        server.enqueue(MockResponse().setBody("""
            {"runId":"$RUN_ID","algorithmVersion":"receipt-basket.v1","state":"previewed",
             "checked":1,"updateCount":0,"changedCount":0,
             "impact":{"algorithmVersion":"receipt-recalculation-impact.v1",
              "inputVersion":"${"a".repeat(64)}","reasonCode":"missing_amounts",
              "completeness":"complete","optionalSpendBefore":null,"optionalSpendAfter":null,
              "optionalSpendDelta":null,"currency":"RUB"},"changes":[]}
        """.trimIndent()))
        val api = authenticatedApi()

        val failure = runCatching { api.previewReceiptRecalculation(TENANT_ID) }.exceptionOrNull()

        assertNotNull("Core's partial reason cannot claim complete analytics", failure)
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
    }

    private fun authenticatedApi(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient()).also {
            it.saveTokens("recalculation-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private fun previewJson() = """
        {"runId":"$RUN_ID","algorithmVersion":"receipt-basket.v1","state":"previewed",
         "checked":3,"updateCount":2,"changedCount":1,
         "impact":$IMPACT_JSON,
         "changes":[$CHANGE_JSON]}
    """.trimIndent()

    private fun applyJson() = """
        {"runId":"$RUN_ID","algorithmVersion":"receipt-basket.v1","state":"applied",
         "appliedCount":2,"changedCount":1,"impact":null,"changes":[$CHANGE_JSON]}
    """.trimIndent()

    private fun historyJson() = """
        {"runs":[{"runId":"$RUN_ID","algorithmVersion":"receipt-basket.v1","state":"applied",
          "checked":3,"updateCount":2,"changedCount":1,"createdAt":"2026-10-10T12:00:00Z",
          "appliedAt":null,"impact":null}],"nextCursor":"next/history"}
    """.trimIndent()

    private fun runJson() = """
        {"runId":"$RUN_ID","algorithmVersion":"receipt-basket.v1","state":"applied",
         "checked":1,"updateCount":1,"changedCount":1,"createdAt":"2026-10-10T12:00:00Z",
         "appliedAt":null,"impact":null}
    """.trimIndent()

    private fun detailJson() = """
        {"run":{"runId":"$RUN_ID","algorithmVersion":"receipt-basket.v1","state":"previewed",
          "checked":3,"updateCount":2,"changedCount":1,"createdAt":"2026-10-10T12:00:00Z",
          "appliedAt":null,"impact":$IMPACT_JSON},"changes":[$CHANGE_JSON],"nextCursor":"next/detail"}
    """.trimIndent()

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000001"
        const val RUN_ID = "00000000-0000-4000-8000-000000000042"
        const val IMPACT_JSON = """
            {"algorithmVersion":"receipt-recalculation-impact.v1",
             "inputVersion":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
             "reasonCode":"available","completeness":"complete",
             "optionalSpendBefore":"0.10","optionalSpendAfter":"100000000000000000000.99",
             "optionalSpendDelta":"100000000000000000000.89","currency":"RUB"}
        """
        const val CHANGE_JSON = """
            {"itemId":"00000000-0000-4000-8000-000000000011","name":"Tea",
             "lineSum":"10.01","itemVersion":4,"beforeVerdict":"neutral",
             "beforeReason":"old","beforeAction":null,"beforeSource":"model",
             "afterVerdict":"useful","afterReason":"new","afterAction":null,
             "afterSource":"rule","changed":true}
        """
    }
}
