package com.decorix.finance

import android.content.Context
import android.net.Uri

internal data class ReceiptUploadCheckpoint(
    val tenantId: String,
    val jobId: String? = null,
    val photoUri: String? = null,
    val idempotencyKey: String? = null,
)

internal class ReceiptUploadCheckpointStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun savePending(tenantId: String, photoUri: String, idempotencyKey: String): Boolean {
        if (!isValidIdentifier(tenantId) || !isContentUri(photoUri) || !isValidIdempotencyKey(idempotencyKey)) {
            return false
        }
        return preferences.edit()
            .clear()
            .putString(KEY_TENANT_ID, tenantId)
            .putString(KEY_PHOTO_URI, photoUri)
            .putString(KEY_IDEMPOTENCY_KEY, idempotencyKey)
            .commit()
    }

    fun saveJob(tenantId: String, jobId: String): Boolean {
        if (!isValidIdentifier(tenantId) || !isValidIdentifier(jobId)) return false
        return preferences.edit()
            .clear()
            .putString(KEY_TENANT_ID, tenantId)
            .putString(KEY_JOB_ID, jobId)
            .commit()
    }

    fun load(): ReceiptUploadCheckpoint? {
        val stored = preferences.all
        val allowedKeys = setOf(KEY_TENANT_ID, KEY_JOB_ID, KEY_PHOTO_URI, KEY_IDEMPOTENCY_KEY)
        val tenantId = readString(KEY_TENANT_ID)
        val jobId = readString(KEY_JOB_ID)
        val photoUri = readString(KEY_PHOTO_URI)
        val idempotencyKey = readString(KEY_IDEMPOTENCY_KEY)
        val validTenantId = tenantId?.takeIf(::isValidIdentifier)
        val validJobId = jobId?.takeIf(::isValidIdentifier)

        val checkpoint = when {
            stored.keys.any { it !in allowedKeys } || stored.values.any { it !is String } -> null
            validTenantId == null -> null
            validJobId != null && photoUri == null && idempotencyKey == null ->
                ReceiptUploadCheckpoint(tenantId = validTenantId, jobId = validJobId)
            jobId == null && photoUri != null && idempotencyKey != null &&
                isContentUri(photoUri) && isValidIdempotencyKey(idempotencyKey) ->
                ReceiptUploadCheckpoint(
                    tenantId = validTenantId,
                    photoUri = photoUri,
                    idempotencyKey = idempotencyKey,
                )
            else -> null
        }

        if (checkpoint == null && stored.isNotEmpty()) clear()
        return checkpoint
    }

    fun clear(): Boolean = preferences.edit().clear().commit()

    private fun isContentUri(value: String): Boolean =
        runCatching { Uri.parse(value).scheme == "content" }.getOrDefault(false)

    private fun readString(key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    private fun isValidIdentifier(value: String?): Boolean =
        !value.isNullOrBlank() && value.length <= MAX_IDENTIFIER_LENGTH && value.none(Char::isWhitespace)

    private fun isValidIdempotencyKey(value: String): Boolean =
        value.length in MIN_IDEMPOTENCY_KEY_LENGTH..MAX_IDEMPOTENCY_KEY_LENGTH &&
            value.none(Char::isWhitespace)

    private companion object {
        const val PREFERENCES_NAME = "receipt_upload_checkpoint"
        const val KEY_TENANT_ID = "tenant_id"
        const val KEY_JOB_ID = "job_id"
        const val KEY_PHOTO_URI = "photo_uri"
        const val KEY_IDEMPOTENCY_KEY = "idempotency_key"
        const val MIN_IDEMPOTENCY_KEY_LENGTH = 16
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 128
        const val MAX_IDENTIFIER_LENGTH = 256
    }
}
