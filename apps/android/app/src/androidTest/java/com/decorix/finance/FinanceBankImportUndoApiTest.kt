package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FinanceBankImportUndoApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun successfulUndoPostsExactEmptyRequestAndParsesCoreResult() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            undoJson(state = "reverted", revision = 3, revertedCount = 2, conflicts = emptyList()),
        ))
        val api = api()

        val result = api.undoBankImport(TENANT_ID, IMPORT_ID, 2, UNDO_KEY, api.authSessionGeneration)

        assertEquals(FinanceBankImportUndoResult(IMPORT_ID, "reverted", 3, 2, emptyList()), result)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertUndoRequest(request, revision = 2, key = UNDO_KEY)
    }

    @Test fun manualRetryUsesSameIdempotencyKeyAndDoesNotRetryTransportFailureItself() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"unavailable"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            undoJson(state = "reverted", revision = 3, revertedCount = 2, conflicts = emptyList()),
        ))
        val api = api()
        val session = api.authSessionGeneration

        val first = runCatching { api.undoBankImport(TENANT_ID, IMPORT_ID, 2, UNDO_KEY, session) }.exceptionOrNull()

        assertTrue(first is ApiFailure)
        assertEquals(503, (first as ApiFailure).status)
        assertUndoRequest(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)), revision = 2, key = UNDO_KEY)
        assertNull("Failed undo must not be hiddenly replayed", server.takeRequest(250, TimeUnit.MILLISECONDS))

        val retry = api.undoBankImport(TENANT_ID, IMPORT_ID, 2, UNDO_KEY, session)

        assertEquals("reverted", retry.state)
        assertUndoRequest(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)), revision = 2, key = UNDO_KEY)
        assertNull("Only explicit retry may repeat undo", server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun structuredConflictReturnsCoreUndoResultWithoutRetry() {
        server.enqueue(MockResponse().setResponseCode(409).setBody(
            undoJson(state = "committed", revision = 2, revertedCount = 0, conflicts = listOf(TRANSACTION_ID)),
        ))
        val api = api()

        val result = api.undoBankImport(TENANT_ID, IMPORT_ID, 2, UNDO_KEY, api.authSessionGeneration)

        assertEquals(FinanceBankImportUndoResult(
            IMPORT_ID, "committed", 2, 0, listOf(TRANSACTION_ID),
        ), result)
        assertUndoRequest(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)), revision = 2, key = UNDO_KEY)
        assertNull("Structured conflict must not retry an unsafe undo", server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun malformedOrGenericConflictRemainsHttpFailure() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"undo_conflict"}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody(undoJson(
            state = "committed", revision = 2, revertedCount = 0, conflicts = listOf("not-a-uuid"),
        )))
        server.enqueue(MockResponse().setResponseCode(409).setBody(
            undoJson(state = "reverted", revision = 3, revertedCount = 2, conflicts = emptyList()),
        ))
        val api = api()

        val generic = runCatching {
            api.undoBankImport(TENANT_ID, IMPORT_ID, 2, UNDO_KEY, api.authSessionGeneration)
        }.exceptionOrNull()
        val malformed = runCatching {
            api.undoBankImport(TENANT_ID, IMPORT_ID, 2, UNDO_KEY, api.authSessionGeneration)
        }.exceptionOrNull()
        val wrongConflictState = runCatching {
            api.undoBankImport(TENANT_ID, IMPORT_ID, 2, UNDO_KEY, api.authSessionGeneration)
        }.exceptionOrNull()

        assertTrue(generic is ApiFailure)
        assertEquals(409, (generic as ApiFailure).status)
        assertTrue(malformed is ApiFailure)
        assertEquals(409, (malformed as ApiFailure).status)
        assertTrue("409 may return a DTO only while Core leaves batch committed", wrongConflictState is ApiFailure)
        assertEquals(409, (wrongConflictState as ApiFailure).status)
        assertUndoRequest(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)), revision = 2, key = UNDO_KEY)
        assertUndoRequest(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)), revision = 2, key = UNDO_KEY)
        assertUndoRequest(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)), revision = 2, key = UNDO_KEY)
        assertNull("Invalid conflict bodies must not trigger retries", server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun unauthorizedUndoDoesNotRefreshOrReplay() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"expired"}"""))
        val api = api()

        val failure = runCatching {
            api.undoBankImport(TENANT_ID, IMPORT_ID, 2, UNDO_KEY, api.authSessionGeneration)
        }.exceptionOrNull()

        assertTrue(failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
        assertUndoRequest(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)), revision = 2, key = UNDO_KEY)
        assertNull("401 undo must not call token refresh or replay POST", server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun staleRevisionIsPreservedAs412WithoutRetry() {
        server.enqueue(MockResponse().setResponseCode(412).setBody("""{"code":"stale_revision"}"""))
        val api = api()

        val failure = runCatching {
            api.undoBankImport(TENANT_ID, IMPORT_ID, 2, UNDO_KEY, api.authSessionGeneration)
        }.exceptionOrNull()

        assertTrue(failure is ApiFailure)
        assertEquals(412, (failure as ApiFailure).status)
        assertUndoRequest(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)), revision = 2, key = UNDO_KEY)
        assertNull("Stale revision must not auto-retry undo", server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun replacementSessionRejectsUndoBeforeNetworkRequest() {
        val api = api()
        val selectedSession = api.authSessionGeneration
        api.saveTokens("replacement-token", "replacement-refresh", System.currentTimeMillis() + 60_000)

        val failure = runCatching {
            api.undoBankImport(TENANT_ID, IMPORT_ID, 2, UNDO_KEY, selectedSession)
        }.exceptionOrNull()

        assertTrue(failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
        assertNull("Old screen session must not send undo with replacement credentials",
            server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun accountReplacementWaitsForSessionBoundUndoRequestToFinish() {
        val requestArrived = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestArrived.countDown()
                check(releaseResponse.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release undo response" }
                return MockResponse().setResponseCode(200)
                    .setBody(undoJson("reverted", 3, 2, emptyList()))
            }
        }
        val api = api()
        val requestSession = api.authSessionGeneration
        val undoResult = AtomicReference<FinanceBankImportUndoResult?>(null)
        val undoFailure = AtomicReference<Throwable?>(null)
        val undoThread = Thread({
            try {
                undoResult.set(api.undoBankImport(TENANT_ID, IMPORT_ID, 2, UNDO_KEY, requestSession))
            } catch (failure: Throwable) {
                undoFailure.set(failure)
            }
        }, "session-bound-bank-import-undo")
        val replacementStarted = CountDownLatch(1)
        val replacementCompleted = CountDownLatch(1)
        val replacementFailure = AtomicReference<Throwable?>(null)
        val replacement = Thread({
            replacementStarted.countDown()
            try {
                api.saveTokens("replacement-token", "replacement-refresh", System.currentTimeMillis() + 60_000)
            } catch (failure: Throwable) {
                replacementFailure.set(failure)
            } finally {
                replacementCompleted.countDown()
            }
        }, "session-replacement-during-undo")

        try {
            undoThread.start()
            assertTrue("Undo request did not reach the server", requestArrived.await(2, TimeUnit.SECONDS))
            replacement.start()
            assertTrue("Session replacement did not start", replacementStarted.await(2, TimeUnit.SECONDS))
            assertFalse("Session replacement must serialize with the in-flight undo",
                replacementCompleted.await(250, TimeUnit.MILLISECONDS))
            assertEquals(requestSession, api.authSessionGeneration)
        } finally {
            releaseResponse.countDown()
            undoThread.join(5_000)
            if (replacement.state != Thread.State.NEW) replacement.join(5_000)
        }

        assertFalse("Undo request did not finish", undoThread.isAlive)
        assertFalse("Session replacement did not finish", replacement.isAlive)
        assertNull(undoFailure.get())
        assertNull(replacementFailure.get())
        assertEquals("reverted", requireNotNull(undoResult.get()).state)
        assertTrue("Session replacement must complete after undo releases its guard",
            api.authSessionGeneration > requestSession)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("Bearer bank-import-token", request.getHeader("Authorization"))
    }

    @Test fun undoResultValidatesIdentifiersStateRevisionCountAndConflictShape() {
        val success = FinanceBankImportModels.undoResult(JSONObject(
            undoJson("reverted", 3, 2, emptyList()),
        ))
        val conflict = FinanceBankImportModels.undoResult(JSONObject(
            undoJson("committed", 2, 0, listOf(TRANSACTION_ID)),
        ))
        assertEquals(IMPORT_ID, success.id)
        assertEquals(TRANSACTION_ID, conflict.conflicts.single())

        val invalid = listOf(
            undoJson("reverted", 3, 2, emptyList()).replace(IMPORT_ID, "not-a-uuid"),
            undoJson("needs_review", 3, 2, emptyList()),
            undoJson("reverted", 0, 2, emptyList()),
            undoJson("reverted", 3, -1, emptyList()),
            undoJson("committed", 2, 0, emptyList()),
            undoJson("reverted", 3, 2, listOf(TRANSACTION_ID)).replace("\"conflicts\":[\"$TRANSACTION_ID\"]", "\"conflicts\":null"),
            undoJson("reverted", 3, 2, emptyList()).replace("\"revision\":3", "\"revision\":3.5"),
            undoJson("reverted", 3, 2, emptyList()).replace("\"revertedCount\":2", "\"revertedCount\":2.5"),
            undoJson("reverted", 3, 2, emptyList()).replace("\"revision\":3", "\"revision\":\"3\""),
        )
        invalid.forEachIndexed { index, payload ->
            assertTrue("Malformed undo result $index must fail closed",
                runCatching { FinanceBankImportModels.undoResult(JSONObject(payload)) }.isFailure)
        }
    }

    private fun assertUndoRequest(request: RecordedRequest, revision: Long, key: String) {
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/imports/$IMPORT_ID/undo", request.path)
        assertEquals("Bearer bank-import-token", request.getHeader("Authorization"))
        assertEquals("\"$revision\"", request.getHeader("If-Match"))
        assertEquals(key, request.getHeader("Idempotency-Key"))
        assertEquals(0L, request.body.size)
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, "$base/realms/finance"), OkHttpClient()).also {
            it.saveTokens("bank-import-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private fun undoJson(state: String, revision: Long, revertedCount: Int, conflicts: List<String>): String =
        JSONObject()
            .put("id", IMPORT_ID)
            .put("state", state)
            .put("revision", revision)
            .put("revertedCount", revertedCount)
            .put("conflicts", org.json.JSONArray(conflicts))
            .toString()

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000047"
        const val IMPORT_ID = "00000000-0000-4000-8000-000000000048"
        const val TRANSACTION_ID = "00000000-0000-4000-8000-000000000051"
        const val UNDO_KEY = "android-bank-import-undo-000048"
    }
}
