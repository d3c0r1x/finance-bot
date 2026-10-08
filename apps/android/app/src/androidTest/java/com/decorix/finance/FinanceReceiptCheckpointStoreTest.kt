package com.decorix.finance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceReceiptCheckpointStoreTest {
    @Test fun pendingUploadAndJobCheckpointSurviveStoreRecreationAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstStore = ReceiptUploadCheckpointStore(context)
        firstStore.clear()

        firstStore.savePending(tenantId = "tenant-17", photoUri = "content://synthetic/receipt.jpg",
            idempotencyKey = "synthetic-idempotency-key-29")

        assertEquals(ReceiptUploadCheckpoint("tenant-17", null, "content://synthetic/receipt.jpg",
            "synthetic-idempotency-key-29"),
            ReceiptUploadCheckpointStore(context).load())

        firstStore.saveJob(tenantId = "tenant-17", jobId = "job-29")
        assertEquals(ReceiptUploadCheckpoint("tenant-17", "job-29", null, null),
            ReceiptUploadCheckpointStore(context).load())

        firstStore.clear()
        assertNull(ReceiptUploadCheckpointStore(context).load())
    }
}
