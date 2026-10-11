package com.decorix.finance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceExportStatusLifecyclePolicyTest {
    @Test fun responseStartedBeforePauseIsRejectedAfterResume() {
        val request = FinanceExportStatusRequest(generation = 4L)

        assertFalse(
            FinanceExportStatusPolicy.isCurrent(
                request = request,
                currentGeneration = 5L,
                screenActive = true,
            ),
        )
    }

    @Test fun responseForCurrentLifecycleAndRequestRemainsEligible() {
        val request = FinanceExportStatusRequest(generation = 4L)

        assertTrue(
            FinanceExportStatusPolicy.isCurrent(
                request = request,
                currentGeneration = 4L,
                screenActive = true,
            ),
        )
    }
}
