package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class FinanceAdviceAnalyticsApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun readyReportPreservesExactValuesFourWeeksPendingEffectsAndSeparateF42Marks() {
        server.enqueue(MockResponse().setBody(jobJson("ready", READY_REPORT)))
        val result = api().getAdviceAnalytics(TENANT_ID)

        assertEquals(JOB_ID, result.id)
        assertEquals("ready", result.state)
        assertEquals("202", result.inputWatermark)
        assertEquals("advice-f43.v1", result.algorithmVersion)
        assertEquals("complete", result.completeness)
        val report = requireNotNull(result.report)
        assertEquals("202", report.inputWatermark)
        assertEquals("1200.00", report.savings.monthlyCeiling)
        assertEquals("3600.00", report.savings.groups.single().spend)
        assertEquals(4, report.trend.weeks.size)
        assertEquals("0.175", report.trend.weeks[1].optionalShare)
        assertTrue(report.trend.weeks[1].recalculated)
        assertEquals(1, report.effects.pending.size)
        assertEquals(15, report.effects.pending.single().daysLeft)
        assertEquals(1, report.effects.effects.size)
        assertEquals("-100.0", report.effects.effects.single().change)
        assertEquals(false, report.effects.causalityClaim)
        assertEquals(1, report.recalculation.changedItemCount)
        assertEquals("-50.00", report.recalculation.optionalSpendDelta)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/analytics/advice", request.path)
        assertEquals("Bearer advice-token", request.getHeader("Authorization"))
    }

    @Test fun enqueueIsAnExplicitAuthenticatedPostAndJobCanBePolledToReady() {
        server.enqueue(MockResponse().setResponseCode(202).setBody(jobJson("pending", "null")))
        server.enqueue(MockResponse().setBody(jobJson("processing", "null")))
        server.enqueue(MockResponse().setBody(jobJson("ready", READY_REPORT)))
        val api = api()

        assertEquals("pending", api.requestAdviceAnalytics(TENANT_ID).state)
        val accepted = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", accepted.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/analytics/advice", accepted.path)
        assertEquals("Bearer advice-token", accepted.getHeader("Authorization"))

        assertEquals("processing", api.getAdviceAnalyticsJob(TENANT_ID, JOB_ID).state)
        val poll = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", poll.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/analytics/advice/jobs/$JOB_ID", poll.path)
        assertEquals("ready", api.getAdviceAnalyticsJob(TENANT_ID, JOB_ID).state)
    }

    @Test fun unauthorizedEnqueueIsNotSilentlyRefreshedOrReplayed() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
        // A valid refresh response would expose an unsafe automatic POST replay.
        server.enqueue(MockResponse().setBody(
            """{"access_token":"refreshed-token","refresh_token":"next-refresh","expires_in":300}""",
        ))

        val failure = runCatching { api().requestAdviceAnalytics(TENANT_ID) }.exceptionOrNull()

        assertTrue("enqueue must surface the original 401, got $failure", failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
        val original = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", original.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/analytics/advice", original.path)
        assertEquals("Bearer advice-token", original.getHeader("Authorization"))
        assertNull("durable enqueue must not be replayed after a 401",
            server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test fun partialUnavailableAndNullMeasurementsRemainNullNotZero() {
        val partial = READY_REPORT.replace("\"completeness\":\"complete\"", "\"completeness\":\"partial\"")
            .replaceFirst("\"reasonCode\":\"available\"", "\"reasonCode\":\"missing_amounts\"")
            .replace(Regex("\"savings\":\\{.*?\\},\\s*\"trend\"", RegexOption.DOT_MATCHES_ALL),
                "\"savings\":{\"available\":false,\"reasonCode\":\"missing_amounts\",\"label\":\"theoretical_ceiling_not_actual_savings\",\"days\":90,\"monthlyCeiling\":null,\"shareOfIncome\":null,\"shareOfLimit\":null,\"groups\":[]},\n             \"trend\"")
            .replace(Regex("\"trend\":\\{.*?\\},\\s*\"effects\"", RegexOption.DOT_MATCHES_ALL),
                "\"trend\":{\"available\":false,\"reasonCode\":\"missing_amounts\",\"weeks\":[],\"delta\":null,\"direction\":\"\"},\n             \"effects\"")
            .replace("\"afterSpend\":\"0.00\"", "\"afterSpend\":null")
        server.enqueue(MockResponse().setBody(jobJson("ready", partial, completeness = "partial")))

        val report = requireNotNull(api().getAdviceAnalytics(TENANT_ID).report)

        assertEquals("partial", report.completeness)
        assertNull(report.savings.monthlyCeiling)
        assertNull(report.savings.shareOfIncome)
        assertNull(report.savings.shareOfLimit)
        assertNull(report.trend.delta)
        assertEquals("missing_amounts", report.trend.reasonCode)
        assertTrue(report.trend.weeks.isEmpty())
        assertNull(report.effects.effects.single().afterSpend)
    }

    @Test fun failedStaleAndUnavailableJobsArePreservedWithoutShowingOldReportAsCurrent() {
        server.enqueue(MockResponse().setBody(jobJson("failed", "null", errorCode = "worker_failed")))
        server.enqueue(MockResponse().setBody(jobJson("stale", READY_REPORT)))
        server.enqueue(MockResponse().setBody(jobJson("ready", UNAVAILABLE_REPORT)))
        val api = api()

        val failed = api.getAdviceAnalytics(TENANT_ID)
        assertEquals("failed", failed.state)
        assertEquals("worker_failed", failed.errorCode)
        assertNull(failed.report)
        val stale = api.getAdviceAnalytics(TENANT_ID)
        assertEquals("stale", stale.state)
        assertNotNull("Core may preserve the old report for audit", stale.report)
        val unavailable = requireNotNull(api.getAdviceAnalytics(TENANT_ID).report)
        assertEquals(false, unavailable.savings.available)
        assertEquals("insufficient_history", unavailable.trend.reasonCode)
        assertTrue(unavailable.trend.weeks.isEmpty())
        assertNull(unavailable.trend.delta)
    }

    @Test fun sourceBoundReturnsTyped413WithoutPretendingAJobWasAccepted() {
        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"reasonCode":"too_many_items"}"""))

        val failure = runCatching { api().requestAdviceAnalytics(TENANT_ID) }.exceptionOrNull()

        assertTrue("413 must be surfaced as ApiFailure, got $failure", failure is ApiFailure)
        assertEquals(413, (failure as ApiFailure).status)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/analytics/advice", request.path)
    }

    @Test fun incompleteRequiredReportFieldFailsClosed() {
        val malformed = READY_REPORT.replace(",\"causalityClaim\":false", "")
        server.enqueue(MockResponse().setBody(jobJson("ready", malformed)))

        val failure = runCatching { api().getAdviceAnalytics(TENANT_ID) }.exceptionOrNull()

        assertNotNull("a missing required non-causality marker must not decode as a valid report", failure)
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient()).also {
            it.saveTokens("advice-token", "refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private fun jobJson(
        state: String,
        report: String,
        errorCode: String = "null",
        completeness: String = "complete",
    ) = """
        {"id":"$JOB_ID","state":"$state","inputWatermark":"202",
         "algorithmVersion":"advice-f43.v1","completeness":"$completeness",
         "errorCode":$errorCode,"report":$report,"updatedAt":"2026-10-07T12:00:00Z"}
    """.trimIndent()

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000001"
        const val JOB_ID = "00000000-0000-4000-8000-000000000043"

        val READY_REPORT = """
            {"algorithmVersion":"advice-f43.v1","inputWatermark":"202","completeness":"complete","reasonCode":"available",
             "savings":{"available":true,"reasonCode":"available","label":"theoretical_ceiling_not_actual_savings","days":90,
               "monthlyCeiling":"1200.00","shareOfIncome":"6.0","shareOfLimit":"12.0",
               "groups":[{"productKey":"snack","name":"Снеки","count":3,"spend":"3600.00","monthlyCeiling":"1200.00"}]},
             "trend":{"available":true,"reasonCode":"available","weeks":[
               {"start":"2026-09-10","end":"2026-09-16","spend":"5000.00","optionalSpend":"1500.00","optionalShare":"0.300","itemCount":8,"recalculated":false},
               {"start":"2026-09-17","end":"2026-09-23","spend":"4000.00","optionalSpend":"700.00","optionalShare":"0.175","itemCount":6,"recalculated":true},
               {"start":"2026-09-24","end":"2026-09-30","spend":"3000.00","optionalSpend":"900.00","optionalShare":"0.300","itemCount":5,"recalculated":false},
               {"start":"2026-10-01","end":"2026-10-07","spend":"6000.00","optionalSpend":"1200.00","optionalShare":"0.200","itemCount":9,"recalculated":false}],"delta":"-0.125","direction":"down"},
             "effects":{"effects":[{"productKey":"snack","name":"Снеки","advice":"Покупать реже","beforeCount":2,"afterCount":0,
               "daysBefore":35,"daysAfter":30,"intervalBefore":"17.5","intervalAfter":null,"change":"-100.0","direction":"less_often","afterSpend":"0.00"}],
               "pending":[{"productKey":"candy","name":"Конфеты","daysAfter":6,"daysLeft":15}],"causalityClaim":false},
             "recalculation":{"available":true,"changedItemCount":1,"optionalSpendDelta":"-50.00","windows":[
               {"start":"2026-09-17","end":"2026-09-23","changedItemCount":1,"optionalSpendDelta":"-50.00"}]}}
        """.trimIndent()

        val UNAVAILABLE_REPORT = READY_REPORT
            .replaceFirst("\"reasonCode\":\"available\"", "\"reasonCode\":\"insufficient_history\"")
            .replace(Regex("\"savings\":\\{.*?\\},\\s*\"trend\"", RegexOption.DOT_MATCHES_ALL),
                "\"savings\":{\"available\":false,\"reasonCode\":\"insufficient_history\",\"label\":\"theoretical_ceiling_not_actual_savings\",\"days\":90,\"monthlyCeiling\":null,\"shareOfIncome\":null,\"shareOfLimit\":null,\"groups\":[]},\n             \"trend\"")
            .replace(Regex("\"trend\":\\{.*?\\},\\s*\"effects\"", RegexOption.DOT_MATCHES_ALL),
                "\"trend\":{\"available\":false,\"reasonCode\":\"insufficient_history\",\"weeks\":[],\"delta\":null,\"direction\":\"\"},\n             \"effects\"")
            .replace(Regex("\"effects\":\\{.*?\\},\\s*\"recalculation\"", RegexOption.DOT_MATCHES_ALL),
                "\"effects\":{\"effects\":[],\"pending\":[],\"causalityClaim\":false},\n             \"recalculation\"")
            .replace(Regex("\"recalculation\":\\{.*?\\}\\}$", RegexOption.DOT_MATCHES_ALL),
                "\"recalculation\":{\"available\":false,\"changedItemCount\":0,\"optionalSpendDelta\":null,\"windows\":[]}}")
    }
}
