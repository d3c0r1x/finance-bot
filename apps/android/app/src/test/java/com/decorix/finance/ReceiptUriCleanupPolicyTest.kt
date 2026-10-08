package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptUriCleanupPolicyTest {
    @Test fun lostCheckpointReleasesEveryPersistedUriOnce() {
        val orphanA = "content://synthetic/orphan-a.jpg"
        val orphanB = "content://synthetic/orphan-b.png"

        val toRelease = ReceiptUriCleanupPolicy.urisToRelease(
            persistedUris = listOf(orphanA, orphanA, orphanB),
            checkpoint = null,
        )

        assertEquals(setOf(orphanA, orphanB), toRelease)
    }

    @Test fun activePendingCheckpointRetainsItsPhotoAndReleasesOtherGrantsOnce() {
        val pendingPhoto = "content://synthetic/pending.jpg"
        val orphan = "content://synthetic/orphan.png"
        val checkpoint = ReceiptUploadCheckpoint(
            tenantId = "tenant-test",
            photoUri = pendingPhoto,
            idempotencyKey = "synthetic-idempotency-key-001",
        )

        val toRelease = ReceiptUriCleanupPolicy.urisToRelease(
            persistedUris = listOf(orphan, pendingPhoto, orphan, pendingPhoto),
            checkpoint = checkpoint,
        )

        assertEquals(setOf(orphan), toRelease)
    }

    @Test fun jobCheckpointDoesNotRetainPhotoGrantAfterUploadWasAccepted() {
        val uri = "content://synthetic/previous-photo.jpg"
        val checkpoint = ReceiptUploadCheckpoint(tenantId = "tenant-test", jobId = "job-test")

        assertEquals(
            setOf(uri),
            ReceiptUriCleanupPolicy.urisToRelease(persistedUris = listOf(uri), checkpoint = checkpoint),
        )
    }
}
