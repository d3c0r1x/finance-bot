package com.decorix.finance

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class MutationAction { REPEAT, VOID }

data class MutationCheckpoint(
    val action: MutationAction,
    val actorUserId: String,
    val tenantId: String,
    val original: FinanceTransaction,
    val occurredAt: String?,
    val idempotencyKey: String,
    val sessionGeneration: Long,
)

internal interface MutationCheckpointStorage {
    fun read(): List<MutationCheckpoint>
    fun write(checkpoints: List<MutationCheckpoint>)
}

/** AES/GCM-backed durable checkpoint storage. A present but unreadable blob is never treated as empty. */
internal class AndroidEncryptedMutationCheckpointStorage(context: Context) : MutationCheckpointStorage {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): List<MutationCheckpoint> = synchronized(STORAGE_LOCK) {
        readLocked()
    }

    override fun write(checkpoints: List<MutationCheckpoint>) = synchronized(STORAGE_LOCK) {
        // Validate any existing record before replacing it. This prevents a later action from
        // silently erasing an unresolved intent after a missing key or damaged ciphertext.
        if (preferences.all.isNotEmpty()) readLocked()
        checkpoints.forEach(::validateCheckpoint)

        if (checkpoints.isEmpty()) {
            check(preferences.edit().remove(IV_KEY).remove(CIPHERTEXT_KEY).commit()) {
                "Could not clear completed mutation checkpoints"
            }
            return@synchronized
        }

        val plaintext = encode(checkpoints)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyForWrite())
        val ciphertext = cipher.doFinal(plaintext)
        check(preferences.edit()
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHERTEXT_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()) {
            "Could not persist encrypted mutation checkpoints"
        }
    }

    private fun readLocked(): List<MutationCheckpoint> {
        val stored = preferences.all
        if (stored.isEmpty()) return emptyList()
        if (stored.keys != ENCRYPTED_KEYS || stored.values.any { it !is String }) {
            throw IllegalStateException("Mutation checkpoint preferences are incomplete or invalid")
        }
        val encodedIv = stored[IV_KEY] as? String
            ?: throw IllegalStateException("Mutation checkpoint IV is missing")
        val encodedCiphertext = stored[CIPHERTEXT_KEY] as? String
            ?: throw IllegalStateException("Mutation checkpoint ciphertext is missing")
        val iv = decodeBase64(encodedIv, "Mutation checkpoint IV is invalid")
        val ciphertext = decodeBase64(encodedCiphertext, "Mutation checkpoint ciphertext is invalid")
        if (iv.size != GCM_IV_BYTES || ciphertext.size < GCM_TAG_BYTES) {
            throw IllegalStateException("Mutation checkpoint ciphertext is incomplete")
        }

        val key = existingKey()
            ?: throw IllegalStateException("Mutation checkpoint encryption key is missing")
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        val json = decodeUtf8(plaintext)
        return decode(json)
    }

    private fun keyForWrite(): SecretKey = existingKey() ?: createKey()

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(KEY_ALIAS, null) ?: return null
        return (entry as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw IllegalStateException("Mutation checkpoint key has an unsupported type")
    }

    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build())
        }
        .generateKey()

    private fun encode(checkpoints: List<MutationCheckpoint>): ByteArray {
        val root = JSONObject()
            .put(SCHEMA_VERSION_FIELD, SCHEMA_VERSION)
            .put(CHECKPOINTS_FIELD, JSONArray().apply {
                checkpoints.forEach { checkpoint -> put(encodeCheckpoint(checkpoint)) }
            })
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun encodeCheckpoint(checkpoint: MutationCheckpoint): JSONObject {
        val original = checkpoint.original
        return JSONObject()
            .put(ACTION_FIELD, checkpoint.action.name)
            .put(ACTOR_USER_ID_FIELD, checkpoint.actorUserId)
            .put(TENANT_ID_FIELD, checkpoint.tenantId)
            .put(ORIGINAL_FIELD, JSONObject()
                .put(TRANSACTION_ID_FIELD, original.id)
                .put(TENANT_ID_FIELD, original.tenantId)
                .put(TYPE_FIELD, original.type)
                .put(AMOUNT_FIELD, original.amount)
                .put(CURRENCY_FIELD, original.currency)
                .put(CATEGORY_CODE_FIELD, original.categoryCode)
                .put(SUBCATEGORY_CODE_FIELD, original.subcategoryCode ?: JSONObject.NULL)
                .put(DESCRIPTION_FIELD, original.description)
                .put(SOURCE_FIELD, original.source)
                .put(OCCURRED_AT_FIELD, original.occurredAt)
                .put(ACCOUNT_ID_FIELD, original.accountId ?: JSONObject.NULL)
                .put(STATUS_FIELD, original.status)
                .put(VERSION_FIELD, original.version)
                .put(CREATED_AT_FIELD, original.createdAt)
                .put(MEMBER_NAME_FIELD, original.memberName ?: JSONObject.NULL)
                .put(DEBT_ID_FIELD, original.debtId ?: JSONObject.NULL)
                .put(OWNER_USER_ID_FIELD, original.ownerUserId ?: JSONObject.NULL))
            .put(OCCURRED_AT_FIELD, checkpoint.occurredAt ?: JSONObject.NULL)
            .put(IDEMPOTENCY_KEY_FIELD, checkpoint.idempotencyKey)
        // sessionGeneration is deliberately process-local and never serialized.
    }

    private fun decode(json: String): List<MutationCheckpoint> {
        val root = JSONObject(json)
        requireExactKeys(root, setOf(SCHEMA_VERSION_FIELD, CHECKPOINTS_FIELD), "checkpoint document")
        val schemaVersion = root.get(SCHEMA_VERSION_FIELD)
        if (schemaVersion !is Number || schemaVersion.toInt() != SCHEMA_VERSION ||
            schemaVersion.toDouble() != SCHEMA_VERSION.toDouble()) {
            throw IllegalStateException("Unsupported mutation checkpoint schema")
        }
        val array = root.get(CHECKPOINTS_FIELD) as? JSONArray
            ?: throw IllegalStateException("Mutation checkpoint list is invalid")
        return (0 until array.length()).map { index ->
            val item = array.get(index) as? JSONObject
                ?: throw IllegalStateException("Mutation checkpoint entry is invalid")
            decodeCheckpoint(item)
        }
    }

    private fun decodeCheckpoint(json: JSONObject): MutationCheckpoint {
        requireExactKeys(json, CHECKPOINT_FIELDS, "checkpoint")
        val action = runCatching { MutationAction.valueOf(requiredString(json, ACTION_FIELD)) }
            .getOrElse { throw IllegalStateException("Mutation checkpoint action is invalid", it) }
        val originalJson = json.get(ORIGINAL_FIELD) as? JSONObject
            ?: throw IllegalStateException("Mutation checkpoint transaction is invalid")
        requireExactKeys(originalJson, TRANSACTION_FIELDS, "source transaction")
        val original = FinanceTransaction(
            id = requiredString(originalJson, TRANSACTION_ID_FIELD),
            tenantId = requiredString(originalJson, TENANT_ID_FIELD),
            type = requiredString(originalJson, TYPE_FIELD),
            amount = requiredString(originalJson, AMOUNT_FIELD),
            currency = requiredString(originalJson, CURRENCY_FIELD),
            categoryCode = requiredString(originalJson, CATEGORY_CODE_FIELD),
            subcategoryCode = nullableString(originalJson, SUBCATEGORY_CODE_FIELD),
            description = requiredString(originalJson, DESCRIPTION_FIELD),
            source = requiredString(originalJson, SOURCE_FIELD),
            occurredAt = requiredString(originalJson, OCCURRED_AT_FIELD),
            accountId = nullableString(originalJson, ACCOUNT_ID_FIELD),
            status = requiredString(originalJson, STATUS_FIELD),
            version = requiredLong(originalJson, VERSION_FIELD),
            createdAt = requiredString(originalJson, CREATED_AT_FIELD),
            memberName = nullableString(originalJson, MEMBER_NAME_FIELD),
            debtId = nullableString(originalJson, DEBT_ID_FIELD),
            ownerUserId = nullableString(originalJson, OWNER_USER_ID_FIELD),
        )
        val checkpoint = MutationCheckpoint(
            action = action,
            actorUserId = requiredString(json, ACTOR_USER_ID_FIELD),
            tenantId = requiredString(json, TENANT_ID_FIELD),
            original = original,
            occurredAt = nullableString(json, OCCURRED_AT_FIELD),
            idempotencyKey = requiredString(json, IDEMPOTENCY_KEY_FIELD),
            sessionGeneration = 0L,
        )
        validateCheckpoint(checkpoint)
        return checkpoint
    }

    private fun validateCheckpoint(checkpoint: MutationCheckpoint) {
        require(checkpoint.actorUserId.isNotBlank()) { "Mutation checkpoint actor is required" }
        require(checkpoint.tenantId.isNotBlank() && checkpoint.original.tenantId == checkpoint.tenantId) {
            "Mutation checkpoint transaction tenant does not match"
        }
        require(checkpoint.original.id.isNotBlank() && checkpoint.original.status == "posted" &&
            checkpoint.original.version > 0) { "Mutation checkpoint source transaction is invalid" }
        require(checkpoint.idempotencyKey.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH &&
            checkpoint.idempotencyKey.none(Char::isISOControl)) { "Mutation checkpoint idempotency key is invalid" }
        when (checkpoint.action) {
            MutationAction.REPEAT -> require(!checkpoint.occurredAt.isNullOrBlank()) {
                "Repeated mutation timestamp is required"
            }
            MutationAction.VOID -> require(checkpoint.occurredAt == null) {
                "Void mutation must not have a repeated timestamp"
            }
        }
    }

    private fun requiredString(json: JSONObject, key: String): String =
        json.get(key) as? String ?: throw IllegalStateException("Mutation checkpoint field $key is invalid")

    private fun nullableString(json: JSONObject, key: String): String? {
        if (!json.has(key)) throw IllegalStateException("Mutation checkpoint field $key is missing")
        val value = json.get(key)
        return if (value === JSONObject.NULL) null
        else value as? String ?: throw IllegalStateException("Mutation checkpoint field $key is invalid")
    }

    private fun requiredLong(json: JSONObject, key: String): Long {
        val number = json.get(key) as? Number
            ?: throw IllegalStateException("Mutation checkpoint field $key is invalid")
        return try {
            BigDecimal(number.toString()).longValueExact()
        } catch (failure: ArithmeticException) {
            throw IllegalStateException("Mutation checkpoint field $key is invalid", failure)
        }
    }

    private fun requireExactKeys(json: JSONObject, expected: Set<String>, description: String) {
        val actual = json.keys().asSequence().toSet()
        if (actual != expected) throw IllegalStateException("Mutation checkpoint $description fields are invalid")
    }

    private fun decodeBase64(value: String, message: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException(message, failure)
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString()

    private companion object {
        const val PREFERENCES_NAME = "finance_transaction_mutations"
        const val KEY_ALIAS = "finance-mutation-checkpoints-v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val KEY_SIZE_BITS = 256
        const val MIN_KEY_LENGTH = 16
        const val MAX_KEY_LENGTH = 128
        const val SCHEMA_VERSION = 1
        const val IV_KEY = "iv"
        const val CIPHERTEXT_KEY = "ciphertext"
        const val SCHEMA_VERSION_FIELD = "schemaVersion"
        const val CHECKPOINTS_FIELD = "checkpoints"
        const val ACTION_FIELD = "action"
        const val ACTOR_USER_ID_FIELD = "actorUserId"
        const val TENANT_ID_FIELD = "tenantId"
        const val ORIGINAL_FIELD = "original"
        const val OCCURRED_AT_FIELD = "occurredAt"
        const val IDEMPOTENCY_KEY_FIELD = "idempotencyKey"
        const val TRANSACTION_ID_FIELD = "id"
        const val TYPE_FIELD = "type"
        const val AMOUNT_FIELD = "amount"
        const val CURRENCY_FIELD = "currency"
        const val CATEGORY_CODE_FIELD = "categoryCode"
        const val SUBCATEGORY_CODE_FIELD = "subcategoryCode"
        const val DESCRIPTION_FIELD = "description"
        const val SOURCE_FIELD = "source"
        const val ACCOUNT_ID_FIELD = "accountId"
        const val STATUS_FIELD = "status"
        const val VERSION_FIELD = "version"
        const val CREATED_AT_FIELD = "createdAt"
        const val MEMBER_NAME_FIELD = "memberName"
        const val DEBT_ID_FIELD = "debtId"
        const val OWNER_USER_ID_FIELD = "ownerUserId"
        val STORAGE_LOCK = Any()
        val ENCRYPTED_KEYS = setOf(IV_KEY, CIPHERTEXT_KEY)
        val CHECKPOINT_FIELDS = setOf(
            ACTION_FIELD, ACTOR_USER_ID_FIELD, TENANT_ID_FIELD, ORIGINAL_FIELD,
            OCCURRED_AT_FIELD, IDEMPOTENCY_KEY_FIELD,
        )
        val TRANSACTION_FIELDS = setOf(
            TRANSACTION_ID_FIELD, TENANT_ID_FIELD, TYPE_FIELD, AMOUNT_FIELD, CURRENCY_FIELD,
            CATEGORY_CODE_FIELD, SUBCATEGORY_CODE_FIELD, DESCRIPTION_FIELD, SOURCE_FIELD,
            OCCURRED_AT_FIELD, ACCOUNT_ID_FIELD, STATUS_FIELD, VERSION_FIELD, CREATED_AT_FIELD,
            MEMBER_NAME_FIELD, DEBT_ID_FIELD, OWNER_USER_ID_FIELD,
        )
    }
}
