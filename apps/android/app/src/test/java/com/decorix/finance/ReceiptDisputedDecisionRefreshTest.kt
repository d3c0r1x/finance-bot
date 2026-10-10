package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.net.SocketTimeoutException
import org.junit.Test

class ReceiptDisputedDecisionRefreshTest {
    @Test fun appliesDecisionBeforeLoadingAuthoritativePageAndDecisionKeys() {
        val events = mutableListOf<String>()
        val expectedPage = FinanceReceiptItemPage(emptyList(), page = 2, totalItems = 10, hasMore = false)
        val expectedDecisions = FinanceProductDecisions(listOf("sugarydrink"), emptyList())

        val result = applyReceiptDisputedDecisionAndRefresh(
            applyDecision = { events += "apply" },
            loadPage = { events += "page"; expectedPage },
            loadDecisions = { events += "decisions"; expectedDecisions },
        )

        assertEquals(listOf("apply", "page", "decisions"), events)
        assertSame(expectedPage, result.page)
        assertSame(expectedDecisions, result.decisions)
    }

    @Test fun mutationFailureReconcilesAuthoritativeReadsAndPreservesCause() {
        val events = mutableListOf<String>()
        val mutationFailure = IllegalStateException("synthetic decision rejection")
        val expectedPage = FinanceReceiptItemPage(emptyList(), 1, 0, false)
        val expectedDecisions = FinanceProductDecisions(emptyList(), emptyList())

        val result = runCatching {
            applyReceiptDisputedDecisionAndRefresh(
                applyDecision = { events += "apply"; throw mutationFailure },
                loadPage = { events += "page"; expectedPage },
                loadDecisions = { events += "decisions"; expectedDecisions },
            )
        }

        val failure = result.exceptionOrNull()
        assertTrue(failure is ReceiptDisputedDecisionMutationFailure)
        assertSame(mutationFailure, failure?.cause)
        assertEquals(listOf("apply", "page", "decisions"), events)
        assertEquals(true, (failure as ReceiptDisputedDecisionMutationFailure).mayHaveApplied)
        assertSame(expectedPage, failure.snapshot?.page)
        assertSame(expectedDecisions, failure.snapshot?.decisions)
    }

    @Test fun pageRefreshFailureIsClassifiedAfterSuccessfulMutationAndStopsDecisionRead() {
        val events = mutableListOf<String>()
        val refreshFailure = IllegalStateException("synthetic disputed page read failure")

        val result = runCatching {
            applyReceiptDisputedDecisionAndRefresh(
                applyDecision = { events += "apply" },
                loadPage = { events += "page"; throw refreshFailure },
                loadDecisions = { events += "decisions"; FinanceProductDecisions(emptyList(), emptyList()) },
            )
        }

        val failure = result.exceptionOrNull()
        assertTrue(failure is ReceiptDisputedDecisionRefreshFailure)
        assertSame(refreshFailure, failure?.cause)
        assertEquals(listOf("apply", "page"), events)
    }

    @Test fun decisionKeyRefreshFailureIsClassifiedAfterMutationAndPageRead() {
        val events = mutableListOf<String>()
        val expectedPage = FinanceReceiptItemPage(emptyList(), page = 1, totalItems = 0, hasMore = false)
        val refreshFailure = IllegalStateException("synthetic product decision read failure")

        val result = runCatching {
            applyReceiptDisputedDecisionAndRefresh(
                applyDecision = { events += "apply" },
                loadPage = { events += "page"; expectedPage },
                loadDecisions = { events += "decisions"; throw refreshFailure },
            )
        }

        val failure = result.exceptionOrNull()
        assertTrue(failure is ReceiptDisputedDecisionRefreshFailure)
        assertSame(refreshFailure, failure?.cause)
        assertEquals(listOf("apply", "page", "decisions"), events)
    }

    @Test fun lostMutationResponseReconcilesAuthoritativeStateAndAttachesSnapshotToFailure() {
        val events = mutableListOf<String>()
        val lostResponse = SocketTimeoutException("server may have committed before response was lost")
        val authoritativePage = FinanceReceiptItemPage(emptyList(), page = 3, totalItems = 17, hasMore = false)
        val authoritativeDecisions = FinanceProductDecisions(listOf("sugarydrink"), emptyList())

        val failure = runCatching {
            applyReceiptDisputedDecisionAndRefresh(
                applyDecision = { events += "apply"; throw lostResponse },
                loadPage = { events += "page"; authoritativePage },
                loadDecisions = { events += "decisions"; authoritativeDecisions },
            )
        }.exceptionOrNull()

        assertTrue(failure is ReceiptDisputedDecisionMutationFailure)
        val mutationFailure = failure as ReceiptDisputedDecisionMutationFailure
        assertSame(lostResponse, mutationFailure.cause)
        assertEquals(true, mutationFailure.mayHaveApplied)
        assertEquals(listOf("apply", "page", "decisions"), events)
        val snapshot = mutationFailure.snapshot!!
        assertSame(authoritativePage, snapshot.page)
        assertSame(authoritativeDecisions, snapshot.decisions)
    }

    @Test fun lostMutationResponseKeepsMutationFailureWhenReconciliationReadFails() {
        val events = mutableListOf<String>()
        val lostResponse = SocketTimeoutException("server may have committed before response was lost")
        val refreshFailure = IllegalStateException("synthetic page read failure")

        val failure = runCatching {
            applyReceiptDisputedDecisionAndRefresh(
                applyDecision = { events += "apply"; throw lostResponse },
                loadPage = { events += "page"; throw refreshFailure },
                loadDecisions = { events += "decisions"; FinanceProductDecisions(emptyList(), emptyList()) },
            )
        }.exceptionOrNull()

        assertTrue(failure is ReceiptDisputedDecisionMutationFailure)
        val mutationFailure = failure as ReceiptDisputedDecisionMutationFailure
        assertSame(lostResponse, mutationFailure.cause)
        assertEquals(true, mutationFailure.mayHaveApplied)
        assertEquals(listOf("apply", "page"), events)
        assertEquals(null, mutationFailure.snapshot)
    }

