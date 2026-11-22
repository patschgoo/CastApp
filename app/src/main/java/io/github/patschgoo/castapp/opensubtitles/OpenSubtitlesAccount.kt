package io.github.patschgoo.castapp.opensubtitles

import android.content.Context
import androidx.core.content.edit
import io.github.patschgoo.castapp.network.Secrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's own OpenSubtitles.com API key and (optional) login, encrypted with a key
 * that stays in Android's Keystore. Without an API key the online search is off.
 */
class OpenSubtitlesAccount private constructor(context: Context) {
    data class Credentials(val apiKey: String, val username: String, val password: String) {
        val hasLogin: Boolean get() = username.isNotBlank() && password.isNotEmpty()
    }

    private val prefs = context.applicationContext.getSharedPreferences("opensubtitles", Context.MODE_PRIVATE)

    private val _hasKey = MutableStateFlow(!prefs.getString(KEY, null).isNullOrEmpty())
    /** Whether an API key is saved (the search can be used). */
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    /** Login of this app run; OpenSubtitles tokens last about a day. */
    @Volatile var login: OpenSubtitlesClient.Login? = null

    fun credentials(): Credentials = Credentials(
        apiKey = Secrets.decrypt(prefs.getString(KEY, "") ?: ""),
        username = Secrets.decrypt(prefs.getString(USER, "") ?: ""),
        password = Secrets.decrypt(prefs.getString(PASSWORD, "") ?: ""),
    )

    fun save(credentials: Credentials) {
        prefs.edit {
            putString(KEY, Secrets.encrypt(credentials.apiKey.trim()))
            putString(USER, Secrets.encrypt(credentials.username.trim()))
            putString(PASSWORD, Secrets.encrypt(credentials.password))
        }
        login = null
        _hasKey.value = credentials.apiKey.isNotBlank()
    }

    fun clear() = save(Credentials("", "", ""))

    companion object {
        private const val KEY = "api_key"
        private const val USER = "username"
        private const val PASSWORD = "password"

        @Volatile private var instance: OpenSubtitlesAccount? = null

        fun get(context: Context): OpenSubtitlesAccount =
            instance ?: synchronized(this) { instance ?: OpenSubtitlesAccount(context).also { instance = it } }
    }
}
