package com.decorix.finance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BudgetIdempotencyKeyStoreTest {
    @Test fun operationKeySurvivesRetryAndStoreRecreationUntilCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BudgetIdempotencyKeyStore(context)
        val proposalOperation = "synthetic-budget-operation-${UUID.randomUUID()}"
        val applyOperation = "$proposalOperation:apply"

        val initialKey = store.keyFor(proposalOperation)
        assertTrue(initialKey.isNotBlank())
        assertEquals(initialKey, BudgetIdempotencyKeyStore(context).keyFor(proposalOperation))

        val otherOperationKey = store.keyFor(applyOperation)
        assertNotEquals(initialKey, otherOperationKey)

        store.clear(proposalOperation)
        val retryAfterClear = BudgetIdempotencyKeyStore(context).keyFor(proposalOperation)
        assertTrue(retryAfterClear.isNotBlank())
        assertNotEquals(initialKey, retryAfterClear)
    }
}
