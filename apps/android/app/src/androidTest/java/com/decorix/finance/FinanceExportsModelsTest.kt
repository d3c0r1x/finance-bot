package com.decorix.finance

import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F52 Android request and Core export-job parsing contract. */
class FinanceExportsModelsTest {
    @Test fun requestKeepsCsvVersionDatesAndOptionalMemberScope() {
        val own = FinanceExportModels.request("2026-10-11", "2026-10-11")
        assertEquals(FinanceExportRequest("csv-v1", "2026-10-11", "2026-10-11", null), own)

        val all = FinanceExportModels.request("2025-10-12", "2026-10-11", "all")
        assertEquals(FinanceExportRequest("csv-v1", "2025-10-12", "2026-10-11", "all"), all)

        val member = FinanceExportModels.request("2026-10-01", "2026-10-31", MEMBER_ID)
        assertEquals(FinanceExportRequest("csv-v1", "2026-10-01", "2026-10-31", MEMBER_ID), member)
    }

    @Test fun requestAcceptsExactlyOneAnd366InclusiveDates() {
        assertEquals("2026-10-11", FinanceExportModels.request(
            "2026-10-11", "2026-10-11").fromDate)
        assertEquals(366, inclusiveDays(FinanceExportModels.request(
            "2025-10-11", "2026-10-11").fromDate, "2026-10-11"))
    }

    @Test fun requestRejectsReversedAndMoreThan366InclusiveDates() {
        assertTrue(runCatching { FinanceExportModels.request("2026-10-12", "2026-10-11") }.isFailure)
        assertTrue(runCatching { FinanceExportModels.request("2025-10-10", "2026-10-11") }.isFailure)
    }

    @Test fun requestOnlyUsesSupportedCsvVersionAndValidScope() {
        val request = FinanceExportModels.request("2026-10-01", "2026-10-01")
        assertEquals("csv-v1", request.formatVersion)
        assertTrue(runCatching {
            FinanceExportRequest("csv-v2", request.fromDate, request.toDate, null)
        }.isFailure)
        assertTrue(runCatching { FinanceExportModels.request("2026-10-01", "2026-10-01", " ") }.isFailure)
    }

    @Test fun queuedAndProcessingJobsNeverExposeDownloadUrl() {
        for (status in listOf("queued", "processing")) {
            val job = FinanceExportModels.job(jobJson(status))
            assertEquals(status, job.status)
            assertNull(job.downloadUrl)
        }
        assertTrue(runCatching {
            FinanceExportModels.job(jobJson("processing").put("downloadUrl", DOWNLOAD_URL))
        }.isFailure)
    }

    @Test fun readyJobPreservesScopeCountDatesAndHttpsDownloadUrl() {
        val job = FinanceExportModels.job(jobJson("ready").put("downloadUrl", DOWNLOAD_URL))

        assertEquals(EXPORT_ID, job.id)
        assertEquals("ready", job.status)
        assertEquals("csv-v1", job.formatVersion)
        assertEquals("2026-10-01", job.fromDate)
        assertEquals("2026-10-31", job.toDate)
        assertEquals(MEMBER_ID, job.memberId)
        assertEquals(false, job.allMembers)
        assertEquals(17, job.rowCount)
        assertEquals(DOWNLOAD_URL, job.downloadUrl)
    }

    @Test fun readyJobWithoutDownloadUrlRemainsNotDownloadable() {
        val job = FinanceExportModels.job(jobJson("ready"))
        assertEquals("ready", job.status)
        assertNull(job.downloadUrl)
    }

    @Test fun parserRejectsMissingNullableMemberIdKey() {
        val json = jobJson("ready").put("allMembers", true).put("memberId", JSONObject.NULL)
            .put("downloadUrl", DOWNLOAD_URL)
        json.remove("memberId")

        assertTrue("missing memberId must not be treated as explicit null", runCatching {
            FinanceExportModels.job(json)
        }.isFailure)
    }

    @Test fun parserRejectsMissingNullableDownloadUrlKey() {
        val json = jobJson("ready").put("downloadUrl", DOWNLOAD_URL)
        json.remove("downloadUrl")

        assertTrue("missing downloadUrl must not be treated as explicit null", runCatching {
            FinanceExportModels.job(json)
        }.isFailure)
    }

    @Test fun failedAndExpiredJobsCannotExposeDownloadUrl() {
        for (status in listOf("failed", "expired")) {
            assertNull(FinanceExportModels.job(jobJson(status)).downloadUrl)
            assertTrue(runCatching {
                FinanceExportModels.job(jobJson(status).put("downloadUrl", DOWNLOAD_URL))
            }.isFailure)
        }
    }

    @Test fun parserRejectsMalformedStatusVersionIdsCountsDatesAndUnsafeUrls() {
        assertJobRejected { it.put("status", "complete") }
        assertJobRejected { it.put("id", "not-a-uuid") }
        assertJobRejected { it.put("formatVersion", "csv-v2") }
        assertJobRejected { it.put("rowCount", -1) }
        assertJobRejected { it.put("rowCount", 1.5) }
        assertJobRejected { it.put("toDate", "2026-09-30") }
        assertJobRejected { it.put("allMembers", true).put("memberId", MEMBER_ID) }
        for (url in listOf(
            "http://files.example.test/export.csv?signature=secret",
            "javascript:alert(1)",
            "https:///missing-host.csv",
            "https://user:password@files.example.test/export.csv",
        )) {
            assertTrue("unsafe URL must be rejected: $url", runCatching {
                FinanceExportModels.job(jobJson("ready").put("downloadUrl", url))
            }.isFailure)
        }
    }

