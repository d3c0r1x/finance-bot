package com.decorix.finance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceExportStatusLifecyclePolicyTest {
    @Test fun responseStartedBeforePauseIsRejectedAfterResume() {
        val identity = FinanceExportStatusIdentity(tenantId = "tenant-a", sessionGeneration = 10L)
        val request = FinanceExportStatusRequest(generation = 4L, identity = identity)

        assertFalse(
            FinanceExportStatusPolicy.isCurrent(
                request = request,
                currentGeneration = 5L,
                currentIdentity = identity,
                screenActive = true,
            ),
        )
    }

    @Test fun responseForCurrentLifecycleAndRequestRemainsEligible() {
        val identity = FinanceExportStatusIdentity(tenantId = "tenant-a", sessionGeneration = 10L)
        val request = FinanceExportStatusRequest(generation = 4L, identity = identity)

        assertTrue(
            FinanceExportStatusPolicy.isCurrent(
                request = request,
                currentGeneration = 4L,
                currentIdentity = identity,
                screenActive = true,
            ),
        )
    }

    @Test fun responseFromPreviousTenantIsRejected() {
        val request = FinanceExportStatusRequest(
            generation = 4L,
            identity = FinanceExportStatusIdentity(tenantId = "tenant-a", sessionGeneration = 10L),
        )

        assertFalse(
            FinanceExportStatusPolicy.isCurrent(
                request = request,
                currentGeneration = 4L,
                currentIdentity = FinanceExportStatusIdentity(tenantId = "tenant-b", sessionGeneration = 10L),
                screenActive = true,
            ),
        )
    }

    @Test fun responseFromPreviousSessionIsRejected() {
        val request = FinanceExportStatusRequest(
            generation = 4L,
            identity = FinanceExportStatusIdentity(tenantId = "tenant-a", sessionGeneration = 10L),
        )

        assertFalse(
            FinanceExportStatusPolicy.isCurrent(
                request = request,
                currentGeneration = 4L,
                currentIdentity = FinanceExportStatusIdentity(tenantId = "tenant-a", sessionGeneration = 11L),
                screenActive = true,
            ),
        )
    }

    @Test fun staleStatusCompletionMayClearOnlyItsStillOwnedBusyState() {
        val identity = FinanceExportStatusIdentity(tenantId = "tenant-a", sessionGeneration = 10L)
        val request = FinanceExportStatusRequest(generation = 4L, identity = identity)

        assertFalse(
            FinanceExportStatusPolicy.isCurrent(
                request = request,
                currentGeneration = 4L,
                currentIdentity = FinanceExportStatusIdentity(tenantId = "tenant-b", sessionGeneration = 10L),
                screenActive = true,
            ),
        )
        assertTrue(FinanceExportStatusPolicy.ownsStatusBusy(request, currentGeneration = 4L, busyKind = "status"))
        assertFalse(FinanceExportStatusPolicy.ownsStatusBusy(request, currentGeneration = 5L, busyKind = "status"))
        assertFalse(FinanceExportStatusPolicy.ownsStatusBusy(request, currentGeneration = 4L, busyKind = "create"))
    }

    @Test fun delayedReadyResponseFromPreviousSessionCannotPublishOrOpenUrlOrClearNewBusy() {
        val oldIdentity = FinanceExportStatusIdentity(tenantId = "tenant-a", sessionGeneration = 10L)
        val request = FinanceExportStatusRequest(generation = 4L, identity = oldIdentity)

        val decision = FinanceExportStatusPolicy.completion(
            request = request,
            currentGeneration = 5L,
            currentIdentity = FinanceExportStatusIdentity(tenantId = "tenant-b", sessionGeneration = 11L),
            screenActive = true,
            authenticated = true,
            currentExportId = "export-new",
            requestedExportId = "export-old",
            currentSessionGeneration = 11L,
            busyKind = "status",
            responseStatus = "ready",
            responseDownloadUrl = "https://files.example.test/old.csv",
        )

        assertFalse(decision.publishStatus)
        assertFalse(decision.openDownload)
        assertFalse(decision.clearBusy)
    }
}
