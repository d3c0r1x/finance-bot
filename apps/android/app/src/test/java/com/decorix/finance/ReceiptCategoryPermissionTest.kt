package com.decorix.finance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptCategoryPermissionTest {
    @Test fun ownerAdminAndMemberCanWriteReceiptCategory() {
        listOf("owner", "admin", "member").forEach { role ->
            assertTrue("$role must be allowed to select receipt category", canWriteReceiptCategory(role))
        }
    }

    @Test fun viewerAndMissingRoleCannotWriteReceiptCategory() {
        listOf("viewer", null).forEach { role ->
            assertFalse("$role must not be allowed to select receipt category", canWriteReceiptCategory(role))
        }
    }
}
