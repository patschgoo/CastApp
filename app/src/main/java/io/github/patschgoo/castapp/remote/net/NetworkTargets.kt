package io.github.patschgoo.castapp.remote.net

import android.content.Context
import android.os.Build
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.remote.NetworkRemotes
import io.github.patschgoo.castapp.remote.RemoteKey
import io.github.patschgoo.castapp.remote.RemoteSession
import io.github.patschgoo.castapp.remote.RemoteTarget
import io.github.patschgoo.castapp.remote.RemoteUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** A connection problem, already in words for the user. */
class NetRemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A TV on the network, remembered by its address. */
internal abstract class NetTarget(val host: String, override val name: String) : RemoteTarget {
    abstract val type: String

    open fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("host", host).put("name", name)

    /** Runs [block], turning network errors into a message for the user. */
    protected suspend fun <T> friendly(context: Context, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: NetRemoteException) {
        throw e
    } catch (e: RemoteDeniedException) {
        throw NetRemoteException(context.getString(deniedMessage, name), e)
    } catch (e: RemoteNoAnswerException) {
        throw NetRemoteException(context.getString(R.string.remote_no_answer), e)
    } catch (e: RokuBlockedException) {
        throw NetRemoteException(context.getString(R.string.remote_roku_blocked, name), e)
    } catch (e: IOException) {
        throw NetRemoteException(context.getString(R.string.remote_cant_reach, name), e)
    } catch (e: Exception) {
        throw NetRemoteException(context.getString(R.string.remote_failed, name), e)
    }

    protected open val deniedMessage: Int get() = R.string.remote_failed

    protected fun remember(context: Context) = NetworkRemotes.remember(context, this)

    companion object {
        fun fromJson(o: JSONObject): NetTarget? {
            val host = o.optString("host").ifEmpty { return null }
            val name = o.optString("name").ifEmpty { host }
            return when (o.optString("type")) {
                AndroidTvTarget.TYPE -> AndroidTvTarget(host, name, o.optInt("port", 6466))
                RokuTarget.TYPE -> RokuTarget(host, name, o.optInt("port", 8060), o.optBoolean("tv"))
                SamsungTarget.TYPE -> SamsungTarget(host, name, o.optBoolean("secure", true))
                LgTarget.TYPE -> LgTarget(host, name)
                else -> null
            }
        }
    }
}

/** Android TV, Google TV, NVIDIA Shield, Chromecast with Google TV, and Sony, Philips, TCL… Google TVs. */
internal class AndroidTvTarget(host: String, name: String, private val port: Int = 6466) : NetTarget(host, name) {
    override val type = TYPE
    override val id = "atv:$host"
    override val label = "Google TV"
    override val keys: Set<RemoteKey> = AtvMessages.keyCodes.keys
    override val supportsText = true

    override fun toJson(): JSONObject = super.toJson().put("port", port)

    override suspend fun connect(context: Context, ui: RemoteUi): RemoteSession = friendly(context) {
        val store = NetworkRemoteStore.get(context)
        val identity = withContext(Dispatchers.Default) {
            store.identity { ui.showMessage(context.getString(R.string.remote_atv_preparing)) }
        }
        ui.showMessage(context.getString(R.string.remote_connecting, name))
        val client = AndroidTvClient(host, identity, remotePort = port, pairPort = PAIR_PORT)
        val conn = try {
            client.open(Build.MODEL ?: "Android", Build.MANUFACTURER ?: "")
        } catch (_: NeedsPairingException) {
            pair(context, ui, client)
            ui.showMessage(context.getString(R.string.remote_connecting, name))
            try {
                client.open(Build.MODEL ?: "Android", Build.MANUFACTURER ?: "")
            } catch (e: NeedsPairingException) {
                throw NetRemoteException(context.getString(R.string.remote_atv_pair_failed, name), e)
            }
        }
        remember(context)
        AtvSession(conn)
    }

    private suspend fun pair(context: Context, ui: RemoteUi, client: AndroidTvClient) {
        ui.showMessage(context.getString(R.string.remote_atv_pairing, name))
        val ok = try {
            client.pair("CastApp") { wrong ->
                ui.askCode(
                    context.getString(R.string.remote_atv_pair_title, name),
                    context.getString(if (wrong) R.string.remote_atv_pair_wrong else R.string.remote_atv_pair_text),
                )
            }
        } catch (e: PairingRejectedException) {
            throw NetRemoteException(context.getString(R.string.remote_atv_rejected), e)
        } catch (e: IOException) {
            throw NetRemoteException(context.getString(R.string.remote_atv_pair_failed, name), e)
        }
        if (!ok) throw NetRemoteException(context.getString(R.string.remote_atv_cancelled))
    }

