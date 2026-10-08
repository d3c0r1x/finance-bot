package com.decorix.finance

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceReceiptPollingPolicyTest {
    @Test fun queuedJobPollsAgainAfterOneSecond() {
        assertEquals(1_000L, receiptPollDelayMillis("queued", 0))
    }

    @Test fun runningJobPollsAgainAfterOneSecond() {
        assertEquals(1_000L, receiptPollDelayMillis("running", 0))
    }

    @Test fun retryableJobPollsAgainAfterFiveSeconds() {
        assertEquals(5_000L, receiptPollDelayMillis("retryable", 0))
        assertEquals(5_000L, receiptPollDelayMillis("retryable", 59))
    }

    @Test fun completedAndRejectedJobsStopAutomaticPolling() {
        assertNull(receiptPollDelayMillis("completed", 0))
        assertNull(receiptPollDelayMillis("rejected", 0))
    }

    @Test fun sixtiethAttemptStopsAutomaticPolling() {
        assertNull(receiptPollDelayMillis("running", 60))
        assertNull(receiptPollDelayMillis("retryable", 60))
    }
}
