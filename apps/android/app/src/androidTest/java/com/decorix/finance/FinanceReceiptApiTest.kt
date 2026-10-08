package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FinanceReceiptApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun photoUploadRefreshesAfter401AndReplaysSameMultipartFileAndKey() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"fresh-token","refresh_token":"refresh-next","expires_in":300}"""))
        server.enqueue(MockResponse().setResponseCode(202).setBody(jobJson(state = "queued", stage = "queued", progress = 0)))

        val api = api()
        api.saveTokens("stale-token", "refresh-token", System.currentTimeMillis() + 60_000)
        val photo = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x01, 0x02, 0x03, 0xff.toByte(), 0xd9.toByte())
        val key = "receipt-photo-test-key-0001"

        val job = api.uploadReceiptPhoto("tenant-17", photo, "receipt.jpg", "image/jpeg", key)

        assertEquals("job-29", job.id)
        val first = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val refresh = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val replay = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", first.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/photo-jobs", first.path)
        assertEquals("Bearer stale-token", first.getHeader("Authorization"))
        assertEquals(key, first.getHeader("Idempotency-Key"))
        assertEquals("multipart/form-data", first.getHeader("Content-Type")?.substringBefore(';'))
        assertEquals("POST", refresh.method)
        assertEquals("/protocol/openid-connect/token", refresh.path)
        assertEquals("grant_type=refresh_token&client_id=finance-android&refresh_token=refresh-token", refresh.body.readUtf8())
        assertEquals("POST", replay.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/photo-jobs", replay.path)
        assertEquals("Bearer fresh-token", replay.getHeader("Authorization"))
        assertEquals(key, replay.getHeader("Idempotency-Key"))
        assertEquals("multipart/form-data", replay.getHeader("Content-Type")?.substringBefore(';'))
        val firstBody = first.body.readByteArray()
        val replayBody = replay.body.readByteArray()
        assertEquals(firstBody.toList(), replayBody.toList())
        val multipart = replayBody.toString(Charsets.ISO_8859_1)
        assertTrue(multipart.contains("name=\"file\"; filename=\"receipt.jpg\""))
        assertTrue(multipart.contains("Content-Type: image/jpeg"))
        assertTrue(multipart.contains(photo.toString(Charsets.ISO_8859_1)))
    }

    @Test fun receiptJobAndReceiptResponsesParseIntoTypedModels() {
        server.enqueue(MockResponse().setBody(jobJson(state = "completed", stage = "complete", progress = 100)))
        server.enqueue(MockResponse().setBody(receiptJson()))
        val api = api()

        val job = api.receiptJob("tenant-17", "job-29")
        val receipt = api.receipt("tenant-17", "receipt-42")
        assertEquals("/api/v1/tenants/tenant-17/receipt-jobs/job-29",
            server.takeRequest(2, TimeUnit.SECONDS)?.path)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42",
            server.takeRequest(2, TimeUnit.SECONDS)?.path)

        assertEquals("job-29", job.id)
        assertEquals("tenant-17", job.tenantId)
        assertEquals("completed", job.state)
        assertEquals("complete", job.stage)
        assertEquals(100, job.progressPercent)
        assertEquals("receipt-42", job.receiptId)
        assertEquals("receipt-42", receipt.id)
        assertEquals("review_required", receipt.state)
        assertEquals("RUB", receipt.currency)
        assertEquals("245.70", receipt.cashTotal)
        assertEquals("Магазин Тест", receipt.merchant)
        assertEquals(1, receipt.itemCount)
        assertEquals(1, receipt.items.size)
        assertEquals("Хлеб", receipt.items.single().name)
        assertNotNull(receipt.items.single().id)
        assertEquals(null, receipt.transactionId)
    }

    @Test fun concurrentUnauthorizedRequestsRefreshRotatingTokenOnceAndKeepSession() {
        val initialRequests = CountDownLatch(2)
        val releaseUnauthorizedResponses = CountDownLatch(1)
        val secondRefreshObserved = CountDownLatch(1)
        val apiRequestCount = AtomicInteger()
        val refreshRequestCount = AtomicInteger()
        val authorizationHeaders = Collections.synchronizedList(mutableListOf<String?>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/protocol/openid-connect/token" -> {
                    if (refreshRequestCount.incrementAndGet() == 1) {
                        secondRefreshObserved.await(300, TimeUnit.MILLISECONDS)
                        MockResponse().setBody("""{"access_token":"fresh-token","refresh_token":"rotated-token","expires_in":300}""")
                    } else {
                        secondRefreshObserved.countDown()
                        MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                    }
                }
                request.path == "/api/v1/tenants/tenant-17/receipt-jobs/job-29" -> {
                    authorizationHeaders.add(request.getHeader("Authorization"))
                    if (apiRequestCount.incrementAndGet() <= 2) {
                        initialRequests.countDown()
                        check(initialRequests.await(5, TimeUnit.SECONDS)) { "both stale-token requests must arrive" }
                        check(releaseUnauthorizedResponses.await(5, TimeUnit.SECONDS)) { "test must release initial 401 responses" }
                        MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")
                    } else {
                        MockResponse().setBody(jobJson(state = "running", stage = "ocr", progress = 20))
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val api = api()
        api.saveTokens("stale-token", "refresh-token", System.currentTimeMillis() + 60_000)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<Boolean> {
                    runCatching { api.receiptJob("tenant-17", "job-29") }.isSuccess
                }
            }
            assertTrue("both API requests should reach the server", initialRequests.await(5, TimeUnit.SECONDS))
            releaseUnauthorizedResponses.countDown()
            val successfulCalls = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals("one refresh request must rotate the token for both callers", 1, refreshRequestCount.get())
            assertEquals(listOf(true, true), successfulCalls)
            assertEquals(4, apiRequestCount.get())
            assertEquals(2, authorizationHeaders.count { it == "Bearer stale-token" })
            assertEquals(2, authorizationHeaders.count { it == "Bearer fresh-token" })
            assertTrue("successful concurrent refresh must preserve the signed-in session", api.hasSession())
        } finally {
            secondRefreshObserved.countDown()
            releaseUnauthorizedResponses.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun blankRefreshAccessTokenFailsWithoutReplacingExistingSession() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/protocol/openid-connect/token" -> MockResponse().setBody(
                    """{"access_token":"","refresh_token":"rotated-token","expires_in":300}""",
                )
                "/api/v1/tenants/tenant-17/receipt-jobs/job-29" ->
                    MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val api = api()
        api.saveTokens("stale-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val failure = runCatching { api.receiptJob("tenant-17", "job-29") }.exceptionOrNull()

        assertNotNull("a blank refreshed access token must fail the request", failure)
        assertTrue("invalid refresh response must not replace the existing session", api.hasSession())
        val firstRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val refreshRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("Bearer stale-token", firstRequest.getHeader("Authorization"))
        assertEquals("refresh-token", refreshRequest.body.readUtf8().substringAfter("refresh_token="))
    }

    @Test fun logoutDuringRefreshDoesNotRestoreSessionWhenRefreshCompletes() {
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val logoutCompleted = CountDownLatch(1)
        val apiRequestCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/protocol/openid-connect/token" -> {
                    refreshStarted.countDown()
                    check(releaseRefresh.await(5, TimeUnit.SECONDS)) { "test must release pending refresh" }
                    MockResponse().setBody("""{"access_token":"fresh-token","refresh_token":"rotated-token","expires_in":300}""")
                }
                request.path == "/protocol/openid-connect/revoke" -> MockResponse()
                request.path == "/api/v1/tenants/tenant-17/receipt-jobs/job-29" -> {
                    if (apiRequestCount.incrementAndGet() == 1) {
                        MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")
                    } else {
                        MockResponse().setBody(jobJson(state = "running", stage = "ocr", progress = 20))
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val api = api()
        api.saveTokens("stale-token", "refresh-token", System.currentTimeMillis() + 60_000)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val operation = pool.submit<Boolean> {
                runCatching { api.receiptJob("tenant-17", "job-29") }.isSuccess
            }
            assertTrue("request should begin token refresh", refreshStarted.await(5, TimeUnit.SECONDS))
            val logout = pool.submit<Boolean> {
                try {
                    api.logout()
                    true
                } finally {
                    logoutCompleted.countDown()
                }
            }
            // Give an implementation that does not serialize logout with refresh time to complete logout
            // before releasing the refresh response. A serialized or generation-guarded implementation
            // may correctly finish logout after the pending refresh is released.
            logoutCompleted.await(300, TimeUnit.MILLISECONDS)
            releaseRefresh.countDown()
            operation.get(10, TimeUnit.SECONDS)
            assertTrue("logout should return after the in-flight refresh is resolved", logout.get(10, TimeUnit.SECONDS))
            assertFalse("a refresh already in flight must not resurrect a logged-out session", api.hasSession())
        } finally {
            releaseRefresh.countDown()
            pool.shutdownNow()
        }
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient())
    }

    private fun jobJson(state: String, stage: String, progress: Int) = """
        {"id":"job-29","tenantId":"tenant-17","documentId":"document-9","state":"$state",
         "stage":"$stage","progressPercent":$progress,"attemptCount":1,"retryable":false,"errorCode":null,
         "receiptId":"${if (state == "completed") "receipt-42" else ""}",
         "createdAt":"2026-10-08T09:00:00Z","updatedAt":"2026-10-08T09:01:00Z"}
    """.trimIndent()

    private fun receiptJson() = """
        {"id":"receipt-42","tenantId":"tenant-17","documentId":"document-9","state":"review_required",
         "version":1,"transactionId":null,"currency":"RUB","cashTotal":"245.70","itemsTotal":"245.70",
         "merchant":"Магазин Тест","receiptDate":"2026-10-08","selectedReader":"ocr","categoryCode":null,
         "categorySource":"unknown","categoryAlgorithmVersion":"receipt-category-v1","alcoholShare":null,
         "leisureShare":null,"leisure":false,"duplicateDecision":"unknown","duplicateOfReceiptId":null,
         "items":[{"id":"item-3","name":"Хлеб","quantity":"1","unitPrice":"245.70","lineSum":"245.70",
           "productKey":null,"provenance":"ocr","confidence":0.94,"categoryCode":null,"verdict":null,
           "advice":null,"reviewReason":null,"reviewAction":null,"verdictSource":null,"reviewProvider":null,
           "reviewModelVersion":null,"reviewPromptVersion":null,"reviewAlgorithmVersion":null,"version":1}],
         "itemCount":1,"createdAt":"2026-10-08T09:01:00Z"}
    """.trimIndent()
}
