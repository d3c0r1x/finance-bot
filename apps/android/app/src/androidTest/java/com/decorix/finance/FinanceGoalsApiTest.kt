package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
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
class FinanceGoalsApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun getGoalsUsesBearerTenantRouteAndPreservesExactDecimalStringsAndNulls() {
        server.enqueue(MockResponse().setBody(overviewJson()))

        val result = api().getGoals(TENANT_ID)

        assertEquals("sum", result.unit)
        assertEquals("202", result.inputWatermark)
        val candidate = result.candidates.single()
        assertEquals("coffee-week", candidate.key)
        assertEquals("0.12", candidate.monthlyRate)
        assertEquals("999999999999999.99", candidate.monthlySpend)
        assertNull(candidate.monthlyLimit)
        assertEquals("0.01", candidate.estimatedReduction)
        assertNull(candidate.memberProductKeys)
        assertNull(result.active)
        assertNull(result.activeProgress)
        assertEquals("cat:сладкие напитки", result.groups.single().key)
        assertNull(result.groups.single().productKey)
        assertNull(result.groups.single().monthlySpend)
        assertNull(result.skipped.single().monthlySpend)
        assertEquals("2026-10-10T12:00:00Z", result.history.single().completedAt)
        assertNull(result.history.single().goalId)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/goals", request.path)
        assertEquals("Bearer goals-token", request.getHeader("Authorization"))
    }

    @Test fun updateGoalUnitSendsOnlySupportedUnitAndReturnsServerValue() {
        server.enqueue(MockResponse().setBody("""{"unit":"count"}"""))

        val result = api().updateGoalUnit(TENANT_ID, "count")

        assertEquals("count", result.unit)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/goals/unit", request.path)
        assertEquals("Bearer goals-token", request.getHeader("Authorization"))
        assertEquals("""{"unit":"count"}""", request.body.readUtf8())
    }

    @Test fun productCandidateMayOmitOptionalMemberProductKeys() {
        server.enqueue(MockResponse().setBody(
            Regex(",\\s*\"memberProductKeys\":null")
                .replaceFirst(overviewJson(), ""),
        ))

        val result = api().getGoals(TENANT_ID)

        assertNull(result.candidates.single().memberProductKeys)
        assertEquals(listOf("coffee", "latte"), result.groups.single().memberProductKeys)
    }

    @Test fun rejectsCandidateMoneyWithMoreThanTwoFractionalDigits() {
        val json = JSONObject(overviewJson())
        json.getJSONArray("candidates").getJSONObject(0).put("monthlySpend", "1.234")
        server.enqueue(MockResponse().setBody(json.toString()))

        val failure = runCatching { api().getGoals(TENANT_ID) }.exceptionOrNull()

        assertNotNull("Core money fields allow exactly two fractional digits", failure)
    }

    @Test fun rejectsCandidateEvidenceCountBelowCoreMinimum() {
        val json = JSONObject(overviewJson())
        json.getJSONArray("candidates").getJSONObject(0).put("evidenceCount", 0)
        server.enqueue(MockResponse().setBody(json.toString()))

        val failure = runCatching { api().getGoals(TENANT_ID) }.exceptionOrNull()

        assertNotNull("candidate evidenceCount minimum is 1", failure)
    }

    @Test fun rejectsCandidateCountersAboveCoreMaximum() {
        val purchaseCountJson = JSONObject(overviewJson())
        purchaseCountJson.getJSONArray("candidates").getJSONObject(0).put("purchaseCount", 50_001)
        server.enqueue(MockResponse().setBody(purchaseCountJson.toString()))
        val purchaseCountFailure = runCatching { api().getGoals(TENANT_ID) }.exceptionOrNull()

        val evidenceCountJson = JSONObject(overviewJson())
        evidenceCountJson.getJSONArray("candidates").getJSONObject(0).put("evidenceCount", 50_001)
        server.enqueue(MockResponse().setBody(evidenceCountJson.toString()))
        val evidenceCountFailure = runCatching { api().getGoals(TENANT_ID) }.exceptionOrNull()

        assertNotNull("candidate purchaseCount maximum is 50000", purchaseCountFailure)
        assertNotNull("candidate evidenceCount maximum is 50000", evidenceCountFailure)
    }

    @Test fun rejectsActiveProgressBoughtAboveCoreMaximum() {
        val json = JSONObject(overviewJson())
            .put("groups", JSONArray())
            .put("active", JSONObject(countGoalJson()))
        val progress = JSONObject(countProgressJson()).put("bought", 50_001)
        json.put("activeProgress", progress)
        server.enqueue(MockResponse().setBody(json.toString()))

        val failure = runCatching { api().getGoals(TENANT_ID) }.exceptionOrNull()

        assertNotNull("progress bought maximum is 50000", failure)
    }

    @Test fun rejectsHistoryBoughtAboveCoreMaximum() {
        val json = JSONObject(overviewJson())
        json.getJSONArray("history").getJSONObject(0).put("bought", 50_001)
        server.enqueue(MockResponse().setBody(json.toString()))

        val failure = runCatching { api().getGoals(TENANT_ID) }.exceptionOrNull()

        assertNotNull("history bought maximum is 50000", failure)
    }

    @Test fun activeGoalKeepsAcceptedUnitWhenFutureProposalPreferenceChanges() {
        val json = JSONObject(overviewJson())
            .put("groups", JSONArray())
            .put("active", JSONObject(countGoalJson()))
            .put("activeProgress", JSONObject(countProgressJson()))
        server.enqueue(MockResponse().setBody(json.toString()))

        val result = api().getGoals(TENANT_ID)

        assertEquals("sum", result.unit)
        assertEquals("count", result.active?.unit)
        assertEquals(result.active?.unit, result.activeProgress?.unit)
    }

    @Test fun acceptGoalPostsCandidateAndWatermarkThenParsesCreatedGoalWithoutRounding() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(goalJson()))

        val result = api().acceptGoal(TENANT_ID, "coffee-week", "202")

        assertEquals(GOAL_ID, result.id)
        assertEquals("coffee-week", result.key)
        assertEquals("product", result.scope)
        assertEquals("sum", result.unit)
        assertEquals("12.34", result.monthlyRate)
        assertEquals("1234.56", result.monthlySpend)
        assertNull(result.monthlyLimit)
        assertEquals("202", result.inputWatermark)
        assertEquals(1L, result.version)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/goals", request.path)
        assertEquals("Bearer goals-token", request.getHeader("Authorization"))
        assertEquals("""{"candidateKey":"coffee-week","inputWatermark":"202"}""", request.body.readUtf8())
    }

    @Test fun groupCandidateKeyWithSpacesIsPostedUnchanged() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(groupGoalJson()))

        val result = api().acceptGoal(TENANT_ID, "cat:сладкие напитки", "202")

        assertEquals("cat:сладкие напитки", result.key)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("""{"candidateKey":"cat:сладкие напитки","inputWatermark":"202"}""", request.body.readUtf8())
    }

    @Test fun cancelGoalUsesTenantAndGoalIdsAndPreservesUpdatedGoal() {
        server.enqueue(MockResponse().setBody(goalJson().replace("\"status\":\"active\"", "\"status\":\"cancelled\"")
            .replace("\"version\":1", "\"version\":2")))

        val result = api().cancelGoal(TENANT_ID, GOAL_ID)

        assertEquals("cancelled", result.status)
        assertEquals(2L, result.version)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/goals/$GOAL_ID/cancel", request.path)
        assertEquals("Bearer goals-token", request.getHeader("Authorization"))
    }

    @Test fun forbiddenAndStaleConflictResponsesRemainTypedApiFailures() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"reasonCode":"forbidden"}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"reasonCode":"stale_candidate"}"""))
        val api = api()

        val forbidden = runCatching { api.acceptGoal(TENANT_ID, "candidate", "202") }.exceptionOrNull()
        val conflict = runCatching { api.acceptGoal(TENANT_ID, "candidate", "202") }.exceptionOrNull()

        assertTrue("403 must remain an API failure, got $forbidden", forbidden is ApiFailure)
        assertEquals(403, (forbidden as ApiFailure).status)
        assertTrue("409 must remain an API failure, got $conflict", conflict is ApiFailure)
        assertEquals(409, (conflict as ApiFailure).status)
        repeat(2) {
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/api/v1/tenants/$TENANT_ID/goals", request.path)
        }
    }

    @Test fun unauthorizedGoalAcceptIsNotRefreshedOrReplayed() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
        server.enqueue(MockResponse().setBody(
            """{"access_token":"refreshed-token","refresh_token":"next-refresh","expires_in":300}""",
        ))

        val failure = runCatching { api().acceptGoal(TENANT_ID, "candidate", "202") }.exceptionOrNull()

        assertTrue("accept must surface the original 401, got $failure", failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/goals", request.path)
        assertEquals("Bearer goals-token", request.getHeader("Authorization"))
        assertNull("goal creation must not repeat after 401", server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun invalidTenantOrGoalUuidIsRejectedBeforeAnyNetworkRequest() {
        val api = api()

        val badTenant = runCatching { api.getGoals("tenant/other") }.exceptionOrNull()
        val badGoal = runCatching { api.cancelGoal(TENANT_ID, "not-a-uuid") }.exceptionOrNull()

        assertNotNull(badTenant)
        assertNotNull(badGoal)
        assertNull("invalid identifiers must not reach Core", server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient()).also {
            it.saveTokens("goals-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private fun overviewJson() = """
        {"unit":"sum","active":null,"inputWatermark":"202",
         "candidates":[{"key":"coffee-week","productKey":"coffee","name":"Кофе","unit":"sum",
           "monthlyRate":"0.12","countTarget":4,"monthlySpend":"999999999999999.99",
           "monthlyLimit":null,"estimatedReduction":"0.01","purchaseCount":8,"evidenceCount":3,
           "memberProductKeys":null}],
         "groups":[{"key":"cat:сладкие напитки","productKey":null,"name":"Напитки","unit":"sum",
           "monthlyRate":"1.00","countTarget":2,"monthlySpend":null,"monthlyLimit":null,
           "estimatedReduction":null,"purchaseCount":3,"evidenceCount":2,"memberProductKeys":["coffee","latte"]}],
         "skipped":[{"productKey":"unknown","name":"Без суммы","monthlySpend":null,"reasonCode":"missing_amounts"}],
         "activeProgress":null,
         "history":[{"id":"$OUTCOME_ID","goalId":null,"key":"old-goal","name":"Прошлая цель","scope":"product",
           "unit":"count","countTarget":5,"monthlyLimit":null,"bought":3,"spent":null,"met":null,
           "acceptedAt":"2026-09-01T10:00:00Z","completedAt":"2026-10-10T12:00:00Z","origin":"legacy"}]}
    """.trimIndent()

    private fun goalJson() = """
        {"id":"$GOAL_ID","key":"coffee-week","scope":"product","name":"Кофе","unit":"sum",
         "monthlyRate":"12.34","countTarget":4,"monthlySpend":"1234.56",
         "monthlyLimit":null,"evidenceCount":3,"inputWatermark":"202","acceptedAt":"2026-10-11T09:00:00Z",
         "endsAt":"2026-11-10T09:00:00Z","status":"active","version":1}
    """.trimIndent()

    private fun countGoalJson() = """
        {"id":"$GOAL_ID","key":"chips","scope":"product","name":"Чипсы","unit":"count",
         "monthlyRate":"2.00","countTarget":2,"monthlySpend":"25.00","monthlyLimit":null,"evidenceCount":3,
         "inputWatermark":"202","acceptedAt":"2026-10-11T09:00:00Z","endsAt":"2026-11-10T09:00:00Z",
         "status":"active","version":1}
    """.trimIndent()

    private fun countProgressJson() = """
        {"algorithmVersion":"goal-progress-f45.v1","inputWatermark":"202","unit":"count","bought":1,
         "spent":"25.00","amountsUnknown":false,"over":null,"met":true,"finished":false,"daysLeft":29,
         "windowStart":"2026-10-11T09:00:00Z","windowEnd":"2026-11-10T09:00:00Z"}
    """.trimIndent()

    private fun groupGoalJson() = """
        {"id":"$GOAL_ID","key":"cat:сладкие напитки","scope":"group","name":"Сладкие напитки",
         "unit":"sum","monthlyRate":"3.00","countTarget":0,"monthlySpend":"350.00","monthlyLimit":"250.00",
         "evidenceCount":5,"inputWatermark":"202","acceptedAt":"2026-10-11T09:00:00Z",
         "endsAt":"2026-11-10T09:00:00Z","status":"active","version":1}
    """.trimIndent()

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000001"
        const val GOAL_ID = "00000000-0000-4000-8000-000000000045"
        const val OUTCOME_ID = "00000000-0000-4000-8000-000000000099"
    }
}
