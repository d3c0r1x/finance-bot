package com.decorix.finance

/** Keeps late goal responses from crossing account, tenant, screen or request boundaries. */
internal object GoalResponsePolicy {
    fun canApply(
        authenticated: Boolean,
        requestTenantId: String,
        activeTenantId: String?,
        capturedSessionGeneration: Long,
        currentSessionGeneration: Long,
        requestGeneration: Long,
        currentGeneration: Long,
        screenActive: Boolean,
    ): Boolean = authenticated &&
        requestTenantId == activeTenantId &&
        capturedSessionGeneration == currentSessionGeneration &&
        requestGeneration == currentGeneration &&
        screenActive
}
