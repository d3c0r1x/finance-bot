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
class FinanceBankImportCategoryApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun classifyPostsOnlyRevisionAndParsesCoreHypothesisAndClarificationFields() {
        server.enqueue(MockResponse().setBody(previewJson(
            revision = 2,
            categoryCode = null,
            categorySource = "model",
            suggestedCategoryCode = "еда",
            categoryConfidence = "0.875",
            clarificationCandidate = true,
        )))
        val api = api()

        val preview = api.classifyBankImport(TENANT_ID, IMPORT_ID, expectedRevision = 1)

        assertEquals(2L, preview.revision)
        val row = preview.rows.single()
        assertNull(row.categoryCode)
        assertEquals("model", row.categorySource)
        assertEquals("еда", row.suggestedCategoryCode)
        assertEquals("0.875", row.categoryConfidence)
        assertTrue(row.clarificationCandidate)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/imports/$IMPORT_ID/classify", request.path)
        assertEquals("Bearer bank-import-token", request.getHeader("Authorization"))
        assertEquals("\"1\"", request.getHeader("If-Match"))
        assertNull(request.getHeader("Idempotency-Key"))
        assertEquals(0L, request.body.size)
    }

    @Test fun explicitCategorySelectionUsesRowRouteRevisionAndReturnsHumanMapping() {
        server.enqueue(MockResponse().setBody(previewJson(
            revision = 3,
            categoryCode = "транспорт",
            categorySource = "human",
            suggestedCategoryCode = "еда",
            categoryConfidence = "0.875",
            clarificationCandidate = false,
        )))
        val api = api()

        val preview = api.selectBankImportCategory(
            TENANT_ID, IMPORT_ID, ROW_ID, "транспорт", expectedRevision = 2,
        )

        assertEquals(3L, preview.revision)
        val row = preview.rows.single()
        assertEquals("транспорт", row.categoryCode)
        assertEquals("human", row.categorySource)
        assertEquals("еда", row.suggestedCategoryCode)
        assertEquals("0.875", row.categoryConfidence)
        assertEquals(false, row.clarificationCandidate)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/imports/$IMPORT_ID/rows/$ROW_ID/category", request.path)
        assertEquals("Bearer bank-import-token", request.getHeader("Authorization"))
        assertEquals("\"2\"", request.getHeader("If-Match"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertNull(request.getHeader("Idempotency-Key"))
        assertEquals("{\"categoryCode\":\"транспорт\"}", request.body.readUtf8())
    }

    @Test fun classifySurfacesCoreRevisionAndProviderFailuresWithoutRetry() {
        listOf(403, 409, 412, 413, 502, 503).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":"failure_$status"}"""))
        }
        val api = api()

        listOf(403, 409, 412, 413, 502, 503).forEach { status ->
            val failure = runCatching {
                api.classifyBankImport(TENANT_ID, IMPORT_ID, expectedRevision = 1)
            }.exceptionOrNull()

            assertTrue("Core HTTP $status must remain visible", failure is ApiFailure)
            assertEquals(status, (failure as ApiFailure).status)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("\"1\"", request.getHeader("If-Match"))
            assertNull("F50 classify must never be silently retried", server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun categorySelectionPreservesCoreValidationPermissionAndRevisionErrors() {
        listOf(400, 403, 409, 412).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"code":"failure_$status"}"""))
        }
        val api = api()

        listOf(400, 403, 409, 412).forEach { status ->
            val failure = runCatching {
                api.selectBankImportCategory(TENANT_ID, IMPORT_ID, ROW_ID, "еда", expectedRevision = 4)
            }.exceptionOrNull()

            assertTrue("Core HTTP $status must remain visible", failure is ApiFailure)
            assertEquals(status, (failure as ApiFailure).status)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("PUT", request.method)
            assertEquals("\"4\"", request.getHeader("If-Match"))
            assertEquals("{\"categoryCode\":\"еда\"}", request.body.readUtf8())
            assertNull("category errors must not trigger a hidden retry",
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

    private fun previewJson(
        revision: Int,
        categoryCode: String?,
        categorySource: String,
        suggestedCategoryCode: String?,
        categoryConfidence: String?,
        clarificationCandidate: Boolean,
    ) = """
        {"id":"$IMPORT_ID","tenantId":"$TENANT_ID","state":"needs_review","revision":$revision,
         "quality":"valid","parseVersion":"tbank-pdf.v1","periodStart":"2026-09-01",
         "periodEnd":"2026-09-30","parsedExpenseTotal":"15.00","parsedIncomeTotal":"0.00",
         "expectedExpenseTotal":"15.00","expectedIncomeTotal":"0.00","expenseTotal":"15.00",
         "incomeTotal":"0.00","refundTotal":"0.00","transferTotal":"0.00","excludedTotal":"0.00",
         "includedCount":1,"excludedCount":0,"createdCount":0,"duplicateCount":0,
         "rows":[{"id":"$ROW_ID","ordinal":0,"operationDate":"2026-09-03","operationTime":"08:15",
          "signedAmount":"-15.00","amount":"15.00","kind":"purchase","transactionType":"expense",
          "included":true,"selectionSource":"default","exclusionReason":null,"duplicate":false,
          "duplicateOfTransactionId":null,"outcome":"pending","transactionId":null,"merchant":"Market",
          "description":"Purchase","cardLast4":null,"categoryCode":${categoryCode.jsonValue()},
          "categorySource":"$categorySource","suggestedCategoryCode":${suggestedCategoryCode.jsonValue()},
          "categoryConfidence":${categoryConfidence.jsonValue()},"clarificationCandidate":$clarificationCandidate}]}
    """.trimIndent()

    private fun String?.jsonValue(): String = this?.let { "\"$it\"" } ?: "null"

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000047"
        const val IMPORT_ID = "00000000-0000-4000-8000-000000000048"
        const val ROW_ID = "00000000-0000-4000-8000-000000000051"
    }
}
