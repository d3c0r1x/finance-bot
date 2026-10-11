package com.decorix.finance

import java.util.concurrent.atomic.AtomicLong

internal object ShoppingResponsePolicy {
    private val lock = Any()

    fun beginRequest(generation: AtomicLong): Long = synchronized(lock) {
        generation.incrementAndGet()
    }

    fun canApply(
        requestTenantId: String,
        activeTenantId: String?,
        authenticated: Boolean,
        requestGeneration: Long,
        currentRequestGeneration: Long,
        sessionCurrent: Boolean,
    ): Boolean = authenticated && sessionCurrent && requestTenantId == activeTenantId &&
        requestGeneration == currentRequestGeneration

    fun applyIfCurrent(
        sessionGeneration: Long,
        requestTenantId: String,
        activeTenantId: () -> String?,
        authenticated: () -> Boolean,
        requestGeneration: Long,
        currentRequestGeneration: () -> Long,
        action: () -> Unit,
    ): Boolean = synchronized(lock) {
        ReceiptOperationGeneration.runIfCurrent(sessionGeneration) {
            if (!canApply(requestTenantId, activeTenantId(), authenticated(), requestGeneration,
                    currentRequestGeneration(), sessionCurrent = true)) {
                false
            } else {
                action()
                true
            }
        } ?: false
    }
}
