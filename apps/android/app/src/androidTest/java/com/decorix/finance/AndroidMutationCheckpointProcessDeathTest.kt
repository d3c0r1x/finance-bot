package com.decorix.finance

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.security.KeyStore

/**
 * Two ordered phases let the host script force-stop the target app between test invocations.
 * The dedicated preferences and Keystore alias ensure this never touches a user's pending request.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AndroidMutationCheckpointProcessDeathTest {
    @Test fun test01SeedSyntheticRepeatAndVoid() {
        clearSyntheticState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = syntheticCheckpoints()

        AndroidEncryptedMutationCheckpointStorage(context, TEST_PREFERENCES, TEST_KEY_ALIAS).write(expected)

        assertEquals(expected.map { it.copy(sessionGeneration = 0L) },
            AndroidEncryptedMutationCheckpointStorage(context, TEST_PREFERENCES, TEST_KEY_ALIAS).read())
    }

    @Test fun test02RecoverExactSnapshotsAfterHostForceStop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            val recovered = AndroidEncryptedMutationCheckpointStorage(
                context, TEST_PREFERENCES, TEST_KEY_ALIAS,
            ).read()

            assertEquals(syntheticCheckpoints().map { it.copy(sessionGeneration = 0L) }, recovered)
        } finally {
            clearSyntheticState()
        }
    }

    @Test fun test03CleanupSyntheticState() {
        clearSyntheticState()
    }

    private fun syntheticCheckpoints() = listOf(
        MutationCheckpoint(
            action = MutationAction.REPEAT,
            actorUserId = "process-death-test-user",
            tenantId = "process-death-test-tenant",
            original = sourceTransaction("process-death-repeat-source", version = 17),
            occurredAt = "2026-10-12T12:34:56Z",
            idempotencyKey = "process-death-repeat-idempotency-key",
            sessionGeneration = 73L,
        ),
        MutationCheckpoint(
            action = MutationAction.VOID,
            actorUserId = "process-death-test-user",
            tenantId = "process-death-test-tenant",
            original = sourceTransaction("process-death-void-source", version = 23),
            occurredAt = null,
            idempotencyKey = "process-death-void-idempotency-key",
            sessionGeneration = 73L,
        ),
    )

    private fun sourceTransaction(id: String, version: Long) = FinanceTransaction(
        id = id,
        tenantId = "process-death-test-tenant",
        type = "expense",
        amount = "68.91",
        currency = "RUB",
        categoryCode = "food",
        subcategoryCode = "groceries",
        description = "SYNTHETIC-PROCESS-DEATH-$id",
        source = "manual",
        occurredAt = "2026-10-11T09:30:00Z",
        accountId = null,
        status = "posted",
        version = version,
        createdAt = "2026-10-11T09:30:01Z",
        memberName = "Synthetic member",
        debtId = null,
        ownerUserId = "process-death-test-user",
    )

    private fun clearSyntheticState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit())
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(TEST_KEY_ALIAS)) deleteEntry(TEST_KEY_ALIAS)
        }
    }

    private companion object {
        const val TEST_PREFERENCES = "finance_transaction_mutations_process_death_test"
        const val TEST_KEY_ALIAS = "finance-mutation-process-death-test-v1"
    }
}
