package com.decorix.finance

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class FinanceViewModel(application: Application) : AndroidViewModel(application) {
    val repository = FinanceRepository(application)
    var signedIn by mutableStateOf(repository.signedIn)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var offline by mutableStateOf(false)
    var revision by mutableStateOf(0)
    var language by mutableStateOf(repository.preferences.getString("language", "ru")!!)
    var theme by mutableStateOf(repository.preferences.getString("theme", "system")!!)
    var screen by mutableStateOf("home")

    fun perform(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true; error = null
            try { block() } catch (failure: Exception) {
                error = when (failure) {
                    is ApiFailure -> failure.code
                    is IllegalArgumentException -> "invalid_input"
                    else -> "network_error"
                }
            } finally { busy = false; signedIn = repository.signedIn }
        }
    }
    suspend fun load(path: String): String {
        return try { repository.call(path).also { offline = false } }
        catch (failure: Exception) {
            if (failure is ApiFailure) throw failure
            repository.cached(path)?.also { offline = true } ?: throw failure
        }
    }
    fun authenticate(username: String, password: String, name: String?) = perform {
        repository.login(username, password, name, language)
        signedIn = true; screen = "home"; revision++
    }
    fun mutate(path: String, method: String = "POST", data: JSONObject = JSONObject(), done: () -> Unit = {}) = perform {
        repository.call(path, method, data)
        revision++; done()
    }
    fun appearance(lang: String = language, mode: String = theme) {
        language = lang; theme = mode
        repository.preferences.edit().putString("language", lang).putString("theme", mode).apply()
    }
    fun logout() = perform { repository.logout(); screen = "home"; revision++ }
}
