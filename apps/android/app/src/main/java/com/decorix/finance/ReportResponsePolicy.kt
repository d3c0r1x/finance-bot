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

internal object FamilyBudgetFoodResponsePolicy {
    fun isCurrentRequest(
        requestTenantId: String,
        activeTenantId: String?,
        authenticated: Boolean,
        requestMonth: String,
        currentMonth: String?,
        requestGeneration: Long,
        currentRequestGeneration: Long,
        sessionCurrent: Boolean,
    ): Boolean = authenticated && sessionCurrent && requestTenantId == activeTenantId &&
        requestMonth == currentMonth && requestGeneration == currentRequestGeneration

    fun canApply(
        requestTenantId: String,
        activeTenantId: String?,
        authenticated: Boolean,
        requestMonth: String,
        currentMonth: String?,
        responsePeriod: String,
        responseScope: String,
        responseMonth: String,
        requestGeneration: Long,
        currentRequestGeneration: Long,
        sessionCurrent: Boolean,
    ): Boolean = isCurrentRequest(requestTenantId, activeTenantId, authenticated, requestMonth, currentMonth,
        requestGeneration, currentRequestGeneration, sessionCurrent) &&
        responsePeriod == "month" && responseScope == "family" && responseMonth == requestMonth
}
