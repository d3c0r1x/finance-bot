package com.decorix.finance

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiFailure(val status: Int, val code: String) : IOException(code)

/** Tokens are encrypted with a non-exportable Android Keystore key. */
class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val alias = "finance-session"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(tokens: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(tokens.toString().toByteArray())
        prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()
    }
    fun read(): JSONObject? = runCatching {
        val value = prefs.getString("data", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        }
        JSONObject(String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP))))
    }.getOrNull()
    fun clear() { prefs.edit().clear().commit() }
}

class FinanceRepository(context: Context) {
    val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val vault = TokenVault(context)
    private var tokens = vault.read()
    private val refreshLock = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(250, TimeUnit.SECONDS).build()
    private val cache: SQLiteDatabase = context.openOrCreateDatabase("cache.db", Context.MODE_PRIVATE, null).apply {
        execSQL("CREATE TABLE IF NOT EXISTS responses(path TEXT PRIMARY KEY, body TEXT NOT NULL)")
    }
    var baseUrl: String
        get() = preferences.getString("server", "http://10.0.2.2:8000")!!
        set(value) {
            val url = value.trim().trimEnd('/')
            require(url.startsWith("https://") || (BuildConfig.DEBUG && url.startsWith("http://")))
            if (url != baseUrl) clearSession()
            preferences.edit().putString("server", url).commit()
        }
    val signedIn get() = tokens != null

    private fun execute(path: String, method: String, body: RequestBody?, authorized: Boolean): String {
        val request = Request.Builder().url(baseUrl + "/api/v1/" + path).method(method, body)
        if (authorized) tokens?.optString("access_token")?.let { request.header("Authorization", "Bearer $it") }
        client.newCall(request.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).opt("detail") }.getOrNull()
                throw ApiFailure(response.code, detail as? String ?: "invalid_input")
            }
            return text
        }
    }

    suspend fun login(username: String, password: String, name: String?, language: String) = withContext(Dispatchers.IO) {
        val data = JSONObject().put("username", username.trim()).put("password", password)
        if (name != null) data.put("display_name", name).put("language", language)
        val result = execute("auth/" + if (name == null) "login" else "register", "POST", jsonBody(data), false)
        clearSession()
        tokens = JSONObject(result)
        vault.save(tokens!!)
    }

    private fun jsonBody(value: JSONObject) = value.toString().toRequestBody("application/json".toMediaType())

    suspend fun call(path: String, method: String = "GET", data: JSONObject? = null,
                     upload: ByteArray? = null, mime: String = "image/jpeg"): String = withContext(Dispatchers.IO) {
        val body = if (upload != null) MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", if (mime == "application/pdf") "statement.pdf" else "receipt.jpg", upload.toRequestBody(mime.toMediaType())).build()
        else if (method in listOf("POST", "PUT")) jsonBody(data ?: JSONObject()) else null
        val oldAccess = tokens?.optString("access_token")
        val result = try {
            execute(path, method, body, true)
        } catch (failure: ApiFailure) {
            if (failure.status != 401) throw failure
            refreshLock.withLock {
                if (tokens?.optString("access_token") == oldAccess) {
                    try {
                        val refreshed = execute("auth/refresh", "POST", jsonBody(JSONObject().put("refresh_token", tokens?.optString("refresh_token"))), false)
                        tokens = JSONObject(refreshed)
                        vault.save(tokens!!)
                    } catch (refreshFailure: ApiFailure) {
                        if (refreshFailure.status == 401) clearSession()
                        throw refreshFailure
                    }
                }
            }
            execute(path, method, body, true)
        }
        if (method == "GET") cache.execSQL("INSERT OR REPLACE INTO responses VALUES(?,?)", arrayOf(path, result))
        else if (method != "GET") cache.execSQL("DELETE FROM responses")
        result
    }

    fun cached(path: String): String? = cache.rawQuery("SELECT body FROM responses WHERE path=?", arrayOf(path)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    suspend fun logout() { try { call("auth/logout", "POST") } finally { clearSession() } }
    fun clearSession() {
        tokens = null
        vault.clear()
        cache.execSQL("DELETE FROM responses")
    }
}
