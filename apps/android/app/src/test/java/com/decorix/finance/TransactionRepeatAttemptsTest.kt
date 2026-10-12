package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.After
import org.junit.Test

class TransactionRepeatAttemptsTest {
    @After fun clearPendingAttempt() {
        TransactionRepeatAttempts.complete(TENANT_ID, TRANSACTION_ID)
    }

    @Test fun retryKeepsSameIntentUntilExplicitSuccessThenNextClickGetsFreshKey() {
        val firstOccurredAt = "2026-10-12T09:15:30+03:00"
        val retryOccurredAt = "2026-10-13T09:15:30+03:00"
        val first = TransactionRepeatAttempts.begin(TENANT_ID, original(), firstOccurredAt)
        val retry = TransactionRepeatAttempts.begin(TENANT_ID, original(), retryOccurredAt)

        assertEquals(first, retry)
        assertEquals(original(), retry.original)
        assertEquals(firstOccurredAt, retry.occurredAt)
        assertEquals(first.idempotencyKey, retry.idempotencyKey)

        TransactionRepeatAttempts.complete(TENANT_ID, original().id)
        val nextClick = TransactionRepeatAttempts.begin(TENANT_ID, original(), retryOccurredAt)

        assertNotSame(first, nextClick)
        assertNotEquals(first.idempotencyKey, nextClick.idempotencyKey)
        assertEquals(retryOccurredAt, nextClick.occurredAt)
    }

    @Test fun changedAuthSessionGenerationDoesNotReusePendingSnapshotOrKey() {
        val firstOccurredAt = "2026-10-12T09:15:30+03:00"
        val otherSessionOccurredAt = "2026-10-13T09:15:30+03:00"
        val firstSession = TransactionRepeatAttempts.begin(
            TENANT_ID, original(), firstOccurredAt, authSessionGeneration = 41L,
        )
        val retryInSameSession = TransactionRepeatAttempts.begin(
            TENANT_ID, original(), otherSessionOccurredAt, authSessionGeneration = 41L,
        )
        val nextSession = TransactionRepeatAttempts.begin(
            TENANT_ID, original(), otherSessionOccurredAt, authSessionGeneration = 42L,
        )

        assertEquals(firstSession, retryInSameSession)
        assertNotSame(firstSession, nextSession)
        assertNotEquals(firstSession.idempotencyKey, nextSession.idempotencyKey)
        assertEquals(firstOccurredAt, retryInSameSession.occurredAt)
        assertEquals(otherSessionOccurredAt, nextSession.occurredAt)
        assertEquals(original(), nextSession.original)
    }

    private fun original() = FinanceTransaction(
        id = TRANSACTION_ID,
        tenantId = TENANT_ID,
        type = "expense",
        amount = "247.35",
        currency = "RUB",
        categoryCode = "transport",
        subcategoryCode = "taxi",
        description = "Airport taxi",
        source = "manual",
        occurredAt = "2026-10-11T10:00:00Z",
        accountId = "account-1",
        status = "posted",
        version = 4,
        createdAt = "2026-10-11T10:00:00Z",
        memberName = "Test member",
        debtId = null,
        ownerUserId = "member-1",
    )

    private companion object {
        const val TENANT_ID = "7ed3e606-0000-4000-8000-000000000001"
        const val TRANSACTION_ID = "7ed3e606-0000-4000-8000-000000000002"
    }
}
