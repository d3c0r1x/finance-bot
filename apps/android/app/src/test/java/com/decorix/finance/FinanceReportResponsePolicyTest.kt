package com.decorix.finance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceReportResponsePolicyTest {
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
