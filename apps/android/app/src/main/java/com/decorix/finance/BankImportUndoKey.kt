package com.decorix.finance

import java.util.UUID

internal object BankImportUndoKey {
    fun forImport(importId: String): String {
        val canonicalId = canonicalUuid(importId)
            ?: throw IllegalArgumentException("Invalid bank import identifier")
        return "android-bank-import-undo-$canonicalId"
    }

    private fun canonicalUuid(value: String): String? = runCatching {
        UUID.fromString(value).toString().takeIf { it.equals(value, ignoreCase = true) }
    }.getOrNull()
}
