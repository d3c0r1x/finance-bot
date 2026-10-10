package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptConflictRecoveryPolicyTest {
    @Test fun duplicateConflictAndStaleVersionRequireRefreshingReceiptAndCandidates() {
        listOf(409, 412).forEach { status ->
            assertEquals(
                "HTTP $status must refresh the receipt and duplicate candidates",
                ReceiptReviewRecoveryAction.REFRESH_RECEIPT_AND_CANDIDATES,
                ReceiptReviewRecoveryPolicy.actionFor(status),
            )
        }
    }

    @Test fun validationAndServerErrorsAreNotMisclassifiedAsStaleReviewSnapshots() {
        listOf(400, 500).forEach { status ->
            assertEquals(
                "HTTP $status must not be treated as a stale receipt snapshot",
                ReceiptReviewRecoveryAction.SHOW_ERROR,
                ReceiptReviewRecoveryPolicy.actionFor(status),
            )
        }
    }
}
