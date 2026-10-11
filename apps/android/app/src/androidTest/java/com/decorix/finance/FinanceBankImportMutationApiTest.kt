package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FinanceBankImportMutationApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun rowSelectionUsesStableRowRouteRevisionAndReturnsNewCorePreview() {
        server.enqueue(MockResponse().setBody(previewJson(
            revision = 2,
            expenseTotal = "13.00",
            includedCount = 2,
            excludedCount = 0,
            rowType = "expense",
            rowIncluded = true,
            selectionSource = "user",
            exclusionReason = null,
        )))
        val api = api()

        val preview = api.selectBankImportRow(
            TENANT_ID, IMPORT_ID, FEE_ROW_ID, "expense", expectedRevision = 1,
        )

        assertEquals(2L, preview.revision)
        assertEquals("13.00", preview.expenseTotal)
        assertEquals(2, preview.includedCount)
        assertEquals(0, preview.excludedCount)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/imports/$IMPORT_ID/rows/$FEE_ROW_ID", request.path)
        assertEquals("Bearer bank-import-token", request.getHeader("Authorization"))
        assertEquals("\"1\"", request.getHeader("If-Match"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("{\"transactionType\":\"expense\"}", request.body.readUtf8())
    }

    @Test fun nullSelectionExplicitlyRestoresCoreDefaultInsteadOfLocallyGuessing() {
        server.enqueue(MockResponse().setBody(previewJson(
            revision = 3,
            expenseTotal = "12.00",
            excludedTotal = "1.00",
            includedCount = 1,
            excludedCount = 1,
            rowType = null,
            rowIncluded = false,
            selectionSource = "default",
            exclusionReason = "bank_fee",
        )))
        val api = api()

        val preview = api.selectBankImportRow(
            TENANT_ID, IMPORT_ID, FEE_ROW_ID, transactionType = null, expectedRevision = 2,
        )

        assertEquals(3L, preview.revision)
        assertEquals("12.00", preview.expenseTotal)
        assertEquals("1.00", preview.excludedTotal)
        assertFalse(preview.rows.single { it.id == FEE_ROW_ID }.included)
        assertEquals("bank_fee", preview.rows.single { it.id == FEE_ROW_ID }.exclusionReason)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PATCH", request.method)
        assertEquals("\"2\"", request.getHeader("If-Match"))
        assertEquals("{\"transactionType\":null}", request.body.readUtf8())
    }

    @Test fun confirmPostsOnlyAfterExplicitCallWithRevisionAndStablePerImportKey() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"unavailable"}"""))
        server.enqueue(MockResponse().setBody(previewJson(state = "committed", revision = 2,
            createdCount = 1, outcome = "created")))
        val api = api()
        val sessionGeneration = api.authSessionGeneration
        val stableKey = confirmationKey(IMPORT_ID)

        val first = runCatching {
            api.confirmBankImport(TENANT_ID, IMPORT_ID, 1, stableKey, sessionGeneration)
        }.exceptionOrNull()
        assertTrue(first is ApiFailure)
        assertEquals(503, (first as ApiFailure).status)
        val firstRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertConfirmRequest(firstRequest, revision = 1, key = stableKey)
        assertNull("Core confirmation must not be replayed automatically", server.takeRequest(250, TimeUnit.MILLISECONDS))

        val committed = api.confirmBankImport(TENANT_ID, IMPORT_ID, 1, stableKey, sessionGeneration)

        assertEquals("committed", committed.state)
        assertEquals(1, committed.createdCount)
        val retry = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertConfirmRequest(retry, revision = 1, key = stableKey)
    }

    @Test fun confirmSendsMutationOnlyWhenCalledAndNeverReplaysCurrentSession401() {
        val api = api()
        assertNull("preview reads and construction must not create transactions",
            server.takeRequest(250, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"expired"}"""))

        val failure = runCatching {
            api.confirmBankImport(TENANT_ID, IMPORT_ID, 1, confirmationKey(IMPORT_ID), api.authSessionGeneration)
        }.exceptionOrNull()

        assertTrue(failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertConfirmRequest(request, revision = 1, key = confirmationKey(IMPORT_ID))
        assertNull("401 must not refresh tokens or replay a financial mutation",
            server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun staleSessionCannotSendConfirmationWithReplacementAccountToken() {
        val api = api()
        val selectedSession = api.authSessionGeneration
        api.saveTokens("replacement-token", "replacement-refresh", System.currentTimeMillis() + 60_000)

        val failure = runCatching {
            api.confirmBankImport(TENANT_ID, IMPORT_ID, 1, confirmationKey(IMPORT_ID), selectedSession)
        }.exceptionOrNull()

        assertTrue(failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
        assertNull("stale confirmation must not reach Core with replacement session", server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun staleRevisionAndCommittedConflictAreReturnedWithoutMutationReplay() {
        server.enqueue(MockResponse().setResponseCode(412).setBody("""{"code":"stale_revision"}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"import_already_committed"}"""))
        val api = api()

        val stale = runCatching {
            api.selectBankImportRow(TENANT_ID, IMPORT_ID, FEE_ROW_ID, "expense", expectedRevision = 1)
        }.exceptionOrNull()
        val conflict = runCatching {
            api.confirmBankImport(TENANT_ID, IMPORT_ID, 1, confirmationKey(IMPORT_ID), api.authSessionGeneration)
        }.exceptionOrNull()

        assertTrue(stale is ApiFailure)
        assertEquals(412, (stale as ApiFailure).status)
        assertTrue(conflict is ApiFailure)
        assertEquals(409, (conflict as ApiFailure).status)
        val selectionRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PATCH", selectionRequest.method)
        assertEquals("\"1\"", selectionRequest.getHeader("If-Match"))
        val confirmRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertConfirmRequest(confirmRequest, revision = 1, key = confirmationKey(IMPORT_ID))
        assertNull("conflict responses must not trigger hidden retries", server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    private fun assertConfirmRequest(request: okhttp3.mockwebserver.RecordedRequest, revision: Long, key: String) {
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/imports/$IMPORT_ID/confirm", request.path)
        assertEquals("Bearer bank-import-token", request.getHeader("Authorization"))
        assertEquals("\"$revision\"", request.getHeader("If-Match"))
        assertEquals(key, request.getHeader("Idempotency-Key"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("{\"confirmed\":true}", request.body.readUtf8())
    }

    private fun confirmationKey(importId: String) = "android-bank-import-confirm-$importId"

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, "$base/realms/finance"), OkHttpClient()).also {
            it.saveTokens("bank-import-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private fun previewJson(
        state: String = "needs_review",
        revision: Int = 1,
        expenseTotal: String = "12.00",
        excludedTotal: String = "1.00",
        includedCount: Int = 1,
        excludedCount: Int = 1,
        rowType: String? = null,
        rowIncluded: Boolean = false,
        selectionSource: String = "default",
        exclusionReason: String? = "bank_fee",
        createdCount: Int = 0,
        outcome: String = "pending",
    ) = """
        {"id":"$IMPORT_ID","tenantId":"$TENANT_ID","state":"$state","revision":$revision,
         "quality":"valid","parseVersion":"tbank-pdf.v1","periodStart":"2026-09-01",
         "periodEnd":"2026-09-30","parsedExpenseTotal":"12.00","parsedIncomeTotal":"0.00",
         "expectedExpenseTotal":"12.00","expectedIncomeTotal":"0.00","expenseTotal":"$expenseTotal",
         "incomeTotal":"0.00","refundTotal":"0.00","transferTotal":"0.00","excludedTotal":"$excludedTotal",
         "includedCount":$includedCount,"excludedCount":$excludedCount,"createdCount":$createdCount,"duplicateCount":0,
         "rows":[{"id":"$FEE_ROW_ID","ordinal":0,"operationDate":"2026-09-03","operationTime":"08:15",
          "signedAmount":"-1.00","amount":"1.00","kind":"fee","transactionType":${rowType.jsonValue()},
          "included":$rowIncluded,"selectionSource":"$selectionSource","exclusionReason":${exclusionReason.jsonValue()},
          "duplicate":false,"duplicateOfTransactionId":null,"outcome":"$outcome","transactionId":null,
          "merchant":"Bank","description":"Fee","cardLast4":null,"categoryCode":null,
          "categorySource":"unknown","suggestedCategoryCode":null,"categoryConfidence":null,
          "clarificationCandidate":false}]}
    """.trimIndent()

    private fun String?.jsonValue(): String = this?.let { "\"$it\"" } ?: "null"

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000047"
        const val IMPORT_ID = "00000000-0000-4000-8000-000000000048"
        const val FEE_ROW_ID = "00000000-0000-4000-8000-000000000051"
    }
}
