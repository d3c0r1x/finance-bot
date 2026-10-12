package com.decorix.finance

import java.util.UUID

/** Durable, owner-scoped request identity for mutations whose HTTP result may be lost. */
internal class TransactionMutationAttempts(private val storage: MutationCheckpointStorage) {
    @Synchronized
    fun beginRepeat(
        actorUserId: String,
        tenantId: String,
        original: FinanceTransaction,
        occurredAt: String,
        sessionGeneration: Long,
    ): MutationCheckpoint {
        validateScope(actorUserId, tenantId, original)
        require(original.status == "posted" && original.type != "debt_payment") {
            "Transaction cannot be repeated"
        }
        require(occurredAt.isNotBlank()) { "Repeated mutation timestamp is required" }
        val existing = storage.read().firstOrNull {
            it.action == MutationAction.REPEAT && it.actorUserId == actorUserId && it.tenantId == tenantId &&
                it.original.id == original.id && it.original.version == original.version
        }
        if (existing != null) return existing.copy(sessionGeneration = sessionGeneration)

        return MutationCheckpoint(
            action = MutationAction.REPEAT,
            actorUserId = actorUserId,
            tenantId = tenantId,
            original = original.copy(),
            occurredAt = occurredAt,
            idempotencyKey = UUID.randomUUID().toString(),
            sessionGeneration = sessionGeneration,
        ).also { storage.write(storage.read() + it) }
    }

    @Synchronized
    fun beginVoid(
        actorUserId: String,
        tenantId: String,
        original: FinanceTransaction,
        sessionGeneration: Long,
    ): MutationCheckpoint {
        validateScope(actorUserId, tenantId, original)
        require(original.status == "posted" && original.version > 0) { "Transaction cannot be voided" }
        val existing = storage.read().firstOrNull {
            it.action == MutationAction.VOID && it.actorUserId == actorUserId && it.tenantId == tenantId &&
                it.original.id == original.id && it.original.version == original.version
        }
        if (existing != null) return existing.copy(sessionGeneration = sessionGeneration)

        return MutationCheckpoint(
            action = MutationAction.VOID,
            actorUserId = actorUserId,
            tenantId = tenantId,
            original = original.copy(),
            occurredAt = null,
            idempotencyKey = UUID.randomUUID().toString(),
            sessionGeneration = sessionGeneration,
        ).also { storage.write(storage.read() + it) }
    }

    @Synchronized
    fun pendingFor(actorUserId: String, tenantId: String): List<MutationCheckpoint> {
        if (actorUserId.isBlank() || tenantId.isBlank()) return emptyList()
        return storage.read().filter { it.actorUserId == actorUserId && it.tenantId == tenantId }
    }

    @Synchronized
    fun completeRepeat(actorUserId: String, tenantId: String, transactionId: String, idempotencyKey: String) {
        removeMatching { it.action == MutationAction.REPEAT && it.actorUserId == actorUserId &&
            it.tenantId == tenantId && it.original.id == transactionId && it.idempotencyKey == idempotencyKey }
    }

    @Synchronized
    fun completeVoid(
        actorUserId: String,
        tenantId: String,
        transactionId: String,
        sourceVersion: Long,
        idempotencyKey: String,
    ) {
        removeMatching { it.action == MutationAction.VOID && it.actorUserId == actorUserId &&
            it.tenantId == tenantId && it.original.id == transactionId &&
            it.original.version == sourceVersion && it.idempotencyKey == idempotencyKey }
    }

    private fun removeMatching(predicate: (MutationCheckpoint) -> Boolean) {
        val current = storage.read()
        val remaining = current.filterNot(predicate)
        if (remaining.size != current.size) storage.write(remaining)
    }

    private fun validateScope(actorUserId: String, tenantId: String, original: FinanceTransaction) {
        require(actorUserId.isNotBlank()) { "Mutation actor is required" }
        require(tenantId.isNotBlank()) { "Mutation tenant is required" }
        require(original.tenantId == tenantId) { "Transaction does not belong to tenant" }
        require(original.id.isNotBlank() && original.version > 0) { "Transaction source is invalid" }
    }
}

internal fun mutationCheckpointsOutsideVisibleTransactions(
    checkpoints: List<MutationCheckpoint>,
    visibleTransactions: List<FinanceTransaction>,
): List<MutationCheckpoint> {
    val visibleTenantTransactions = visibleTransactions.map { it.tenantId to it.id }.toSet()
    return checkpoints.filterNot { (it.tenantId to it.original.id) in visibleTenantTransactions }
}

internal fun exposeMutationRecoveryAfterFailure(
    state: FinanceUiState,
    attempts: TransactionMutationAttempts,
    actorUserId: String,
    tenantId: String,
): FinanceUiState {
    if (state.tenants.firstOrNull()?.let { it.userId == actorUserId && it.id == tenantId } != true) return state
    return runCatching { attempts.pendingFor(actorUserId, tenantId) }
        .fold(
            onSuccess = { state.copy(pendingMutationCheckpoints = it, mutationCheckpointError = false) },
            onFailure = { state.copy(pendingMutationCheckpoints = emptyList(), mutationCheckpointError = true) },
        )
}
