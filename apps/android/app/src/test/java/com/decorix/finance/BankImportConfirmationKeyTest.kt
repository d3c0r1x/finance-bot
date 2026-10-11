package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BankImportConfirmationKeyTest {
    @Test fun keyIsStableForAnImportAndWithinCoreLengthLimit() {
        val importId = "00000000-0000-4000-8000-000000000048"

        val first = BankImportConfirmationKey.forImport(importId)
        val retry = BankImportConfirmationKey.forImport(importId)

        assertEquals("android-bank-import-confirm-$importId", first)
        assertEquals(first, retry)
        assertTrue(first.length in 16..128)
    }

    @Test fun differentImportIdsCannotShareAConfirmationKey() {
        val first = BankImportConfirmationKey.forImport("00000000-0000-4000-8000-000000000048")
        val second = BankImportConfirmationKey.forImport("00000000-0000-4000-8000-000000000049")

        assertNotEquals(first, second)
    }
}
