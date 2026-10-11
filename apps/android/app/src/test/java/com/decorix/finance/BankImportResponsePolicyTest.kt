package com.decorix.finance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankImportResponsePolicyTest {
    @Test fun appliesOnlyWhenSessionTenantRequestAndScreenStillMatch() {
        assertTrue(BankImportResponsePolicy.canApply(
            requestAuthGeneration = 4, currentAuthGeneration = 4,
            requestTenantId = "tenant-a", currentTenantId = "tenant-a",
            requestGeneration = 8, currentGeneration = 8, screenActive = true,
        ))
    }

    @Test fun rejectsResponseFromReplacedAuthSession() {
        assertFalse(BankImportResponsePolicy.canApply(
            requestAuthGeneration = 4, currentAuthGeneration = 5,
            requestTenantId = "tenant-a", currentTenantId = "tenant-a",
            requestGeneration = 8, currentGeneration = 8, screenActive = true,
        ))
    }

    @Test fun rejectsResponseAfterTenantRequestOrScreenChanges() {
        assertFalse(BankImportResponsePolicy.canApply(
            requestAuthGeneration = 4, currentAuthGeneration = 4,
            requestTenantId = "tenant-a", currentTenantId = "tenant-b",
            requestGeneration = 8, currentGeneration = 8, screenActive = true,
        ))
        assertFalse(BankImportResponsePolicy.canApply(
            requestAuthGeneration = 4, currentAuthGeneration = 4,
            requestTenantId = "tenant-a", currentTenantId = "tenant-a",
            requestGeneration = 8, currentGeneration = 9, screenActive = true,
        ))
        assertFalse(BankImportResponsePolicy.canApply(
            requestAuthGeneration = 4, currentAuthGeneration = 4,
            requestTenantId = "tenant-a", currentTenantId = "tenant-a",
            requestGeneration = 8, currentGeneration = 8, screenActive = false,
        ))
    }

    @Test fun stagedPreviewCanBeRetainedAfterLeavingOnlyWhileSameSessionAndTenantRemain() {
        assertTrue(BankImportResponsePolicy.canCachePreview(
            requestAuthGeneration = 4, currentAuthGeneration = 4,
            requestTenantId = "tenant-a", currentTenantId = "tenant-a",
        ))
        assertFalse(BankImportResponsePolicy.canCachePreview(
            requestAuthGeneration = 4, currentAuthGeneration = 5,
            requestTenantId = "tenant-a", currentTenantId = "tenant-a",
        ))
        assertFalse(BankImportResponsePolicy.canCachePreview(
            requestAuthGeneration = 4, currentAuthGeneration = 4,
            requestTenantId = "tenant-a", currentTenantId = "tenant-b",
        ))
    }

    @Test fun olderRefreshCannotReplaceANewerCorePreviewRevision() {
        val current = preview(revision = 4)
        assertFalse(BankImportResponsePolicy.canReplacePreview(current, preview(revision = 3)))
        assertTrue(BankImportResponsePolicy.canReplacePreview(current, preview(revision = 4)))
        assertTrue(BankImportResponsePolicy.canReplacePreview(current, preview(revision = 5)))
    }

    @Test fun anUndoFinishingAfterReturnToItsScreenRequiresFreshCorePreview() {
        assertTrue(BankImportUndoPolicy.shouldRefreshAfterCompletion(
            screenActive = true, requestStillCurrent = false, sessionMatches = true, tenantMatches = true,
        ))
        assertFalse(BankImportUndoPolicy.shouldRefreshAfterCompletion(
            screenActive = false, requestStillCurrent = false, sessionMatches = true, tenantMatches = true,
        ))
        assertFalse(BankImportUndoPolicy.shouldRefreshAfterCompletion(
            screenActive = true, requestStillCurrent = false, sessionMatches = false, tenantMatches = true,
        ))
        assertFalse(BankImportUndoPolicy.shouldRefreshAfterCompletion(
            screenActive = true, requestStillCurrent = false, sessionMatches = true, tenantMatches = false,
        ))
        assertFalse(BankImportUndoPolicy.shouldRefreshAfterCompletion(
            screenActive = true, requestStillCurrent = true, sessionMatches = true, tenantMatches = true,
        ))
    }

    @Test fun lateReclassificationCompletionCannotClearANewerReviewedAttempt() {
        assertTrue(BankImportReclassificationPolicy.ownsAttempt("apply-key-00000001", "apply-key-00000001"))
        assertFalse(BankImportReclassificationPolicy.ownsAttempt("apply-key-00000002", "apply-key-00000001"))
        assertFalse(BankImportReclassificationPolicy.ownsAttempt(null, "apply-key-00000001"))
    }

    private fun preview(revision: Long) = FinanceBankImportPreview(
        quality = "valid", periodStart = "2026-10-01", periodEnd = "2026-10-01",
        parsedExpenseTotal = "0.00", parsedIncomeTotal = "0.00",
        expectedExpenseTotal = null, expectedIncomeTotal = null, rows = emptyList(),
        id = "same-import", tenantId = "same-tenant", revision = revision,
    )
}
