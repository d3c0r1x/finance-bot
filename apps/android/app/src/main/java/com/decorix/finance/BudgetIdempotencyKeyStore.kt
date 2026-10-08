package com.decorix.finance

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

internal class BudgetIdempotencyKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun keyFor(operationId: String): String {
        require(operationId.isNotBlank()) { "Operation identity is required" }
        val storageKey = STORAGE_KEY_PREFIX + sha256(operationId)
        preferences.getString(storageKey, null)?.let { existing ->
            if (existing.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH && existing.none(Char::isWhitespace)) {
                return existing
            }
        }

        val key = UUID.randomUUID().toString()
        check(preferences.edit().putString(storageKey, key).commit()) {
            "Could not persist budget idempotency key"
        }
        return key
    }

    fun clear(operationId: String): Boolean {
        require(operationId.isNotBlank()) { "Operation identity is required" }
        return preferences.edit().remove(STORAGE_KEY_PREFIX + sha256(operationId)).commit()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val PREFERENCES_NAME = "budget_idempotency_keys"
        const val STORAGE_KEY_PREFIX = "operation_"
        const val MIN_KEY_LENGTH = 16
        const val MAX_KEY_LENGTH = 128
    }
}
