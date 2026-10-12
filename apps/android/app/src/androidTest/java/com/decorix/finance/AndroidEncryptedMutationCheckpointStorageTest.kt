package com.decorix.finance

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidEncryptedMutationCheckpointStorageTest {
    @Test fun repeatAndVoidRecordsSurviveFreshStorageInstancesAsExactSnapshots() = withIsolatedStorage { context ->
        val repeat = checkpoint(MutationAction.REPEAT, marker = "repeat")
        val void = checkpoint(MutationAction.VOID, marker = "void").copy(occurredAt = null)
        val expected = listOf(repeat, void)

        AndroidEncryptedMutationCheckpointStorage(context).write(expected)

        val recovered = AndroidEncryptedMutationCheckpointStorage(context).read()
        assertEquals(expected.map { it.copy(sessionGeneration = 0L) }, recovered)
        assertEquals("process-local generation must not be persisted", listOf(0L, 0L),
            recovered.map { it.sessionGeneration })
    }

    @Test fun sharedPreferencesContainNoPlaintextTransactionFieldsOrIdempotencyKey() = withIsolatedStorage { context ->
        val record = checkpoint(MutationAction.REPEAT, marker = "plaintext-sentinel")
        AndroidEncryptedMutationCheckpointStorage(context).write(listOf(record))

        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val persistedText = preferences.all.entries.joinToString("\n") { (key, value) -> "$key=$value" }
        val plaintextSecrets = listOf(
            record.actorUserId,
            record.tenantId,
            record.original.id,
            record.original.description,
            record.original.amount,
            record.original.occurredAt,
            record.idempotencyKey,
        )

        plaintextSecrets.forEach { secret ->
            assertFalse("SharedPreferences exposed plaintext field: $secret", persistedText.contains(secret))
        }
    }

    @Test fun damagedCiphertextFailsClosedInsteadOfReturningAnEmptyCheckpointList(): Unit =
        withIsolatedStorage { context ->
        AndroidEncryptedMutationCheckpointStorage(context).write(
            listOf(checkpoint(MutationAction.VOID, "damaged").copy(occurredAt = null)))
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val damaged = Base64.encodeToString("synthetic-invalid-aes-gcm-ciphertext".toByteArray(), Base64.NO_WRAP)
        check(preferences.edit().putString(CIPHERTEXT_KEY, damaged).commit())

        assertThrows(Exception::class.java) {
            AndroidEncryptedMutationCheckpointStorage(context).read()
        }
    }

    @Test fun missingKeystoreKeyCannotReadOrReplaceExistingEncryptedCheckpoint(): Unit =
        withMissingKeyStorage { context, preferences ->
            val storage = AndroidEncryptedMutationCheckpointStorage(
                context,
                MISSING_KEY_PREFERENCES_NAME,
                MISSING_KEY_ALIAS,
            )
            storage.write(listOf(checkpoint(MutationAction.REPEAT, "missing-key-original")))
            val originalBlob = preferences.all.toMap()
            assertEquals(setOf(IV_KEY, CIPHERTEXT_KEY), originalBlob.keys)

            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
                deleteEntry(MISSING_KEY_ALIAS)
            }

            assertThrows(Exception::class.java) { storage.read() }
            assertThrows(Exception::class.java) {
                storage.write(listOf(checkpoint(MutationAction.VOID, "must-not-replace-original")))
            }
            assertEquals("unreadable encrypted user intent must remain unchanged", originalBlob, preferences.all)
        }

    private fun checkpoint(action: MutationAction, marker: String): MutationCheckpoint {
        val id = UUID.randomUUID().toString()
        val tenantId = UUID.randomUUID().toString()
        val actorUserId = UUID.randomUUID().toString()
        val idempotencyKey = UUID.randomUUID().toString()
        return MutationCheckpoint(
            action = action,
            sessionGeneration = 71L,
            actorUserId = actorUserId,
            tenantId = tenantId,
            original = FinanceTransaction(
                id = id,
                tenantId = tenantId,
                type = "expense",
                amount = "68.91",
                currency = "RUB",
                categoryCode = "food",
                subcategoryCode = "groceries",
                description = "SYNTHETIC-$marker-${UUID.randomUUID()}",
                source = "manual",
                occurredAt = "2026-10-11T09:30:00Z",
                accountId = null,
                status = "posted",
                version = 11,
                createdAt = "2026-10-11T09:30:01Z",
                memberName = "Synthetic member",
                debtId = null,
                ownerUserId = actorUserId,
            ),
            occurredAt = "2026-10-12T12:00:00Z",
            idempotencyKey = idempotencyKey,
        )
    }

    private inline fun <T> withIsolatedStorage(block: (Context) -> T): T {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val previousIv = preferences.getString(IV_KEY, null)
        val previousCiphertext = preferences.getString(CIPHERTEXT_KEY, null)
        return try {
            block(context)
        } finally {
            val editor = preferences.edit().remove(IV_KEY).remove(CIPHERTEXT_KEY)
            if (previousIv != null) editor.putString(IV_KEY, previousIv)
            if (previousCiphertext != null) editor.putString(CIPHERTEXT_KEY, previousCiphertext)
            check(editor.commit())
        }
    }

    private inline fun <T> withMissingKeyStorage(block: (Context, android.content.SharedPreferences) -> T): T {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(MISSING_KEY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            deleteEntry(MISSING_KEY_ALIAS)
        }
        return try {
            block(context, preferences)
        } finally {
            check(preferences.edit().clear().commit())
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
                deleteEntry(MISSING_KEY_ALIAS)
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "finance_transaction_mutations"
        const val IV_KEY = "iv"
        const val CIPHERTEXT_KEY = "ciphertext"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MISSING_KEY_PREFERENCES_NAME = "finance_transaction_mutations_missing_key_test"
        const val MISSING_KEY_ALIAS = "finance-transaction-mutations-missing-key-test"
    }
}
