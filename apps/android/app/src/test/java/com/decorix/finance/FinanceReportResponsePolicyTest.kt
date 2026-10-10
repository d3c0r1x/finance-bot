package com.decorix.finance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceReportResponsePolicyTest {
    @Test fun familyFoodReportAppliesOnlyForCurrentTenantMonthRequestAndSession() {
        assertTrue(FamilyBudgetFoodResponsePolicy.canApply(
            requestTenantId = "tenant-a", activeTenantId = "tenant-a", authenticated = true,
            requestMonth = "2026-10", currentMonth = "2026-10", responsePeriod = "month", responseScope = "family",
            responseMonth = "2026-10",
            requestGeneration = 7L,
            currentRequestGeneration = 7L, sessionCurrent = true,
        ))
        assertTrue(FamilyBudgetFoodResponsePolicy.isCurrentRequest(
            requestTenantId = "tenant-a", activeTenantId = "tenant-a", authenticated = true,
            requestMonth = "2026-10", currentMonth = "2026-10", requestGeneration = 7L,
            currentRequestGeneration = 7L, sessionCurrent = true,
        ))
        assertFalse(FamilyBudgetFoodResponsePolicy.canApply(
            requestTenantId = "tenant-a", activeTenantId = "tenant-a", authenticated = true,
            requestMonth = "2026-09", currentMonth = "2026-10", responsePeriod = "month", responseScope = "family",
            responseMonth = "2026-09",
            requestGeneration = 7L,
            currentRequestGeneration = 7L, sessionCurrent = true,
        ))
        assertFalse(FamilyBudgetFoodResponsePolicy.canApply(
            requestTenantId = "tenant-a", activeTenantId = "tenant-b", authenticated = true,
            requestMonth = "2026-10", currentMonth = "2026-10", responsePeriod = "month", responseScope = "family",
            responseMonth = "2026-10",
            requestGeneration = 7L,
            currentRequestGeneration = 8L, sessionCurrent = true,
        ))
        assertFalse(FamilyBudgetFoodResponsePolicy.canApply(
            requestTenantId = "tenant-a", activeTenantId = "tenant-a", authenticated = true,
            requestMonth = "2026-10", currentMonth = "2026-10", responsePeriod = "month", responseScope = "family",
            responseMonth = "2026-10",
            requestGeneration = 7L,
            currentRequestGeneration = 7L, sessionCurrent = false,
        ))
        assertFalse(FamilyBudgetFoodResponsePolicy.canApply(
            requestTenantId = "tenant-a", activeTenantId = "tenant-a", authenticated = true,
            requestMonth = "2026-10", currentMonth = "2026-10", responsePeriod = "month", responseScope = "personal",
            responseMonth = "2026-10",
            requestGeneration = 7L, currentRequestGeneration = 7L, sessionCurrent = true,
        ))
        assertFalse(FamilyBudgetFoodResponsePolicy.canApply(
            requestTenantId = "tenant-a", activeTenantId = "tenant-a", authenticated = true,
            requestMonth = "2026-10", currentMonth = "2026-10", responsePeriod = "month", responseScope = "family",
            responseMonth = "2026-09", requestGeneration = 7L, currentRequestGeneration = 7L, sessionCurrent = true,
        ))
    }

    @Test fun acceptsCurrentAuthorizedResponseForActiveTenant() {
        assertTrue(
            ReportResponsePolicy.canApply(
                requestTenantId = "tenant-a",
                activeTenantId = "tenant-a",
                authenticated = true,
                currentRequestGeneration = 4L,
                requestGeneration = 4L,
                sessionCurrent = true,
            ),
        )
    }

    @Test fun rejectsResponseForDifferentTenant() {
        assertFalse(
            ReportResponsePolicy.canApply(
                requestTenantId = "tenant-a",
                activeTenantId = "tenant-b",
                authenticated = true,
                requestGeneration = 4L,
                currentRequestGeneration = 4L,
                sessionCurrent = true,
            ),
        )
    }

    @Test fun rejectsResponseAfterLogout() {
        assertFalse(
            ReportResponsePolicy.canApply(
                requestTenantId = "tenant-a",
                activeTenantId = "tenant-a",
                authenticated = false,
                requestGeneration = 4L,
                currentRequestGeneration = 4L,
                sessionCurrent = true,
            ),
        )
    }

    @Test fun rejectsResponseFromChangedSession() {
        assertFalse(
            ReportResponsePolicy.canApply(
                requestTenantId = "tenant-a",
                activeTenantId = "tenant-a",
                authenticated = true,
                requestGeneration = 4L,
                currentRequestGeneration = 4L,
                sessionCurrent = false,
            ),
        )
    }

    @Test fun rejectsSupersededReportRequest() {
        assertFalse(
            ReportResponsePolicy.canApply(
                requestTenantId = "tenant-a",
                activeTenantId = "tenant-a",
                authenticated = true,
                requestGeneration = 4L,
                currentRequestGeneration = 5L,
                sessionCurrent = true,
            ),
        )
    }
}
