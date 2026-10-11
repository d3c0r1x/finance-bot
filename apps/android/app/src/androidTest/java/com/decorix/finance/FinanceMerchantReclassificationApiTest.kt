package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FinanceMerchantReclassificationApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun previewPostsMerchantAndParsesExactReviewedCandidateSnapshot() {
        server.enqueue(MockResponse().setBody(previewJson()))
        val api = api()

        val preview = api.previewMerchantReclassification(TENANT_ID, "Market")

        assertEquals("Market", preview.merchant)
        assertEquals("market", preview.normalizedMerchant)
        assertEquals("еда", preview.categoryCode)
        val candidate = preview.candidates.single()
        assertEquals(TRANSACTION_ID, candidate.transactionId)
        assertEquals(3L, candidate.version)
        assertEquals("прочее", candidate.currentCategoryCode)
        assertEquals("125.50", candidate.amount)
        assertEquals("2026-10-01T09:30:00Z", candidate.occurredAt)
        assertEquals("Card payment", candidate.description)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/merchant-reclassifications/preview", request.path)
        assertEquals("Bearer bank-import-token", request.getHeader("Authorization"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertNull(request.getHeader("If-Match"))
        assertNull(request.getHeader("Idempotency-Key"))
        assertEquals("{\"merchant\":\"Market\"}", request.body.readUtf8())
    }

    @Test fun applyPostsOnlyExplicitlyReviewedFieldsAndReturnsCoreResult() {
        server.enqueue(MockResponse().setBody(resultJson()))
        val api = api()
        val reviewed = FinanceMerchantReclassificationPreview(
            merchant = "Market",
            normalizedMerchant = "market",
            categoryCode = "еда",
            candidates = listOf(FinanceMerchantReclassificationCandidate(
                transactionId = TRANSACTION_ID,
                version = 3L,
                currentCategoryCode = "прочее",
                amount = "125.50",
                occurredAt = "2026-10-01T09:30:00Z",
                description = "Card payment",
            )),
        )

        val result = api.applyMerchantReclassification(TENANT_ID, reviewed, IDEMPOTENCY_KEY)

        assertEquals("Market", result.merchant)
        assertEquals("еда", result.categoryCode)
        assertEquals(1, result.changedCount)
        assertEquals(listOf(TRANSACTION_ID), result.transactionIds)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/merchant-reclassifications/apply", request.path)
        assertEquals("Bearer bank-import-token", request.getHeader("Authorization"))
        assertEquals(IDEMPOTENCY_KEY, request.getHeader("Idempotency-Key"))
        assertNull(request.getHeader("If-Match"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(setOf("merchant", "categoryCode", "candidates"), body.keys().asSequence().toSet())
        assertEquals("Market", body.getString("merchant"))
        assertEquals("еда", body.getString("categoryCode"))
        val candidates = body.getJSONArray("candidates")
        assertEquals(1, candidates.length())
        val candidate = candidates.getJSONObject(0)
        assertEquals(setOf("transactionId", "version", "currentCategoryCode"),
            candidate.keys().asSequence().toSet())
        assertEquals(TRANSACTION_ID, candidate.getString("transactionId"))
        assertEquals(3L, candidate.getLong("version"))
        assertEquals("прочее", candidate.getString("currentCategoryCode"))
        assertNull("Apply sends no display-only candidate data", candidate.opt("amount"))
        assertNull(candidate.opt("occurredAt"))
        assertNull(candidate.opt("description"))
    }

    @Test fun previewPreservesCoreValidationAndAccessFailuresWithoutRetry() {
        assertPreviewFailures(listOf(400, 403, 404, 409, 412))
    }

    @Test fun applyPreservesCoreValidationAccessStaleAndConflictFailuresWithoutRetry() {
        listOf(400, 403, 409, 412).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":"failure_$status"}"""))
        }
        val api = api()
        val reviewed = FinanceMerchantReclassificationPreview(
            "Market", "market", "еда", listOf(FinanceMerchantReclassificationCandidate(
                TRANSACTION_ID, 3L, "прочее", "125.50", "2026-10-01T09:30:00Z", "Card payment",
            )),
        )

        listOf(400, 403, 409, 412).forEach { status ->
            val failure = runCatching {
                api.applyMerchantReclassification(TENANT_ID, reviewed, IDEMPOTENCY_KEY)
            }.exceptionOrNull()

            assertTrue("Core HTTP $status must remain visible", failure is ApiFailure)
            assertEquals(status, (failure as ApiFailure).status)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/api/v1/tenants/$TENANT_ID/merchant-reclassifications/apply", request.path)
            assertEquals(IDEMPOTENCY_KEY, request.getHeader("Idempotency-Key"))
            assertNull("failed Apply must not be replayed", server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun applyDoesNotRefreshOrReplayA401WithReplacementCredentials() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"expired"}"""))
        val api = api()
        val reviewed = FinanceMerchantReclassificationPreview(
            "Market", "market", "еда", listOf(FinanceMerchantReclassificationCandidate(
                TRANSACTION_ID, 3L, "прочее", "125.50", "2026-10-01T09:30:00Z", "Card payment",
            )),
        )

        val failure = runCatching {
            api.applyMerchantReclassification(TENANT_ID, reviewed, IDEMPOTENCY_KEY)
        }.exceptionOrNull()

        assertTrue(failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("Bearer bank-import-token", request.getHeader("Authorization"))
        assertNull("Apply 401 must not call token refresh or resend the mutation",
            server.takeRequest(150, TimeUnit.MILLISECONDS))
    }

    @Test fun invalidTenantMerchantOrIdempotencyKeyIsRejectedBeforeNetwork() {
        val api = api()
        assertTrue(runCatching { api.previewMerchantReclassification("bad-tenant", "Market") }.isFailure)
        assertTrue(runCatching { api.previewMerchantReclassification(TENANT_ID, "  ") }.isFailure)
        val reviewed = FinanceMerchantReclassificationPreview(
            "Market", "market", "еда", listOf(FinanceMerchantReclassificationCandidate(
                TRANSACTION_ID, 3L, "прочее", "125.50", "2026-10-01T09:30:00Z", "Card payment",
            )),
        )
        assertTrue(runCatching { api.applyMerchantReclassification(TENANT_ID, reviewed, "short") }.isFailure)
        assertNull("Client validation must not issue a request", server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    private fun assertPreviewFailures(statuses: List<Int>) {
        statuses.forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":"failure_$status"}"""))
        }
        val api = api()
        statuses.forEach { status ->
            val failure = runCatching { api.previewMerchantReclassification(TENANT_ID, "Market") }.exceptionOrNull()
            assertTrue("Core HTTP $status must remain visible", failure is ApiFailure)
            assertEquals(status, (failure as ApiFailure).status)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("{\"merchant\":\"Market\"}", request.body.readUtf8())
            assertNull("preview failures must not trigger a hidden retry",
                server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, "$base/realms/finance"), OkHttpClient()).also {
            it.saveTokens("bank-import-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private fun previewJson() = """
        {"merchant":"Market","normalizedMerchant":"market","categoryCode":"еда",
         "candidates":[{"transactionId":"$TRANSACTION_ID","version":3,"currentCategoryCode":"прочее",
         "amount":"125.50","occurredAt":"2026-10-01T09:30:00Z","description":"Card payment"}]}
    """.trimIndent()

    private fun resultJson() = """
        {"merchant":"Market","categoryCode":"еда","changedCount":1,"transactionIds":["$TRANSACTION_ID"]}
    """.trimIndent()

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000047"
        const val TRANSACTION_ID = "00000000-0000-4000-8000-000000000052"
        const val IDEMPOTENCY_KEY = "android-merchant-reclass-apply-0001"
    }
}
