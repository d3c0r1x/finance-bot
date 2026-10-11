package com.decorix.finance

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankImportCheckpointStoreTest {
    @Test fun keepsLatestImportPerTenantWithoutCrossTenantLeakage() {
        val preferences = InMemorySharedPreferences()
        val store = BankImportCheckpointStore(preferences)

        assertTrue(store.save(TENANT_A, IMPORT_A_1))
        assertTrue(store.save(TENANT_B, IMPORT_B_1))
        assertTrue(store.save(TENANT_A, IMPORT_A_2))

        assertEquals(IMPORT_A_2, store.load(TENANT_A))
        assertEquals(IMPORT_B_1, store.load(TENANT_B))
    }

    @Test fun latestImportSurvivesCreationOfAnotherStoreInstance() {
        val preferences = InMemorySharedPreferences()
        assertTrue(BankImportCheckpointStore(preferences).save(TENANT_A, IMPORT_A_1))

        assertEquals(IMPORT_A_1, BankImportCheckpointStore(preferences).load(TENANT_A))
    }

    @Test fun rejectsInvalidTenantAndImportIdsWithoutPersistingInput() {
        val preferences = InMemorySharedPreferences()
        val store = BankImportCheckpointStore(preferences)

        assertFalse(store.save("content://private/tenant", IMPORT_A_1))
        assertFalse(store.save(TENANT_A, "content://private/statement.pdf"))
        assertFalse(store.save(TENANT_A, "not-a-uuid"))
        assertNull(store.load("content://private/tenant"))
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun persistsOnlyTenantAndImportUuidsNeverPdfUriOrBytes() {
        val preferences = InMemorySharedPreferences()
        assertTrue(BankImportCheckpointStore(preferences).save(TENANT_A, IMPORT_A_1))

        assertEquals(setOf("tenant_import_$TENANT_A"), preferences.all.keys)
        assertEquals(setOf(IMPORT_A_1), preferences.all.values.toSet())
        assertTrue(preferences.all.values.all { it is String && isUuid(it) })
        assertFalse(preferences.all.values.any { it.toString().contains("content://") })
        assertFalse(preferences.all.values.any { it is ByteArray })
    }

    private fun isUuid(value: Any?): Boolean =
        value is String && runCatching { java.util.UUID.fromString(value) }.isSuccess

    private companion object {
        const val TENANT_A = "10000000-0000-4000-8000-000000000001"
        const val TENANT_B = "10000000-0000-4000-8000-000000000002"
        const val IMPORT_A_1 = "20000000-0000-4000-8000-000000000001"
        const val IMPORT_A_2 = "20000000-0000-4000-8000-000000000002"
        const val IMPORT_B_1 = "20000000-0000-4000-8000-000000000003"
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = MemoryEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class MemoryEditor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            put(key, values?.toSet())
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String): SharedPreferences.Editor = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
        override fun commit(): Boolean = applyChanges()
        override fun apply() { applyChanges() }

        private fun put(key: String, value: Any?): SharedPreferences.Editor = apply { updates[key] = value }
        private fun applyChanges(): Boolean {
            if (clearRequested) values.clear()
            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
    }
}
