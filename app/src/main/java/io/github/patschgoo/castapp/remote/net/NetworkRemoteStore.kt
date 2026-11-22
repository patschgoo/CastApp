package io.github.patschgoo.castapp.remote.net

import android.content.Context
import androidx.core.content.edit
import io.github.patschgoo.castapp.network.Secrets
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the network remotes remember: TVs used before, the phone's Android TV certificate
 * and the per-TV Samsung tokens and LG client keys (those three encrypted with [Secrets]).
 */
internal class NetworkRemoteStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("network_remotes", Context.MODE_PRIVATE)
    private var identity: ClientIdentity? = null

    /** The phone's client certificate for Android TVs; made once (takes a second) and kept. */
    @Synchronized
    fun identity(onGenerate: () -> Unit = {}): ClientIdentity {
        identity?.let { return it }
        val stored = prefs.getString(KEY_IDENTITY, null)?.let(Secrets::decrypt)?.let(ClientIdentity::decode)
        if (stored != null) {
            identity = stored
            return stored
        }
        onGenerate()
        val made = ClientIdentity.generate("CastApp")
        prefs.edit { putString(KEY_IDENTITY, Secrets.encrypt(made.encode())) }
        identity = made
        return made
    }

    fun secret(targetId: String): String? =
        prefs.getString("secret:$targetId", null)?.let(Secrets::decrypt)?.ifEmpty { null }

    fun setSecret(targetId: String, value: String?) = prefs.edit {
        if (value.isNullOrEmpty()) remove("secret:$targetId") else putString("secret:$targetId", Secrets.encrypt(value))
    }

    fun savedTargets(): List<JSONObject> = try {
        val arr = JSONArray(prefs.getString(KEY_TARGETS, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun remember(json: JSONObject) {
        val id = json.optString("id")
        val list = savedTargets().filter { it.optString("id") != id } + json
        prefs.edit { putString(KEY_TARGETS, JSONArray(list.takeLast(MAX_SAVED)).toString()) }
    }

    companion object {
        private const val KEY_IDENTITY = "atv_identity"
        private const val KEY_TARGETS = "targets"
        private const val MAX_SAVED = 20

        @Volatile private var instance: NetworkRemoteStore? = null

        fun get(context: Context): NetworkRemoteStore =
            instance ?: synchronized(this) { instance ?: NetworkRemoteStore(context).also { instance = it } }
    }
}
