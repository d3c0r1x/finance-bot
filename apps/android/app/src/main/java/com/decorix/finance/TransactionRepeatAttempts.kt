package com.decorix.finance

import java.util.UUID

internal data class TransactionRepeatAttempt(
    val tenantId: String,
    val sessionGeneration: Long,
    val original: FinanceTransaction,
    val occurredAt: String,
    val idempotencyKey: String,
)

internal object TransactionRepeatAttempts {
    private data class AttemptKey(val tenantId: String, val originalId: String, val sessionGeneration: Long)

    private val attempts = mutableMapOf<AttemptKey, TransactionRepeatAttempt>()

    @Synchronized
    fun begin(tenantId: String, original: FinanceTransaction, occurredAt: String,
              authSessionGeneration: Long = 0L): TransactionRepeatAttempt {
        require(tenantId.isNotBlank()) { "Transaction repeat tenant is required" }
        require(original.tenantId == tenantId) { "Transaction does not belong to tenant" }
        return attempts.getOrPut(AttemptKey(tenantId, original.id, authSessionGeneration)) {
            TransactionRepeatAttempt(
                tenantId = tenantId,
                sessionGeneration = authSessionGeneration,
                original = original.copy(),
                occurredAt = occurredAt,
                idempotencyKey = UUID.randomUUID().toString(),
            )
        }
    }

    @Synchronized
    fun complete(tenantId: String, originalId: String, authSessionGeneration: Long = 0L) {
        attempts.remove(AttemptKey(tenantId, originalId, authSessionGeneration))
    }
}
