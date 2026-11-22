package io.github.patschgoo.castapp.automation

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * The two settings of the automation API: the switch (off by default) and the optional secret
 * callers must pass. Kept in its own preferences file.
 */
class AutomationSettings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("automation", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<AutomationConfig> = _config.asStateFlow()
    val current: AutomationConfig get() = _config.value

    private fun load() = AutomationConfig(prefs.getBoolean(KEY_ENABLED, false), prefs.getString(KEY_SECRET, "") ?: "")

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, on) }
        _config.value = _config.value.copy(enabled = on)
    }

    fun setSecret(secret: String) {
        val clean = secret.trim()
        prefs.edit { putString(KEY_SECRET, clean) }
        _config.value = _config.value.copy(secret = clean)
    }

    /** A new random secret (20 letters and digits), already saved. */
    fun generateSecret(): String {
        val random = SecureRandom()
        val secret = buildString { repeat(20) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
        setSecret(secret)
        return secret
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SECRET = "secret"
        // No look-alikes (0/O, 1/l/I): the secret is typed into other apps by hand sometimes.
        private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        @Volatile private var instance: AutomationSettings? = null

        fun get(context: Context): AutomationSettings =
            instance ?: synchronized(this) { instance ?: AutomationSettings(context).also { instance = it } }
    }
}
