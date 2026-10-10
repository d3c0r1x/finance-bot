package com.decorix.finance

internal object ReportResponsePolicy {
    fun canApply(
        requestTenantId: String,
        activeTenantId: String?,
        authenticated: Boolean,
        requestGeneration: Long,
        currentRequestGeneration: Long,
        sessionCurrent: Boolean,
    ): Boolean = authenticated && sessionCurrent && requestTenantId == activeTenantId &&
        requestGeneration == currentRequestGeneration
}
