package com.decorix.finance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TransactionVoidAttemptsTest {
    @After fun clearAttempts() {
        TransactionVoidAttempts.complete(TENANT_ID, TRANSACTION_ID, SOURCE_VERSION, SESSION_ONE)
        TransactionVoidAttempts.complete(OTHER_TENANT_ID, TRANSACTION_ID, SOURCE_VERSION, SESSION_ONE)
        TransactionVoidAttempts.complete(TENANT_ID, OTHER_TRANSACTION_ID, SOURCE_VERSION, SESSION_ONE)
        TransactionVoidAttempts.complete(TENANT_ID, TRANSACTION_ID, SOURCE_VERSION + 1, SESSION_ONE)
        TransactionVoidAttempts.complete(TENANT_ID, TRANSACTION_ID, SOURCE_VERSION, SESSION_TWO)
    }

    @Test fun sameTenantTransactionVersionAndSessionReuseSameImmutableIntentUntilComplete() {
        val first = TransactionVoidAttempts.begin(TENANT_ID, source(), SESSION_ONE)
        val retry = TransactionVoidAttempts.begin(TENANT_ID, source(), SESSION_ONE)

        assertEquals(TENANT_ID, first.tenantId)
        assertEquals(source(), first.original)
        assertEquals(SESSION_ONE, first.sessionGeneration)
        assertEquals(first, retry)
        assertEquals(first.idempotencyKey, retry.idempotencyKey)

        TransactionVoidAttempts.complete(TENANT_ID, TRANSACTION_ID, SOURCE_VERSION, SESSION_ONE)
        val nextIntent = TransactionVoidAttempts.begin(TENANT_ID, source(), SESSION_ONE)

        assertNotEquals(first.idempotencyKey, nextIntent.idempotencyKey)
    }

    @Test fun tenantTransactionVersionAndSessionEachIsolatePendingIntent() {
        val original = source()
        val base = TransactionVoidAttempts.begin(TENANT_ID, original, SESSION_ONE)
        val otherTenant = TransactionVoidAttempts.begin(OTHER_TENANT_ID, source(tenantId = OTHER_TENANT_ID), SESSION_ONE)
        val otherTransaction = TransactionVoidAttempts.begin(
            TENANT_ID, original.copy(id = OTHER_TRANSACTION_ID), SESSION_ONE,
        )
        val otherVersion = TransactionVoidAttempts.begin(
            TENANT_ID, original.copy(version = SOURCE_VERSION + 1), SESSION_ONE,
        )
        val otherSession = TransactionVoidAttempts.begin(TENANT_ID, original, SESSION_TWO)

        assertNotEquals(base.idempotencyKey, otherTenant.idempotencyKey)
        assertNotEquals(base.idempotencyKey, otherTransaction.idempotencyKey)
        assertNotEquals(base.idempotencyKey, otherVersion.idempotencyKey)
        assertNotEquals(base.idempotencyKey, otherSession.idempotencyKey)
        assertEquals(OTHER_TENANT_ID, otherTenant.tenantId)
        assertEquals(OTHER_TRANSACTION_ID, otherTransaction.original.id)
        assertEquals(SOURCE_VERSION + 1, otherVersion.original.version)
        assertEquals(SESSION_TWO, otherSession.sessionGeneration)
    }

    @Test fun pendingIntentSurvivesUnconfirmedResponseAndOnlyCompleteClearsIt() {
        val first = TransactionVoidAttempts.begin(TENANT_ID, source(), SESSION_ONE)
        // Re-entering after any unconfirmed result must retrieve the same intent.
        val afterInvalidAcknowledgement = TransactionVoidAttempts.begin(TENANT_ID, source(), SESSION_ONE)

        assertEquals(first, afterInvalidAcknowledgement)
        assertEquals(first.idempotencyKey, afterInvalidAcknowledgement.idempotencyKey)

        TransactionVoidAttempts.complete(TENANT_ID, TRANSACTION_ID, SOURCE_VERSION, SESSION_ONE)
        val afterConfirmedCompletion = TransactionVoidAttempts.begin(TENANT_ID, source(), SESSION_ONE)
        assertNotEquals(first.idempotencyKey, afterConfirmedCompletion.idempotencyKey)
    }

    @Test fun capturedRequestContextRejectsReplacementTenantOrAuthenticationSession() {
        val captured = FinanceVoidRequestContext(TENANT_ID, SESSION_ONE)

        assertEquals(true, captured.matchesCurrent(TENANT_ID, SESSION_ONE))
        assertEquals(false, captured.matchesCurrent(OTHER_TENANT_ID, SESSION_ONE))
        assertEquals(false, captured.matchesCurrent(TENANT_ID, SESSION_TWO))
    }

    private fun source(tenantId: String = TENANT_ID) = FinanceTransaction(
        id = TRANSACTION_ID,
        tenantId = tenantId,
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
        version = SOURCE_VERSION,
        createdAt = "2026-10-11T10:00:00Z",
        memberName = "Test member",
        debtId = null,
        ownerUserId = "member-1",
    )

    private companion object {
        const val TENANT_ID = "7ed3e606-0000-4000-8000-000000000001"
        const val OTHER_TENANT_ID = "7ed3e606-0000-4000-8000-000000000004"
        const val TRANSACTION_ID = "7ed3e606-0000-4000-8000-000000000002"
        const val OTHER_TRANSACTION_ID = "7ed3e606-0000-4000-8000-000000000005"
        const val SOURCE_VERSION = 4L
        const val SESSION_ONE = 41L
        const val SESSION_TWO = 42L
    }
}
