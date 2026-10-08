package com.decorix.finance

/** Selects stale persisted document grants; only an active pending-photo checkpoint owns one. */
internal object ReceiptUriCleanupPolicy {
    fun urisToRelease(
        persistedUris: List<String>,
        checkpoint: ReceiptUploadCheckpoint?,
    ): Set<String> {
        val retainedPhoto = checkpoint
            ?.takeIf(::isActivePendingPhotoCheckpoint)
            ?.photoUri

        return persistedUris.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .filterNot { it == retainedPhoto }
            .toCollection(linkedSetOf())
    }

    private fun isActivePendingPhotoCheckpoint(checkpoint: ReceiptUploadCheckpoint): Boolean =
        checkpoint.tenantId.isNotBlank() && checkpoint.jobId == null &&
            checkpoint.photoUri?.startsWith("content://") == true &&
            checkpoint.idempotencyKey?.let { key ->
                key.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH && key.none(Char::isWhitespace)
            } == true

    private const val MIN_KEY_LENGTH = 16
    private const val MAX_KEY_LENGTH = 128
}
