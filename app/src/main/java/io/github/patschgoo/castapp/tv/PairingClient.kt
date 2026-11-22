package io.github.patschgoo.castapp.tv

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.network.Secrets
import io.github.patschgoo.castapp.service.CastController
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Where the phone keeps the pairing tokens of the CastApp TVs it paired with. */
interface TokenStore {
    fun get(deviceId: String): String?
    fun put(deviceId: String, token: String)
    fun remove(deviceId: String)
}

/** Tokens per device id, encrypted with a key from Android's Keystore ([Secrets]). */
class PrefsTokenStore(
    private val prefs: SharedPreferences,
    private val encrypt: (String) -> String = Secrets::encrypt,
    private val decrypt: (String) -> String = Secrets::decrypt,
) : TokenStore {
    override fun get(deviceId: String): String? = prefs.getString(PREFIX + deviceId, null)?.let(decrypt)?.takeIf { it.isNotEmpty() }

    override fun put(deviceId: String, token: String) {
        prefs.edit().putString(PREFIX + deviceId, encrypt(token)).apply()
    }

    override fun remove(deviceId: String) {
        prefs.edit().remove(PREFIX + deviceId).apply()
    }

    private companion object {
        const val PREFIX = "token_"
    }
}

/**
 * The phone's side of "Only paired phones": asks a CastApp receiver whether it needs a code,
 * lets the user type it in once and remembers the token the receiver hands out.
 */
class PairingClient(
    private val tokens: TokenStore,
    /** Asks the user for the code the receiver shows (null = cancelled). */
    private val askCode: suspend (deviceName: String) -> String?,
    private val clientName: () -> String,
    private val post: (url: String, headers: Map<String, String>, body: String) -> Pair<Int, String> = ::httpPost,
) {
    /** Something the user should read ("Wrong code", "Cancelled"...). */
    class PairingException(message: String) : Exception(message)

    /** The headers every request to [device] carries: who we are and, once paired, the token. */
    fun headersFor(device: CastDevice): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        val name = clientName().filter { it.code in 32..126 }.trim().take(40)
        if (name.isNotEmpty()) headers[ReceiverGuard.NAME_HEADER] = name
        tokens.get(device.id)?.let { headers[ReceiverGuard.TOKEN_HEADER] = it }
        return headers
    }

    fun forget(device: CastDevice) = tokens.remove(device.id)

    /**
     * Makes sure this phone may cast to [device] (at [base], "http://host:port"): nothing to do for
     * receivers that don't ask for a code, otherwise asks for it and stores the token.
     * Returns the token in use, or null. Throws [PairingException].
     */
    suspend fun ensure(device: CastDevice, base: String): String? {
        val name = clientName().filter { it.code in 32..126 }.trim().take(40)
        var attempts = 0
        var code: String? = null
        while (true) {
            val headers = headersFor(device)
            val body = JSONObject().put("name", name).apply { if (code != null) put("code", code) }.toString()
            val (status, text) = post("$base/castapp/pair", headers, body)
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            when (status) {
                // Unreachable (the caller reports that), or an older CastApp / a receiver without protection.
                -1, 404 -> return tokens.get(device.id)
                200 -> {
                    val token = json.optString("token")
                    if (token.isNotEmpty()) tokens.put(device.id, token)
                    return tokens.get(device.id)
                }
                401, 403 -> {
                    val error = json.optString("error")
                    if (error != "code_required" && error != "wrong_code") {
                        throw PairingException(text.ifBlank { Txt.sOr(R.string.pair_not_allowed, "%s doesn't allow this phone.", device.name) })
                    }
                    if (error == "wrong_code" && ++attempts >= MAX_ATTEMPTS) {
                        throw PairingException(Txt.sOr(R.string.pair_wrong_code, "Wrong code. Look at the code on %s and try again.", device.name))
                    }
                    if (error == "code_required") tokens.remove(device.id)
                    code = askCode(device.name)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw PairingException(Txt.sOr(R.string.pair_cancelled, "Pairing cancelled. %s only accepts paired phones.", device.name))
                }
                429 -> throw PairingException(
                    Txt.sOr(R.string.pair_too_many, "Too many wrong codes. Wait %d s, then try again with the new code on %s.", json.optInt("retryInSeconds", 60), device.name),
                )
                else -> throw PairingException(Txt.sOr(R.string.pair_cant_now, "%s can't pair right now (HTTP %d).", device.name, status))
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        const val PAIRING_REQUIRED_MARKER = "Pairing required"

        /** POSTs [body] as JSON; returns the status code (-1 if unreachable) and the answer text. */
        fun httpPost(url: String, headers: Map<String, String>, body: String): Pair<Int, String> = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 4000
                conn.readTimeout = 8000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                // No streaming mode: with it a 401 answer turns into an exception instead of the status.
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                code to text
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            -1 to ""
        }
    }
}

/** The phone's pairing client (null where there is no app, e.g. in plain JVM tests). */
object AppPairing {
    val client: PairingClient? by lazy {
        try {
            val app = CastApp.instance
            PairingClient(
                tokens = PrefsTokenStore(app.getSharedPreferences("receiver_tokens", Context.MODE_PRIVATE)),
                askCode = { CastController.askPin(it) },
                clientName = { Build.MODEL ?: "" },
            )
        } catch (_: Throwable) {
            null
        }
    }
}