    @Test fun serverFailuresMapToSafeLocalizedCodesWithoutLeakingCoreDetails() {
        assertEquals("too_many_rows", financeExportErrorCode(ApiFailure(413, "private Core detail")))
        assertEquals("forbidden", financeExportErrorCode(ApiFailure(403, "tenant scope rejected")))
        assertEquals("unavailable", financeExportErrorCode(ApiFailure(503, "storage host name")))
        assertEquals("network", financeExportErrorCode(IllegalStateException("private exception text")))
    }

    @Test fun pollingIsFiveSecondsBoundedAndStopsInBackgroundOrOnTerminalJobs() {
        assertEquals(5_000L, FinanceExportPollingPolicy.INTERVAL_MILLIS)
        assertTrue(FinanceExportPollingPolicy.shouldPoll("queued", 0, screenResumed = true))
        assertTrue(FinanceExportPollingPolicy.shouldPoll("processing", 11, screenResumed = true))
        assertTrue(!FinanceExportPollingPolicy.shouldPoll("processing", 12, screenResumed = true))
        assertTrue(!FinanceExportPollingPolicy.shouldPoll("processing", 0, screenResumed = false))
        for (status in listOf("ready", "failed", "expired")) {
            assertTrue("terminal status $status must not be polled",
                !FinanceExportPollingPolicy.shouldPoll(status, 0, screenResumed = true))
        }
    }

    @Test fun onlyCurrentUserRequestedReadyResponseCanOpenFreshSafeUrl() {
        val attempt = FinanceExportDownloadAttempt(TENANT_ID, EXPORT_ID, sessionGeneration = 7, generation = 3)
        val current = FinanceExportDownloadContext(TENANT_ID, EXPORT_ID, 7, 3,
            authenticated = true, screenActive = true, screenResumed = true)
        assertEquals(DOWNLOAD_URL,
            FinanceExportDownloadPolicy.freshUrlIfCurrent(attempt, current, "ready", DOWNLOAD_URL))
        for ((status, url) in listOf(
            "processing" to DOWNLOAD_URL,
            "expired" to DOWNLOAD_URL,
            "ready" to null,
            "ready" to "http://files.example.test/export.csv",
            "ready" to "https://user:password@files.example.test/export.csv",
        )) {
            assertNull(FinanceExportDownloadPolicy.freshUrlIfCurrent(attempt, current, status, url))
        }
    }

    @Test fun downloadAttemptIsDiscardedWhenSessionTenantScreenOrRequestChanges() {
        val attempt = FinanceExportDownloadAttempt(TENANT_ID, EXPORT_ID, sessionGeneration = 7, generation = 3)
        val current = FinanceExportDownloadContext(TENANT_ID, EXPORT_ID, 7, 3,
            authenticated = true, screenActive = true, screenResumed = true)
        val staleContexts = listOf(
            current.copy(tenantId = "other-tenant"),
            current.copy(exportId = "other-export"),
            current.copy(sessionGeneration = 8),
            current.copy(generation = 4),
            current.copy(authenticated = false),
            current.copy(screenActive = false),
            current.copy(screenResumed = false),
        )
        staleContexts.forEach { stale ->
            assertNull(FinanceExportDownloadPolicy.freshUrlIfCurrent(attempt, stale, "ready", DOWNLOAD_URL))
        }
    }

    @Test fun externalDownloadIntentContainsOnlyTheValidatedHttpsUrl() {
        val intent = FinanceExportDownloadIntents.externalView(DOWNLOAD_URL)
        assertEquals(Intent.ACTION_VIEW, intent?.action)
        assertEquals(Uri.parse(DOWNLOAD_URL), intent?.data)
        assertNull(intent?.extras)
        for (unsafe in listOf(
            "http://files.example.test/export.csv",
            "https://user:password@files.example.test/export.csv",
            "javascript:alert(1)",
        )) {
            assertNull(FinanceExportDownloadIntents.externalView(unsafe))
        }
    }

    private fun assertJobRejected(mutate: (JSONObject) -> Unit) {
        val json = jobJson("ready").put("downloadUrl", DOWNLOAD_URL)
        mutate(json)
        assertTrue("malformed export job must fail closed: $json", runCatching {
            FinanceExportModels.job(json)
        }.isFailure)
    }

    private fun jobJson(status: String) = JSONObject()
        .put("id", EXPORT_ID)
        .put("status", status)
        .put("formatVersion", "csv-v1")
        .put("fromDate", "2026-10-01")
        .put("toDate", "2026-10-31")
        .put("memberId", MEMBER_ID)
        .put("allMembers", false)
        .put("rowCount", 17)
        .put("snapshotAt", "2026-10-11T08:00:00Z")
        .put("createdAt", "2026-10-11T08:00:01Z")
        .put("expiresAt", "2026-10-11T08:10:00Z")
        .put("downloadUrl", JSONObject.NULL)

    private fun inclusiveDays(from: String, to: String): Int =
        (java.time.LocalDate.parse(to).toEpochDay() - java.time.LocalDate.parse(from).toEpochDay()).toInt() + 1

    private companion object {
        const val EXPORT_ID = "00000000-0000-4000-8000-000000000052"
        const val TENANT_ID = "00000000-0000-4000-8000-000000000051"
        const val MEMBER_ID = "00000000-0000-4000-8000-000000000053"
        const val DOWNLOAD_URL = "https://files.example.test/private/export.csv?signature=short-lived"
    }
}
