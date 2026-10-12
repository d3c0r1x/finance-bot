package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FinanceTransactionVoidApiTest {
    private val server = MockWebServer()
    private var attemptSessionGeneration = 0L
    private val attemptTransactionIds = mutableSetOf(TRANSACTION_ID)

    @After fun clearPendingAttempt() {
        attemptTransactionIds.forEach { transactionId ->
            TransactionVoidAttempts.complete(TENANT_ID, transactionId, SOURCE_VERSION, attemptSessionGeneration)
        }
    }

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun disconnectedVoidRetrySendsSamePathBodyIdempotencyKeyAndSourceVersion() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody(voidedJson()))
        val api = api(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        val attempt = TransactionVoidAttempts.begin(TENANT_ID, source(), api.authSessionGeneration)
            .also { attemptSessionGeneration = it.sessionGeneration }

        val lostResponse = runCatching {
            api.voidTransaction(TENANT_ID, attempt.original, attempt.idempotencyKey,
                expectedSessionGeneration = attempt.sessionGeneration)
        }.exceptionOrNull()
        assertNotNull("first request must fail after server receives it", lostResponse)

        val result = api.voidTransaction(TENANT_ID, attempt.original, attempt.idempotencyKey,
            expectedSessionGeneration = attempt.sessionGeneration)

        assertEquals(TRANSACTION_ID, result.id)
        assertEquals(TENANT_ID, result.tenantId)
        assertEquals("voided", result.status)
        assertEquals(SOURCE_VERSION + 1, result.version)

        val requests = listOf(
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)),
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)),
        )
        requests.forEach { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v1/tenants/$TENANT_ID/transactions/$TRANSACTION_ID/void", request.path)
            assertEquals("Bearer void-test-token", request.getHeader("Authorization"))
            assertEquals(attempt.idempotencyKey, request.getHeader("Idempotency-Key"))
            assertEquals("\"$SOURCE_VERSION\"", request.getHeader("If-Match"))
            assertEquals("{}", request.body.readUtf8())
        }
        assertEquals(requests[0].body.clone().readUtf8(), requests[1].body.clone().readUtf8())
    }

    @Test fun staleAuthenticationSessionIsRejectedWithoutPostingVoid() {
        val api = api()
        val capturedGeneration = api.authSessionGeneration
        val attempt = TransactionVoidAttempts.begin(TENANT_ID, source(), capturedGeneration)
            .also { attemptSessionGeneration = it.sessionGeneration }
        api.clearSession()

        val failure = runCatching {
            api.voidTransaction(TENANT_ID, attempt.original, attempt.idempotencyKey,
                expectedSessionGeneration = attempt.sessionGeneration)
        }.exceptionOrNull()

        assertTrue("stale session must fail as an API authentication error", failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
        assertEquals(0, server.requestCount)
    }

    @Test fun mismatchedVoidAcknowledgementFailsAndKeepsAttemptForRetry() {
        val api = api()
        val attempt = TransactionVoidAttempts.begin(TENANT_ID, source(), api.authSessionGeneration)
            .also { attemptSessionGeneration = it.sessionGeneration }
        server.enqueue(MockResponse().setBody(voidedJson(status = "posted")))

        val failure = runCatching {
            api.voidTransaction(TENANT_ID, attempt.original, attempt.idempotencyKey,
                expectedSessionGeneration = attempt.sessionGeneration)
        }.exceptionOrNull()
        val reentered = TransactionVoidAttempts.begin(TENANT_ID, source(), attempt.sessionGeneration)

        assertNotNull("non-voided response must not acknowledge the intent", failure)
        assertEquals(attempt, reentered)
        assertEquals(attempt.idempotencyKey, reentered.idempotencyKey)
        assertEquals(1, server.requestCount)
    }

    @Test fun memberRenameDoesNotInvalidateOtherwiseExactVoidAcknowledgement() {
        val api = api()
        val attempt = TransactionVoidAttempts.begin(TENANT_ID, source(), api.authSessionGeneration)
            .also { attemptSessionGeneration = it.sessionGeneration }
        server.enqueue(MockResponse().setBody(voidedJson(memberName = "Renamed member")))

        val voided = api.voidTransaction(TENANT_ID, attempt.original, attempt.idempotencyKey,
            expectedSessionGeneration = attempt.sessionGeneration)

        assertEquals(TRANSACTION_ID, voided.id)
        assertEquals(TENANT_ID, voided.tenantId)
        assertEquals("voided", voided.status)
        assertEquals(SOURCE_VERSION + 1, voided.version)
        assertEquals("Renamed member", voided.memberName)
    }

    @Test fun wrongTransactionTenantOrVersionAcknowledgementIsRejected() {
        val invalidResponses = listOf(
            voidedJson(id = OTHER_TRANSACTION_ID),
            voidedJson(tenantId = OTHER_TENANT_ID),
            voidedJson(version = SOURCE_VERSION),
        )

        invalidResponses.forEachIndexed { index, response ->
            val api = api()
            val original = source(id = "7ed3e606-0000-4000-8000-${(10 + index).toString().padStart(12, '0')}")
            attemptTransactionIds += original.id
            val attempt = TransactionVoidAttempts.begin(TENANT_ID, original, api.authSessionGeneration)
                .also { attemptSessionGeneration = it.sessionGeneration }
            server.enqueue(MockResponse().setBody(response))

            val failure = runCatching {
                api.voidTransaction(TENANT_ID, attempt.original, attempt.idempotencyKey,
                    expectedSessionGeneration = attempt.sessionGeneration)
            }.exceptionOrNull()
            val reentered = TransactionVoidAttempts.begin(TENANT_ID, original, attempt.sessionGeneration)

            assertNotNull("invalid acknowledgement $index must fail", failure)
            assertEquals(attempt, reentered)
            assertEquals(attempt.idempotencyKey, reentered.idempotencyKey)
        }
        assertEquals(invalidResponses.size, server.requestCount)
    }

    private fun api(client: OkHttpClient = OkHttpClient()): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), client).also {
            it.saveTokens("void-test-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private fun source(
        id: String = TRANSACTION_ID,
        tenantId: String = TENANT_ID,
    ) = FinanceTransaction(
        id = id, tenantId = tenantId, type = "expense", amount = "247.35", currency = "RUB",
        categoryCode = "transport", subcategoryCode = "taxi", description = "Airport taxi", source = "manual",
        occurredAt = "2026-10-11T10:00:00Z", accountId = "account-1", status = "posted", version = SOURCE_VERSION,
        createdAt = "2026-10-11T10:00:00Z", memberName = "Test member", debtId = null, ownerUserId = "member-1",
    )

    private fun voidedJson(
        id: String = TRANSACTION_ID,
        tenantId: String = TENANT_ID,
        memberName: String = "Test member",
        status: String = "voided",
        version: Long = SOURCE_VERSION + 1,
    ) = """
        {"id":"$id","tenantId":"$tenantId","type":"expense","amount":"247.35",
         "currency":"RUB","categoryCode":"transport","subcategoryCode":"taxi","description":"Airport taxi",
         "source":"manual","occurredAt":"2026-10-11T10:00:00Z","accountId":"account-1","status":"$status",
         "version":$version,"createdAt":"2026-10-11T10:00:00Z","memberName":"$memberName",
         "debtId":null,"ownerUserId":"member-1"}
    """.trimIndent()

    private companion object {
        const val TENANT_ID = "7ed3e606-0000-4000-8000-000000000001"
        const val OTHER_TENANT_ID = "7ed3e606-0000-4000-8000-000000000004"
        const val TRANSACTION_ID = "7ed3e606-0000-4000-8000-000000000002"
        const val OTHER_TRANSACTION_ID = "7ed3e606-0000-4000-8000-000000000005"
        const val SOURCE_VERSION = 4L
    }
}
