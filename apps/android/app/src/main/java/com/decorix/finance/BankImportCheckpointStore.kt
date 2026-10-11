package com.decorix.finance

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** Stores only the latest Core import UUID for each tenant. */
internal class BankImportCheckpointStore(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    fun save(tenantId: String, importId: String): Boolean {
        val tenant = canonicalUuid(tenantId) ?: return false
        val import = canonicalUuid(importId) ?: return false
        return runCatching {
            preferences.edit().putString(keyForTenant(tenant), import).commit()
        }.getOrDefault(false)
    }

    fun load(tenantId: String): String? {
        val tenant = canonicalUuid(tenantId) ?: return null
        val key = keyForTenant(tenant)
        val saved = runCatching { preferences.getString(key, null) }.getOrNull()
        val import = saved?.let(::canonicalUuid)
        if (saved != null && import == null) {
            runCatching { preferences.edit().remove(key).commit() }
        }
        return import
    }

    private fun keyForTenant(tenantId: String): String = "$TENANT_KEY_PREFIX$tenantId"

    private companion object {
        const val PREFERENCES_NAME = "bank_import_checkpoint"
        const val TENANT_KEY_PREFIX = "tenant_import_"
    }
}

private fun canonicalUuid(value: String): String? = runCatching {
    UUID.fromString(value).toString().takeIf { it.equals(value, ignoreCase = true) }
}.getOrNull()