    private class AtvSession(private val conn: AndroidTvConnection) : RemoteSession {
        override suspend fun press(key: RemoteKey) = withContext(Dispatchers.IO) {
            val code = AtvMessages.keyCodes[key] ?: return@withContext
            conn.sendKey(code)
        }

        override suspend fun type(text: String) = withContext(Dispatchers.IO) { conn.sendText(text) }

        override fun close() = conn.close()
    }

    companion object {
        const val TYPE = "atv"
        const val PAIR_PORT = 6467
    }
}

internal class RokuTarget(host: String, name: String, private val port: Int = 8060, private val isTv: Boolean) : NetTarget(host, name) {
    override val type = TYPE
    override val id = "roku:$host"
    override val label = "Roku"
    override val keys: Set<RemoteKey> = RokuRemoteClient.keys(isTv)
    override val supportsText = true

    override fun toJson(): JSONObject = super.toJson().put("port", port).put("tv", isTv)

    override suspend fun connect(context: Context, ui: RemoteUi): RemoteSession = friendly(context) {
        ui.showMessage(context.getString(R.string.remote_connecting, name))
        val client = RokuRemoteClient(host, port)
        withContext(Dispatchers.IO) { client.info() } ?: throw IOException("No answer")
        remember(context)
        object : RemoteSession {
            override suspend fun press(key: RemoteKey) = withContext(Dispatchers.IO) { client.press(key) }
            override suspend fun type(text: String) = withContext(Dispatchers.IO) { client.type(text) }
            override fun close() {}
        }
    }

    companion object {
        const val TYPE = "roku"
    }
}

internal class SamsungTarget(host: String, name: String, private val secure: Boolean = true) : NetTarget(host, name) {
    override val type = TYPE
    override val id = "samsung:$host"
    override val label = "Samsung"
    override val keys: Set<RemoteKey> = SamsungProtocol.keys.keys
    override val supportsText = true
    override val deniedMessage get() = R.string.remote_samsung_denied

    override fun toJson(): JSONObject = super.toJson().put("secure", secure)

    override suspend fun connect(context: Context, ui: RemoteUi): RemoteSession = friendly(context) {
        val store = NetworkRemoteStore.get(context)
        ui.showMessage(context.getString(R.string.remote_connecting, name))
        val client = SamsungRemoteClient(host, "CastApp", securePort = secure)
        val conn = client.connect(store.secret(id), onPrompt = { ui.showMessage(context.getString(R.string.remote_allow_on_tv)) })
        if (conn.token != null) store.setSecret(id, conn.token)
        remember(context)
        object : RemoteSession {
            override suspend fun press(key: RemoteKey) = withContext(Dispatchers.IO) { conn.press(key) }
            override suspend fun type(text: String) = withContext(Dispatchers.IO) { conn.type(text) }
            override fun close() = conn.close()
        }
    }

    companion object {
        const val TYPE = "samsung"
    }
}

internal class LgTarget(host: String, name: String) : NetTarget(host, name) {
    override val type = TYPE
    override val id = "lg:$host"
    override val label = "LG"
    override val keys: Set<RemoteKey> = LgProtocol.keys
    override val supportsText = true
    override val deniedMessage get() = R.string.remote_lg_denied

    override suspend fun connect(context: Context, ui: RemoteUi): RemoteSession = friendly(context) {
        val store = NetworkRemoteStore.get(context)
        ui.showMessage(context.getString(R.string.remote_connecting, name))
        val conn = LgRemoteClient(host).connect(store.secret(id), onPrompt = { ui.showMessage(context.getString(R.string.remote_accept_on_tv)) })
        store.setSecret(id, conn.clientKey)
        remember(context)
        object : RemoteSession {
            override suspend fun press(key: RemoteKey) = withContext(Dispatchers.IO) { conn.press(key) }
            override suspend fun type(text: String) = withContext(Dispatchers.IO) { conn.type(text) }
            override fun close() = conn.close()
        }
    }

    companion object {
        const val TYPE = "lg"
    }
}
