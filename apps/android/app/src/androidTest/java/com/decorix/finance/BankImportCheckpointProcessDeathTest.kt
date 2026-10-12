package com.decorix.finance

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** The host runner force-stops the target process between ordered seed and recovery phases. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BankImportCheckpointProcessDeathTest {
    @Test fun test01SeedSyntheticImportsForTwoTenants() {
        val store = checkpointStore().also { clearFixture() }

        assertEquals(true, store.save(TENANT_A, IMPORT_A))
        assertEquals(true, store.save(TENANT_B, IMPORT_B))
        assertEquals(IMPORT_A, store.load(TENANT_A))
        assertEquals(IMPORT_B, store.load(TENANT_B))
    }

    @Test fun test02RecoverSameImportsAndUndoKeysAfterHostForceStop() {
        try {
            val store = checkpointStore()
            val recoveredA = store.load(TENANT_A)
            val recoveredB = store.load(TENANT_B)

            assertEquals(IMPORT_A, recoveredA)
            assertEquals(IMPORT_B, recoveredB)
            assertNull("Another tenant must not resolve the staged import", store.load(TENANT_OTHER))
            assertEquals(BankImportUndoKey.forImport(IMPORT_A), BankImportUndoKey.forImport(recoveredA!!))
            assertEquals(BankImportUndoKey.forImport(IMPORT_B), BankImportUndoKey.forImport(recoveredB!!))
        } finally {
            clearFixture()
        }
    }

    @Test fun test03CleanupSyntheticTenantPointers() {
        clearFixture()
    }

    private fun checkpointStore(): BankImportCheckpointStore = BankImportCheckpointStore(
        context().getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE),
    )

    private fun clearFixture() {
        check(context().getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit())
    }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val TEST_PREFERENCES = "finance_bank_import_process_death_test"
        const val TENANT_A = "30000000-0000-4000-8000-000000000001"
        const val TENANT_B = "30000000-0000-4000-8000-000000000002"
        const val TENANT_OTHER = "30000000-0000-4000-8000-000000000003"
        const val IMPORT_A = "40000000-0000-4000-8000-000000000001"
        const val IMPORT_B = "40000000-0000-4000-8000-000000000002"
    }
}
