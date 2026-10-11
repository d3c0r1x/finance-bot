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
}
