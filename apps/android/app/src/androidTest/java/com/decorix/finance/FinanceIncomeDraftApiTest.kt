package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FinanceIncomeDraftApiTest {
    private val server = MockWebServer()
    private val tokenVault by lazy {
        TokenVault.isolatedForTesting(
            ApplicationProvider.getApplicationContext(),
            "finance_income_draft_api_test_session",
            "finance-income-draft-api-test-v1",
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

    @Test fun incomeDraftRequiresExplicitConfirmationAndHistoryKeepsItsFields() {
        server.enqueue(MockResponse().setBody(draftJson()))
        server.enqueue(MockResponse().setBody("""{"budgetAlerts":[]}"""))
        server.enqueue(MockResponse().setBody("""{"items":[${transactionJson()}],"nextCursor":null}"""))
        val api = api()

        val draft = api.createTransactionDraft(TENANT_ID, "Зарплата июль", DRAFT_KEY)

        assertEquals("income", draft.type)
        assertEquals("123456.78", draft.amount)
        assertEquals("Зарплата за июль", draft.description)
        assertEquals(OCCURRED_AT, draft.occurredAt)
        assertEquals("pending", draft.state)
        val createRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", createRequest.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/transaction-drafts", createRequest.path)
        assertEquals(DRAFT_KEY, createRequest.getHeader("Idempotency-Key"))
        assertEquals("Зарплата июль", JSONObject(createRequest.body.readUtf8()).getString("text"))
        assertEquals("draft review must not send a confirm or direct transaction request", 1, server.requestCount)

        api.confirmTransactionDraft(TENANT_ID, draft)
        val confirmRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", confirmRequest.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/transaction-drafts/$DRAFT_ID/confirm", confirmRequest.path)
        assertEquals("\"$DRAFT_VERSION\"", confirmRequest.getHeader("If-Match"))
        assertTrue(confirmRequest.getHeader("Idempotency-Key")!!.contains(DRAFT_ID))
        assertEquals("{}", confirmRequest.body.readUtf8())
        val posted = api.transactions(TENANT_ID, type = "income").items.single()
        assertEquals("income", posted.type)
        assertEquals("123456.78", posted.amount)
        assertEquals("Зарплата за июль", posted.description)
        assertEquals("text_ai", posted.source)
        assertEquals(OCCURRED_AT, posted.occurredAt)

        val historyRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", historyRequest.method)
        assertTrue(historyRequest.path!!.startsWith("/api/v1/tenants/$TENANT_ID/transactions?"))
        assertTrue(historyRequest.path!!.contains("type=income"))
        assertEquals(3, server.requestCount)
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient(), tokenVault).also {
            it.saveTokens("income-test-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private fun draftJson() = """
        {"id":"$DRAFT_ID","tenantId":"$TENANT_ID","type":"income","amount":"123456.78",
         "currency":"RUB","categoryCode":"salary","subcategoryCode":null,
         "description":"Зарплата за июль","occurredAt":"$OCCURRED_AT","debtId":null,
         "state":"pending","version":$DRAFT_VERSION,"provider":"ollama","modelVersion":"test-model",
         "promptVersion":"transaction-draft.v2"}
    """.trimIndent()

    private fun transactionJson() = """
        {"id":"$TRANSACTION_ID","tenantId":"$TENANT_ID","type":"income","amount":"123456.78",
         "currency":"RUB","categoryCode":"salary","subcategoryCode":null,
         "description":"Зарплата за июль","source":"text_ai","occurredAt":"$OCCURRED_AT",
         "accountId":null,"status":"posted","version":1,"createdAt":"$OCCURRED_AT",
         "memberName":"Test member","debtId":null,"ownerUserId":"member-1"}
    """.trimIndent()

    private companion object {
        const val TENANT_ID = "7ed3e606-0000-4000-8000-000000000001"
        const val DRAFT_ID = "7ed3e606-0000-4000-8000-000000000002"
        const val TRANSACTION_ID = "7ed3e606-0000-4000-8000-000000000003"
        const val DRAFT_KEY = "income-draft-test-key-001"
        const val DRAFT_VERSION = 4
        const val OCCURRED_AT = "2026-07-31T09:15:00+03:00"
    }
}
