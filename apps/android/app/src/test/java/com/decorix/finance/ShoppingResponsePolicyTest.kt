package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class ShoppingResponsePolicyTest {
    @Test fun acceptsResponseForCurrentAuthenticatedTenantAndSession() {
        assertTrue(
            ShoppingResponsePolicy.canApply(
                requestTenantId = "tenant-a",
                activeTenantId = "tenant-a",
                authenticated = true,
                requestGeneration = 4L,
                currentRequestGeneration = 4L,
                sessionCurrent = true,
            ),
        )
    }

    @Test fun rejectsSameTenantResponseAfterLogoutInvalidatesItsSession() {
        val requestSession = ReceiptOperationGeneration.capture()
        assertTrue(ReceiptOperationGeneration.isCurrent(requestSession))

        ReceiptOperationGeneration.invalidate()
        var applied = false

        assertFalse(ShoppingResponsePolicy.applyIfCurrent(
            sessionGeneration = requestSession,
            requestTenantId = "tenant-a",
            activeTenantId = { "tenant-a" },
            authenticated = { true },
            requestGeneration = 7L,
            currentRequestGeneration = { 7L },
            action = { applied = true },
        ))
        assertFalse(applied)
    }

    @Test fun rejectsResponseFromSupersededShoppingRequest() {
        assertFalse(
            ShoppingResponsePolicy.canApply(
                requestTenantId = "tenant-a",
                activeTenantId = "tenant-a",
                authenticated = true,
                requestGeneration = 7L,
                currentRequestGeneration = 8L,
                sessionCurrent = true,
            ),
        )
    }

    @Test fun appliesCurrentShoppingResponseInsideTheSessionGuard() {
        val session = ReceiptOperationGeneration.capture()
        var applications = 0

        assertTrue(ShoppingResponsePolicy.applyIfCurrent(
            sessionGeneration = session,
            requestTenantId = "tenant-a",
            activeTenantId = { "tenant-a" },
            authenticated = { true },
            requestGeneration = 4L,
            currentRequestGeneration = { 4L },
            action = { applications++ },
        ))
        assertEquals(1, applications)
    }

    @Test fun rejectsResponseAfterActiveTenantChanges() {
        val session = ReceiptOperationGeneration.capture()
        var applied = false

        assertFalse(ShoppingResponsePolicy.applyIfCurrent(
            sessionGeneration = session,
            requestTenantId = "tenant-a",
            activeTenantId = { "tenant-b" },
            authenticated = { true },
            requestGeneration = 4L,
            currentRequestGeneration = { 4L },
            action = { applied = true },
        ))
        assertFalse(applied)
    }

    @Test fun newerRequestInvalidatesOlderResponseBeforeItCanApply() {
        val generation = AtomicLong()
        val first = ShoppingResponsePolicy.beginRequest(generation)
        val second = ShoppingResponsePolicy.beginRequest(generation)
        val session = ReceiptOperationGeneration.capture()
        var applied = false

        assertTrue(second > first)
        assertFalse(ShoppingResponsePolicy.applyIfCurrent(
            sessionGeneration = session,
            requestTenantId = "tenant-a",
            activeTenantId = { "tenant-a" },
            authenticated = { true },
            requestGeneration = first,
            currentRequestGeneration = { generation.get() },
            action = { applied = true },
        ))
        assertFalse(applied)
    }
}
