package com.decorix.finance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalResponsePolicyTest {
    @Test fun appliesOnlyToCurrentAuthenticatedTenantSessionAndRequest() {
        assertTrue(GoalResponsePolicy.canApply(
            authenticated = true, requestTenantId = TENANT_A, activeTenantId = TENANT_A,
            capturedSessionGeneration = 4, currentSessionGeneration = 4,
            requestGeneration = 9, currentGeneration = 9, screenActive = true,
        ))
        assertFalse(GoalResponsePolicy.canApply(
            authenticated = false, requestTenantId = TENANT_A, activeTenantId = TENANT_A,
            capturedSessionGeneration = 4, currentSessionGeneration = 4,
            requestGeneration = 9, currentGeneration = 9, screenActive = true,
        ))
        assertFalse(GoalResponsePolicy.canApply(
            authenticated = true, requestTenantId = TENANT_A, activeTenantId = TENANT_B,
            capturedSessionGeneration = 4, currentSessionGeneration = 4,
            requestGeneration = 9, currentGeneration = 9, screenActive = true,
        ))
        assertFalse(GoalResponsePolicy.canApply(
            authenticated = true, requestTenantId = TENANT_A, activeTenantId = TENANT_A,
            capturedSessionGeneration = 4, currentSessionGeneration = 5,
            requestGeneration = 9, currentGeneration = 9, screenActive = true,
        ))
        assertFalse(GoalResponsePolicy.canApply(
            authenticated = true, requestTenantId = TENANT_A, activeTenantId = TENANT_A,
            capturedSessionGeneration = 4, currentSessionGeneration = 4,
            requestGeneration = 8, currentGeneration = 9, screenActive = true,
        ))
        assertFalse(GoalResponsePolicy.canApply(
            authenticated = true, requestTenantId = TENANT_A, activeTenantId = TENANT_A,
            capturedSessionGeneration = 4, currentSessionGeneration = 4,
            requestGeneration = 9, currentGeneration = 9, screenActive = false,
        ))
    }

    private companion object {
        const val TENANT_A = "00000000-0000-4000-8000-000000000001"
        const val TENANT_B = "00000000-0000-4000-8000-000000000002"
    }
}
