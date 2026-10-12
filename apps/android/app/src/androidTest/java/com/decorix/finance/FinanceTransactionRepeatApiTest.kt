package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FinanceTransactionRepeatApiTest {
    private val server = MockWebServer()
    private var attemptSessionGeneration = 0L

    @After fun clearPendingAttempt() {
        TransactionRepeatAttempts.complete(TENANT_ID, TRANSACTION_ID, attemptSessionGeneration)
    }

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun retryReusesProvidedKeyAndExactExpenseBody() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody(transactionJson()))
        val api = api(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        val attempt = TransactionRepeatAttempts.begin(
            TENANT_ID, original(), OCCURRED_AT, api.authSessionGeneration,
        ).also { attemptSessionGeneration = it.sessionGeneration }

        val lostResponse = runCatching {
            api.repeatTransaction(TENANT_ID, original(), attempt.occurredAt, attempt.idempotencyKey,
                expectedSessionGeneration = attempt.sessionGeneration)
        }.exceptionOrNull()
        assertNotNull("first request must fail after server receives full body", lostResponse)
        val retryResult = api.repeatTransaction(TENANT_ID, original(), attempt.occurredAt, attempt.idempotencyKey,
            expectedSessionGeneration = attempt.sessionGeneration)

        assertEquals(REPEATED_TRANSACTION_ID, retryResult.id)
        assertEquals(TENANT_ID, retryResult.tenantId)
        assertEquals("expense", retryResult.type)
        assertEquals("247.35", retryResult.amount)
        assertEquals("Airport taxi", retryResult.description)
        assertEquals(OCCURRED_AT, retryResult.occurredAt)

        val requests = listOf(
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)),
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)),
        )
        requests.forEach { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v1/tenants/$TENANT_ID/transactions", request.path)
            assertEquals("Bearer repeat-test-token", request.getHeader("Authorization"))
            assertEquals(attempt.idempotencyKey, request.getHeader("Idempotency-Key"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(setOf("type", "amount", "currency", "categoryCode", "subcategoryCode",
                "description", "source", "occurredAt"), body.keys().asSequence().toSet())
            assertEquals("expense", body.getString("type"))
            assertEquals("247.35", body.getString("amount"))
            assertEquals("RUB", body.getString("currency"))
            assertEquals("transport", body.getString("categoryCode"))
            assertEquals("taxi", body.getString("subcategoryCode"))
            assertEquals("Airport taxi", body.getString("description"))
            assertEquals("manual", body.getString("source"))
            assertEquals(OCCURRED_AT, body.getString("occurredAt"))
        }
        assertEquals(requests[0].body.clone().readUtf8(), requests[1].body.clone().readUtf8())
    }

    @Test fun changedSessionGenerationRejectsRepeatBeforePost(): Unit {
        val api = api()
        val expectedGeneration = api.authSessionGeneration
        api.clearSession()

        val failure = runCatching {
            api.repeatTransaction(TENANT_ID, original(), OCCURRED_AT, "repeat-key-session-test-001",
                expectedSessionGeneration = expectedGeneration)
        }.exceptionOrNull()

        assertTrue("stale repeat must fail as an authentication/session error", failure is ApiFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun nonPostedResponseIsNotAcceptedAndPendingAttemptRemainsForRetry() {
        server.enqueue(MockResponse().setBody(transactionJson(status = "pending")))
        val api = api()
        val attempt = TransactionRepeatAttempts.begin(
            TENANT_ID, original(), OCCURRED_AT, api.authSessionGeneration,
        ).also { attemptSessionGeneration = it.sessionGeneration }

        val failure = runCatching {
            api.repeatTransaction(TENANT_ID, original(), attempt.occurredAt, attempt.idempotencyKey,
                expectedSessionGeneration = attempt.sessionGeneration)
        }.exceptionOrNull()
        val retry = TransactionRepeatAttempts.begin(
            TENANT_ID, original(), "2026-10-13T09:15:30+03:00", attempt.sessionGeneration,
        )

        assertNotNull("non-posted response must not acknowledge repeat intent", failure)
        assertEquals(attempt, retry)
        assertEquals(OCCURRED_AT, retry.occurredAt)
        assertEquals(1, server.requestCount)
    }

    private fun api(client: OkHttpClient = OkHttpClient()): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), client).also {
            it.saveTokens("repeat-test-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private fun original() = FinanceTransaction(
        id = TRANSACTION_ID, tenantId = TENANT_ID, type = "expense", amount = "247.35", currency = "RUB",
        categoryCode = "transport", subcategoryCode = "taxi", description = "Airport taxi", source = "manual",
        occurredAt = "2026-10-11T10:00:00Z", accountId = "account-1", status = "posted", version = 4,
        createdAt = "2026-10-11T10:00:00Z", memberName = "Test member", debtId = null, ownerUserId = "member-1",
    )

    private fun transactionJson(status: String = "posted") = """
        {"id":"$REPEATED_TRANSACTION_ID","tenantId":"$TENANT_ID","type":"expense","amount":"247.35",
         "currency":"RUB","categoryCode":"transport","subcategoryCode":"taxi","description":"Airport taxi",
         "source":"manual","occurredAt":"$OCCURRED_AT","accountId":null,"status":"$status","version":1,
         "createdAt":"$OCCURRED_AT","memberName":"Test member","debtId":null,"ownerUserId":"member-1"}
    """.trimIndent()

    private companion object {
        const val TENANT_ID = "7ed3e606-0000-4000-8000-000000000001"
        const val TRANSACTION_ID = "7ed3e606-0000-4000-8000-000000000002"
        const val REPEATED_TRANSACTION_ID = "7ed3e606-0000-4000-8000-000000000003"
        const val OCCURRED_AT = "2026-10-12T09:15:30+03:00"
    }
}
