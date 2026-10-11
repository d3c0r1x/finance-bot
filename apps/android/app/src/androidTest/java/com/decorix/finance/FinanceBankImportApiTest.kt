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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FinanceBankImportApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun uploadUsesAuthenticatedPdfMultipartAndPreservesStatementPreview() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(previewJson(
            quality = "valid", parsedExpense = "123456789012345.67", expectedExpense = "123456789012345.67",
            parsedIncome = "800.05", expectedIncome = "800.05",
        )))
        val api = api()

        val preview = api.uploadBankImport(TENANT_ID, PDF_BYTES, "statement.pdf")

        assertEquals(IMPORT_ID, preview.id)
        assertEquals(TENANT_ID, preview.tenantId)
        assertEquals("valid", preview.quality)
        assertEquals("2026-09-01", preview.periodStart)
        assertEquals("2026-09-30", preview.periodEnd)
        assertEquals("123456789012345.67", preview.parsedExpenseTotal)
        assertEquals("123456789012345.67", preview.expectedExpenseTotal)
        assertEquals("800.05", preview.parsedIncomeTotal)
        assertEquals("800.05", preview.expectedIncomeTotal)
        assertEquals(listOf("2026-09-02", "2026-09-03"), preview.rows.map { it.operationDate })
        assertEquals(listOf("-120.50", "25.00"), preview.rows.map { it.signedAmount })
        assertEquals("Market", preview.rows.first().merchant)
        assertEquals("1234", preview.rows.first().cardLast4)
        assertNull(preview.rows.last().merchant)
        assertNull(preview.rows.last().cardLast4)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/imports", request.path)
        assertEquals("Bearer bank-import-token", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data; boundary="))
        val multipart = request.body.readUtf8()
        assertTrue(multipart.contains("name=\"file\"; filename=\"statement.pdf\""))
        assertTrue(multipart.contains("Content-Type: application/pdf"))
        assertTrue(multipart.contains("%PDF-1.7\nsynthetic statement"))
        assertFalse("PDF must not be sent as a receipt image", multipart.contains("image/jpeg"))
    }

    @Test fun previewPreservesMismatchAndUnverifiableQualityAndNullableExpectedTotals() {
        server.enqueue(MockResponse().setBody(previewJson(
            quality = "mismatch", expectedExpense = "900.00", expectedIncome = "100.00",
        )))
        server.enqueue(MockResponse().setBody(previewJson(
            quality = "unverifiable", expectedExpense = null, expectedIncome = null,
        )))
        val api = api()

        val mismatch = api.bankImportPreview(TENANT_ID, IMPORT_ID)
        val unverifiable = api.bankImportPreview(TENANT_ID, IMPORT_ID)

        assertEquals("mismatch", mismatch.quality)
        assertEquals("900.00", mismatch.expectedExpenseTotal)
        assertEquals("100.00", mismatch.expectedIncomeTotal)
        assertEquals("unverifiable", unverifiable.quality)
        assertNull(unverifiable.expectedExpenseTotal)
        assertNull(unverifiable.expectedIncomeTotal)
        assertEquals("1000.00", unverifiable.parsedExpenseTotal)
        assertEquals("100.00", unverifiable.parsedIncomeTotal)

        repeat(2) {
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/api/v1/tenants/$TENANT_ID/imports/$IMPORT_ID/preview", request.path)
            assertEquals("Bearer bank-import-token", request.getHeader("Authorization"))
        }
    }

    @Test fun previewGetCanRefreshAfter401AndReturnsSecondResponse() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"expired"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"access_token":"refreshed-bank-token","refresh_token":"next-refresh","expires_in":300}""",
        ))
        server.enqueue(MockResponse().setBody(previewJson(quality = "valid")))
        val api = api()

        val preview = api.bankImportPreview(TENANT_ID, IMPORT_ID)

        assertEquals("valid", preview.quality)
        val first = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", first.method)
        assertEquals("Bearer bank-import-token", first.getHeader("Authorization"))
        val refresh = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/realms/finance/protocol/openid-connect/token", refresh.path)
        val replay = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", replay.method)
        assertEquals("Bearer refreshed-bank-token", replay.getHeader("Authorization"))
    }

    @Test fun upload401IsNotReplayedWithoutAnIdempotencyContract() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"expired"}"""))
        server.enqueue(MockResponse().setBody(
            """{"access_token":"refreshed-bank-token","refresh_token":"next-refresh","expires_in":300}""",
        ))
        val api = api()

        val failure = runCatching {
            api.uploadBankImport(TENANT_ID, PDF_BYTES, "statement.pdf")
        }.exceptionOrNull()

        assertTrue("upload must return original 401 without refresh/replay, got $failure", failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/imports", request.path)
        assertNull("upload must not be replayed after 401", server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun uploadRejectsAReplacementSessionBeforeSendingTheSelectedPdf() {
        val api = api()
        val selectedSession = api.authSessionGeneration
        api.saveTokens("replacement-token", "replacement-refresh", System.currentTimeMillis() + 60_000)

        val failure = runCatching {
            api.uploadBankImport(TENANT_ID, PDF_BYTES, "statement.pdf", selectedSession)
        }.exceptionOrNull()

        assertTrue("stale upload must fail with an auth error", failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
        assertNull("stale upload must not send the PDF", server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun invalidPdfAndOversizeUploadAreRejectedBeforeNetworkRequest() {
        val api = api()
        val wrongExtension = runCatching { api.uploadBankImport(TENANT_ID, PDF_BYTES, "statement.jpg") }
            .exceptionOrNull()
        val tooLarge = runCatching {
            api.uploadBankImport(TENANT_ID, ByteArray(MAX_PDF_BYTES + 1), "statement.pdf")
        }.exceptionOrNull()

        assertNotNull("only PDF statements may be uploaded", wrongExtension)
        assertNotNull("Core limit is 12 MiB", tooLarge)
        assertNull("invalid uploads must not reach Core", server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun previewRejectsMalformedMoneyCalendarDatesAndRequiredTimes() {
        val valid = previewJson(quality = "valid")
        val invalidPayloads = listOf(
            valid.replace("\"parsedExpenseTotal\":\"1000.00\"", "\"parsedExpenseTotal\":\"1000000000000000000.00\""),
            valid.replace("\"expectedExpenseTotal\":\"1000.00\"", "\"expectedExpenseTotal\":\"-1.00\""),
            valid.replace("\"signedAmount\":\"-120.50\"", "\"signedAmount\":\"-1000000000000000000.00\""),
            valid.replace("\"operationDate\":\"2026-09-02\"", "\"operationDate\":\"2026-02-30\""),
            valid.replace("\"periodStart\":\"2026-09-01\"", "\"periodStart\":\"2026-02-30\""),
            valid.replace("\"operationTime\":\"12:30\"", "\"operationTime\":\"24:00\""),
            valid.replace("\"operationTime\":\"12:30\"", "\"operationTime\":\"12:60\""),
            valid.replace("\"operationTime\":\"12:30\"", "\"operationTime\":null"),
            valid.replace("\"operationTime\":\"12:30\",", ""),
            valid.replace("\"expectedExpenseTotal\":\"1000.00\",", ""),
        )

        invalidPayloads.forEachIndexed { index, payload ->
            assertTrue("Malformed Core preview $index must fail closed",
                runCatching { FinanceBankImportModels.preview(org.json.JSONObject(payload)) }.isFailure)
        }
        val nullableBankTotals = valid.replace("\"expectedExpenseTotal\":\"1000.00\"", "\"expectedExpenseTotal\":null")
        assertNull(FinanceBankImportModels.preview(org.json.JSONObject(nullableBankTotals)).expectedExpenseTotal)
    }

    @Test fun previewAcceptsSchemaMaximumOfEighteenIntegerDigits() {
        val maximum = "999999999999999999.99"
        val payload = previewJson(quality = "valid", parsedExpense = maximum, expectedExpense = maximum)
            .replace("\"signedAmount\":\"-120.50\"", "\"signedAmount\":\"-$maximum\"")

        val preview = FinanceBankImportModels.preview(org.json.JSONObject(payload))

        assertEquals(maximum, preview.parsedExpenseTotal)
        assertEquals(maximum, preview.expectedExpenseTotal)
        assertEquals("-$maximum", preview.rows.first().signedAmount)
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, "$base/realms/finance"), OkHttpClient()).also {
            it.saveTokens("bank-import-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private fun previewJson(
        quality: String,
        parsedExpense: String = "1000.00",
        expectedExpense: String? = "1000.00",
        parsedIncome: String = "100.00",
        expectedIncome: String? = "100.00",
    ) = """
        {"id":"$IMPORT_ID","tenantId":"$TENANT_ID","state":"needs_review","revision":1,
         "quality":"$quality","parseVersion":"tbank-pdf.v1","periodStart":"2026-09-01",
         "periodEnd":"2026-09-30","parsedExpenseTotal":"$parsedExpense","parsedIncomeTotal":"$parsedIncome",
         "expectedExpenseTotal":${expectedExpense.jsonValue()},"expectedIncomeTotal":${expectedIncome.jsonValue()},
         "expenseTotal":"875.00","incomeTotal":"100.00","refundTotal":"25.00","transferTotal":"0.00",
         "excludedTotal":"100.00","includedCount":2,"excludedCount":1,"createdCount":0,"duplicateCount":0,
         "rows":[
           {"id":"$ROW_1_ID","ordinal":0,"operationDate":"2026-09-02","operationTime":"12:30",
            "signedAmount":"-120.50","amount":"120.50","kind":"purchase","transactionType":"expense",
            "included":true,"selectionSource":"default","exclusionReason":null,"duplicate":false,
            "duplicateOfTransactionId":null,"outcome":"pending","transactionId":null,"merchant":"Market",
            "description":"Покупка","cardLast4":"1234","categoryCode":null,"categorySource":"unknown",
            "suggestedCategoryCode":null,"categoryConfidence":null,"clarificationCandidate":false},
           {"id":"$ROW_2_ID","ordinal":1,"operationDate":"2026-09-03","operationTime":"08:15",
            "signedAmount":"25.00","amount":"25.00","kind":"refund","transactionType":"refund",
            "included":true,"selectionSource":"default","exclusionReason":null,"duplicate":false,
            "duplicateOfTransactionId":null,"outcome":"pending","transactionId":null,"merchant":null,
            "description":"Возврат","cardLast4":null,"categoryCode":null,"categorySource":"unknown",
            "suggestedCategoryCode":null,"categoryConfidence":null,"clarificationCandidate":false}
         ]}
    """.trimIndent()

    private fun String?.jsonValue(): String = this?.let { "\"$it\"" } ?: "null"

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000047"
        const val IMPORT_ID = "00000000-0000-4000-8000-000000000048"
        const val ROW_1_ID = "00000000-0000-4000-8000-000000000049"
        const val ROW_2_ID = "00000000-0000-4000-8000-000000000050"
        const val MAX_PDF_BYTES = 12 * 1024 * 1024
        val PDF_BYTES = "%PDF-1.7\nsynthetic statement".toByteArray(Charsets.UTF_8)
    }
}
