package com.decorix.finance

import java.util.UUID

internal data class FinanceVoidRequestContext(
    val tenantId: String,
    val sessionGeneration: Long,
) {
    fun matchesCurrent(currentTenantId: String, currentSessionGeneration: Long): Boolean =
        tenantId == currentTenantId && sessionGeneration == currentSessionGeneration
}

internal data class TransactionVoidAttempt(
    val tenantId: String,
    val sessionGeneration: Long,
    val original: FinanceTransaction,
    val requestBody: String,
    val idempotencyKey: String,
) {
    val sourceVersion: Long get() = original.version
}

internal object TransactionVoidAttempts {
    private data class AttemptKey(
        val tenantId: String,
        val transactionId: String,
        val sourceVersion: Long,
        val sessionGeneration: Long,
    )

    private val attempts = mutableMapOf<AttemptKey, TransactionVoidAttempt>()

    @Synchronized
    fun begin(tenantId: String, original: FinanceTransaction,
              authSessionGeneration: Long): TransactionVoidAttempt {
        require(tenantId.isNotBlank()) { "Transaction void tenant is required" }
        require(original.tenantId == tenantId) { "Transaction does not belong to tenant" }
        require(original.status == "posted" && original.version > 0) { "Transaction cannot be voided" }
        val key = AttemptKey(tenantId, original.id, original.version, authSessionGeneration)
        return attempts.getOrPut(key) {
            TransactionVoidAttempt(
                tenantId = tenantId,
                sessionGeneration = authSessionGeneration,
                original = original.copy(),
                requestBody = "{}",
                idempotencyKey = UUID.randomUUID().toString(),
            )
        }
    }

    @Synchronized
    fun complete(tenantId: String, transactionId: String, sourceVersion: Long,
                 authSessionGeneration: Long) {
        attempts.remove(AttemptKey(tenantId, transactionId, sourceVersion, authSessionGeneration))
    }
}
