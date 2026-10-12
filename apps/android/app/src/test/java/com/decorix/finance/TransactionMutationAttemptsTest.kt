package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionMutationAttemptsTest {
    @Test fun repeatKeepsRequestIdentityAndPayloadAfterRepositoryRecreation() {
        val storage = InMemoryMutationCheckpointStorage()
        val firstRepository = TransactionMutationAttempts(storage)
        val source = transaction()
        val first = firstRepository.beginRepeat(
            ACTOR_A, TENANT_A, source, FIRST_OCCURRED_AT, SESSION_ONE,
        )

        val recreatedRepository = TransactionMutationAttempts(storage)
        val recovered = recreatedRepository.beginRepeat(
            ACTOR_A, TENANT_A, source.copy(description = "Changed after request"),
            SECOND_OCCURRED_AT, SESSION_ONE,
        )

        assertEquals(first.idempotencyKey, recovered.idempotencyKey)
        assertEquals(first.original, recovered.original)
        assertEquals(first.occurredAt, recovered.occurredAt)
        assertEquals(FIRST_OCCURRED_AT, recovered.occurredAt)
        assertEquals(listOf(first), recreatedRepository.pendingFor(ACTOR_A, TENANT_A))
    }

    @Test fun voidKeepsSourceVersionAndKeyAfterRepositoryRecreation() {
        val storage = InMemoryMutationCheckpointStorage()
        val source = transaction()
        val first = TransactionMutationAttempts(storage).beginVoid(
            ACTOR_A, TENANT_A, source, SESSION_ONE,
        )

        val recovered = TransactionMutationAttempts(storage).beginVoid(
            ACTOR_A, TENANT_A, source.copy(memberName = "Renamed member"), SESSION_TWO,
        )

        assertEquals(MutationAction.VOID, recovered.action)
        assertEquals(first.idempotencyKey, recovered.idempotencyKey)
        assertEquals(source, recovered.original)
        assertEquals(source.version, recovered.original.version)
        assertEquals(null, recovered.occurredAt)
    }

    @Test fun actorTenantActionAndSourceVersionHaveIndependentCheckpoints() {
        val repository = TransactionMutationAttempts(InMemoryMutationCheckpointStorage())
        val source = transaction()
        val repeat = repository.beginRepeat(ACTOR_A, TENANT_A, source, FIRST_OCCURRED_AT, SESSION_ONE)
        val otherActor = repository.beginRepeat(ACTOR_B, TENANT_A, source, FIRST_OCCURRED_AT, SESSION_ONE)
        val otherTenant = repository.beginRepeat(ACTOR_A, TENANT_B, source.copy(tenantId = TENANT_B), FIRST_OCCURRED_AT, SESSION_ONE)
        val changedVersion = repository.beginRepeat(
            ACTOR_A, TENANT_A, source.copy(version = source.version + 1), FIRST_OCCURRED_AT, SESSION_ONE,
        )
        val void = repository.beginVoid(ACTOR_A, TENANT_A, source, SESSION_ONE)

        assertNotEquals(repeat.idempotencyKey, otherActor.idempotencyKey)
        assertNotEquals(repeat.idempotencyKey, otherTenant.idempotencyKey)
        assertNotEquals(repeat.idempotencyKey, changedVersion.idempotencyKey)
        assertNotEquals(repeat.idempotencyKey, void.idempotencyKey)
        assertEquals(setOf(repeat, changedVersion, void), repository.pendingFor(ACTOR_A, TENANT_A).toSet())
        assertEquals(setOf(otherActor), repository.pendingFor(ACTOR_B, TENANT_A).toSet())
        assertEquals(setOf(otherTenant), repository.pendingFor(ACTOR_A, TENANT_B).toSet())
    }

    @Test fun completionRemovesOnlyTheExactRepeatActorTenantTransactionAndKey() {
        val repository = TransactionMutationAttempts(InMemoryMutationCheckpointStorage())
        val source = transaction()
        val completed = repository.beginRepeat(ACTOR_A, TENANT_A, source, FIRST_OCCURRED_AT, SESSION_ONE)
        val sameOperationOtherActor = repository.beginRepeat(ACTOR_B, TENANT_A, source, FIRST_OCCURRED_AT, SESSION_ONE)
        val sameOperationOtherTenant = repository.beginRepeat(
            ACTOR_A, TENANT_B, source.copy(tenantId = TENANT_B), FIRST_OCCURRED_AT, SESSION_ONE,
        )
        val void = repository.beginVoid(ACTOR_A, TENANT_A, source, SESSION_ONE)

        repository.completeRepeat(ACTOR_A, TENANT_A, source.id, "wrong-key")
        repository.completeRepeat(ACTOR_B, TENANT_A, source.id, sameOperationOtherActor.idempotencyKey)
        assertTrue(completed in repository.pendingFor(ACTOR_A, TENANT_A))
        assertTrue(sameOperationOtherTenant in repository.pendingFor(ACTOR_A, TENANT_B))

        repository.completeRepeat(ACTOR_A, TENANT_A, source.id, completed.idempotencyKey)

        assertTrue(completed !in repository.pendingFor(ACTOR_A, TENANT_A))
        assertTrue(void in repository.pendingFor(ACTOR_A, TENANT_A))
        assertTrue(sameOperationOtherActor !in repository.pendingFor(ACTOR_B, TENANT_A))
    }

    @Test fun completionRequiresMatchingVoidSourceVersionAndKey() {
        val repository = TransactionMutationAttempts(InMemoryMutationCheckpointStorage())
        val source = transaction()
        val oldVersion = repository.beginVoid(ACTOR_A, TENANT_A, source, SESSION_ONE)
        val newVersion = repository.beginVoid(
            ACTOR_A, TENANT_A, source.copy(version = source.version + 1), SESSION_ONE,
        )

        repository.completeVoid(ACTOR_A, TENANT_A, source.id, source.version + 1, oldVersion.idempotencyKey)
        repository.completeVoid(ACTOR_A, TENANT_A, source.id, source.version, "wrong-key")
        assertTrue(oldVersion in repository.pendingFor(ACTOR_A, TENANT_A))

        repository.completeVoid(ACTOR_A, TENANT_A, source.id, source.version, oldVersion.idempotencyKey)

        assertTrue(oldVersion !in repository.pendingFor(ACTOR_A, TENANT_A))
        assertTrue(newVersion in repository.pendingFor(ACTOR_A, TENANT_A))
    }

    @Test fun rejectsBlankActorAndTenantOrTransactionFromAnotherTenant() {
        val repository = TransactionMutationAttempts(InMemoryMutationCheckpointStorage())

        assertIllegalArgument { repository.beginRepeat("", TENANT_A, transaction(), FIRST_OCCURRED_AT, SESSION_ONE) }
        assertIllegalArgument { repository.beginRepeat(ACTOR_A, "", transaction(), FIRST_OCCURRED_AT, SESSION_ONE) }
        assertIllegalArgument {
            repository.beginRepeat(ACTOR_A, TENANT_B, transaction(), FIRST_OCCURRED_AT, SESSION_ONE)
        }
        assertIllegalArgument { repository.beginVoid(ACTOR_A, TENANT_A, transaction(status = "voided"), SESSION_ONE) }
        assertIllegalArgument { repository.beginVoid(ACTOR_A, TENANT_A, transaction(version = 0), SESSION_ONE) }
    }

    @Test fun recoveryListIncludesPendingRequestsOutsideCurrentPageOnly() {
        val repository = TransactionMutationAttempts(InMemoryMutationCheckpointStorage())
        val source = transaction()
        val repeat = repository.beginRepeat(ACTOR_A, TENANT_A, source, FIRST_OCCURRED_AT, SESSION_ONE)
        val another = repository.beginVoid(
            ACTOR_A, TENANT_A, source.copy(id = OTHER_TRANSACTION_ID), SESSION_ONE,
        )
        val visible = listOf(source.copy(tenantId = TENANT_A))

        assertEquals(listOf(another), mutationCheckpointsOutsideVisibleTransactions(
            listOf(repeat, another), visible,
        ))
        assertEquals(listOf(repeat, another), mutationCheckpointsOutsideVisibleTransactions(
            listOf(repeat, another), emptyList(),
        ))
    }

    @Test fun failedMutationPublishesScopedCheckpointAndFailsClosedOnStorageError() {
        val source = transaction()
        val repository = TransactionMutationAttempts(InMemoryMutationCheckpointStorage())
        val pending = repository.beginRepeat(ACTOR_A, TENANT_A, source, FIRST_OCCURRED_AT, SESSION_ONE)
        val state = FinanceUiState(tenants = listOf(tenant(ACTOR_A, TENANT_A)))

        val recovered = exposeMutationRecoveryAfterFailure(state, repository, ACTOR_A, TENANT_A)
        assertEquals(listOf(pending), recovered.pendingMutationCheckpoints)
        assertEquals(false, recovered.mutationCheckpointError)

        val damagedRepository = TransactionMutationAttempts(object : MutationCheckpointStorage {
            override fun read(): List<MutationCheckpoint> = error("synthetic damaged encrypted store")
            override fun write(checkpoints: List<MutationCheckpoint>) = error("write must not be reached")
        })
        val failedClosed = exposeMutationRecoveryAfterFailure(state, damagedRepository, ACTOR_A, TENANT_A)
        assertTrue(failedClosed.mutationCheckpointError)
        assertTrue(failedClosed.pendingMutationCheckpoints.isEmpty())
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid mutation checkpoint scope to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: invalid owner/tenant or non-mutable transaction is rejected.
        }
    }

    private fun transaction(
        tenantId: String = TENANT_A,
        version: Long = 4L,
        status: String = "posted",
    ) = FinanceTransaction(
        id = TRANSACTION_ID,
        tenantId = tenantId,
        type = "expense",
        amount = "247.35",
        currency = "RUB",
        categoryCode = "transport",
        subcategoryCode = "taxi",
        description = "Synthetic taxi",
        source = "manual",
        occurredAt = "2026-10-11T10:00:00Z",
        accountId = "account-1",
        status = status,
        version = version,
        createdAt = "2026-10-11T10:00:00Z",
        memberName = "Synthetic member",
        debtId = null,
        ownerUserId = ACTOR_B,
    )

    private fun tenant(actor: String, id: String) = FinanceTenant(
        id = id, name = "Synthetic tenant", role = "owner", timezone = "UTC", userId = actor,
    )

    private class InMemoryMutationCheckpointStorage : MutationCheckpointStorage {
        private var checkpoints: List<MutationCheckpoint> = emptyList()

        override fun read(): List<MutationCheckpoint> = checkpoints.toList()

        override fun write(checkpoints: List<MutationCheckpoint>) {
            this.checkpoints = checkpoints.toList()
        }
    }

    private companion object {
        const val ACTOR_A = "30000000-0000-4000-8000-000000000001"
        const val ACTOR_B = "30000000-0000-4000-8000-000000000002"
        const val TENANT_A = "10000000-0000-4000-8000-000000000001"
        const val TENANT_B = "10000000-0000-4000-8000-000000000002"
        const val TRANSACTION_ID = "20000000-0000-4000-8000-000000000001"
        const val OTHER_TRANSACTION_ID = "20000000-0000-4000-8000-000000000003"
        const val FIRST_OCCURRED_AT = "2026-10-12T09:15:30+03:00"
        const val SECOND_OCCURRED_AT = "2026-10-13T09:15:30+03:00"
        const val SESSION_ONE = 41L
        const val SESSION_TWO = 42L
    }
}