    @Test fun definitePermissionRejectionDoesNotMarkMutationAsPossiblyApplied() {
        var warningRefreshes = 0
        val failure = runCatching {
            applyReceiptDisputedDecisionAndRefresh(
                applyDecision = { throw ApiFailure(403, "forbidden") },
                loadPage = { FinanceReceiptItemPage(emptyList(), 1, 0, false) },
                loadDecisions = { FinanceProductDecisions(emptyList(), emptyList()) },
                loadWarnings = { warningRefreshes++; FinanceReceiptRepeatWarnings(emptyList()) },
            )
        }.exceptionOrNull() as ReceiptDisputedDecisionMutationFailure

        assertEquals(false, failure.mayHaveApplied)
        assertTrue(failure.snapshot != null)
        assertEquals(0, warningRefreshes)
        assertEquals(null, receiptRepeatWarningErrorAfterDecision(
            previousError = null, refreshRequested = true, mutationMayHaveApplied = failure.mayHaveApplied,
            refreshedWarnings = failure.snapshot?.warnings))
    }

    @Test fun anInFlightWarningRequestIsInvalidatedAndRefreshedAfterSuccessfulDecision() {
        assertTrue(shouldRefreshReceiptRepeatWarnings("receipt-42", "receipt-42", null, loading = true))
        assertEquals(false, receiptRepeatWarningsResponseMatches(
            requestedGeneration = 4, currentGeneration = 5,
            requestedRevision = "item-v1", currentRevision = "item-v2"))

        val events = mutableListOf<String>()
        val refreshed = FinanceReceiptRepeatWarnings(listOf(repeatWarning(count = 4)))
        val result = applyReceiptDisputedDecisionAndRefresh(
            applyDecision = { events += "allow" },
            loadPage = { events += "page"; FinanceReceiptItemPage(emptyList(), 1, 0, false) },
            loadDecisions = { events += "decisions"; FinanceProductDecisions(emptyList(), emptyList()) },
            loadWarnings = { events += "fresh-warning-request"; refreshed },
        )
        assertEquals(listOf("allow", "page", "decisions", "fresh-warning-request"), events)
        assertSame(refreshed, result.warnings)
    }

    @Test fun changedReceiptRevisionInvalidatesRepeatWarningResponse() {
        assertEquals(false, receiptRepeatWarningsResponseMatches(
            requestedGeneration = 8, currentGeneration = 8,
            requestedRevision = "receipt-v1|item-name=Old", currentRevision = "receipt-v2|item-name=New"))
        assertEquals(true, receiptRepeatWarningsResponseMatches(
            requestedGeneration = 8, currentGeneration = 8,
            requestedRevision = "receipt-v1|item-name=Old", currentRevision = "receipt-v1|item-name=Old"))
    }

    @Test fun successfulAllowAndAmbiguousRevokeRefreshAlreadyLoadedRepeatWarningsLast() {
        val previouslyLoaded = FinanceReceiptRepeatWarnings(listOf(repeatWarning(count = 2)))
        val refreshed = FinanceReceiptRepeatWarnings(listOf(repeatWarning(count = 3)))
        val page = FinanceReceiptItemPage(emptyList(), 1, 0, false)
        val decisions = FinanceProductDecisions(listOf("sugarydrink"), emptyList())

        val allowEvents = mutableListOf<String>()
        val allowed = applyReceiptDisputedDecisionAndRefresh(
            applyDecision = { allowEvents += "allow" },
            loadPage = { allowEvents += "page"; page },
            loadDecisions = { allowEvents += "decisions"; decisions },
            invalidateWarnings = { allowEvents += "invalidate-warnings" },
            loadWarnings = { allowEvents += "warnings"; refreshed },
        )
        assertEquals(listOf("allow", "invalidate-warnings", "page", "decisions", "warnings"), allowEvents)
        assertSame(refreshed, allowed.warnings)
        assertTrue(allowed.warnings !== previouslyLoaded)

        val revokeEvents = mutableListOf<String>()
        val lostResponse = SocketTimeoutException("revoke may have committed before its response was lost")
        val revokeFailure = runCatching {
            applyReceiptDisputedDecisionAndRefresh(
                applyDecision = { revokeEvents += "revoke"; throw lostResponse },
                loadPage = { revokeEvents += "page"; page },
                loadDecisions = { revokeEvents += "decisions"; decisions },
                invalidateWarnings = { revokeEvents += "invalidate-warnings" },
                loadWarnings = { revokeEvents += "warnings"; refreshed },
            )
        }.exceptionOrNull() as ReceiptDisputedDecisionMutationFailure
        assertEquals(listOf("revoke", "invalidate-warnings", "page", "decisions", "warnings"), revokeEvents)
        assertSame(lostResponse, revokeFailure.cause)
        assertSame(refreshed, revokeFailure.snapshot?.warnings)
        assertTrue(revokeFailure.snapshot?.warnings !== previouslyLoaded)
    }

    private fun repeatWarning(count: Int) = FinanceReceiptRepeatWarning(
        itemId = "item-cola", name = "Cola", productKey = "sugarydrink", verdict = "harmful",
        title = "Repeated purchase", count = count, lastSum = "2.50", advice = null,
    )
}
