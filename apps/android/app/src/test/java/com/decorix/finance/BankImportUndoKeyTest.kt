package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BankImportUndoKeyTest {
    @Test fun keyIsStablePerImportAndFitsCoreLengthLimit() {
        val first = BankImportUndoKey.forImport(IMPORT_ID)

        assertEquals(first, BankImportUndoKey.forImport(IMPORT_ID))
        assertTrue(first.length in 16..128)
    }

    @Test fun differentImportsHaveDifferentUndoKeys() {
        assertNotEquals(
            BankImportUndoKey.forImport(IMPORT_ID),
            BankImportUndoKey.forImport(OTHER_IMPORT_ID),
        )
    }

    @Test fun rejectsNonUuidImportIds() {
        assertThrows(IllegalArgumentException::class.java) {
            BankImportUndoKey.forImport("content://private/statement.pdf")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BankImportUndoKey.forImport("not-a-uuid")
        }
    }

    private companion object {
        const val IMPORT_ID = "00000000-0000-4000-8000-000000000049"
        const val OTHER_IMPORT_ID = "00000000-0000-4000-8000-000000000050"
    }
}
