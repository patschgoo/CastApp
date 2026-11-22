package io.github.patschgoo.castapp.mediaservers

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import io.github.patschgoo.castapp.BuildConfig
import io.github.patschgoo.castapp.network.Secrets
import io.github.patschgoo.castapp.service.ResumeRules
import io.github.patschgoo.castapp.service.WatchHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The saved Plex and Jellyfin / Emby accounts. Sign-in tokens are kept encrypted
 * ([Secrets]); the Plex account token (the plex.tv sign-in) is kept the same way.
 */
class MediaServers private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("media_servers", Context.MODE_PRIVATE)
    private val lock = Any()
    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<List<ServerAccount>> = _accounts.asStateFlow()

    private val _plexUser = MutableStateFlow(prefs.getString("plex_user", null))
    /** Who is signed in at plex.tv, or null. */
    val plexUser: StateFlow<String?> = _plexUser.asStateFlow()

    val info: ClientInfo = ClientInfo(
        deviceId = deviceId(),
        deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android",
        version = BuildConfig.VERSION_NAME,
    )

    private fun deviceId(): String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { id ->
        prefs.edit { putString("device_id", id) }
    }

    // ---- accounts ---------------------------------------------------------------------------------

    fun get(id: String): ServerAccount? = _accounts.value.firstOrNull { it.id == id }

    fun add(account: ServerAccount) {
        synchronized(lock) {
            _accounts.value = _accounts.value.filter { it.id != account.id } + account
            persist()
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            _accounts.value = _accounts.value.filter { it.id != id }
            persist()
        }
        forgetHistory(setOf(id))
    }

    /** The watch history keeps the addresses of what was played, and with them the sign-in token: drop those of a signed-out account. */
    private fun forgetHistory(accountIds: Set<String>) {
        val history = WatchHistory.get(appContext)
        history.entries.value
            .filter { e -> ItemRef.decode(e.item?.serverRef)?.accountId in accountIds }
            .forEach { history.remove(it.uri) }
    }

    fun client(account: ServerAccount): MediaServerClient =
        if (account.kind.isPlex) PlexClient(account, info) else JellyfinClient(account, info)

    fun clientFor(accountId: String): MediaServerClient? = get(accountId)?.let(::client)

    private fun load(): List<ServerAccount> = try {
        val a = JSONArray(prefs.getString("accounts", "[]"))
        a.objects().map { o ->
            ServerAccount(
                id = o.getString("id"),
                kind = ServerKind.valueOf(o.getString("kind")),
                name = o.optString("name"),
                baseUrl = o.optString("url"),
                token = Secrets.decrypt(o.optString("token")),
                userId = o.optString("userId"),
                userName = o.optString("userName"),
                serverId = o.optString("serverId"),
                trustAll = o.optBoolean("trustAll"),
                alternates = o.optJSONArray("alt")?.let { alt -> (0 until alt.length()).map { alt.getString(it) } }.orEmpty(),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist() {
        val a = JSONArray()
        for (s in _accounts.value) {
            a.put(
                JSONObject().put("id", s.id).put("kind", s.kind.name).put("name", s.name).put("url", s.baseUrl)
                    .put("token", Secrets.encrypt(s.token)).put("userId", s.userId).put("userName", s.userName)
                    .put("serverId", s.serverId).put("trustAll", s.trustAll).put("alt", JSONArray(s.alternates)),
            )
        }
        prefs.edit { putString("accounts", a.toString()) }
    }

    // ---- Jellyfin / Emby ----------------------------------------------------------------------------

    /** Signs in and saves the account (blocking). Signing in again replaces the old account of that server and user. */
    fun signInJellyfin(address: String, username: String, password: String, trustAll: Boolean): ServerAccount {
        val base = JellyfinAuth.normalizeAddress(address)
        if (base.isEmpty()) throw ServerHttpException(0, "No address")
        val account = JellyfinAuth.authenticate(base, username.trim(), password, info, trustAll, UUID.randomUUID().toString().take(8))
        val existing = _accounts.value.firstOrNull { it.kind == account.kind && it.serverId == account.serverId && it.userId == account.userId }
        val saved = if (existing != null) account.copy(id = existing.id) else account
        add(saved)
        return saved
    }

    // ---- Plex -------------------------------------------------------------------------------------------

    val plexToken: String? get() = Secrets.decrypt(prefs.getString("plex_token", "").orEmpty()).ifEmpty { null }

    val plexTv: PlexTv get() = PlexTv(info)

    fun savePlexSignIn(token: String, user: String?) {
        prefs.edit {
            putString("plex_token", Secrets.encrypt(token))
            putString("plex_user", user.orEmpty())
        }
        _plexUser.value = user.orEmpty()
    }

    /** Signs out of Plex: forgets the token and the servers that came with it. */
    fun signOutPlex() {
        prefs.edit {
            remove("plex_token")
            remove("plex_user")
        }
        _plexUser.value = null
        val plexIds = _accounts.value.filter { it.kind.isPlex }.map { it.id }.toSet()
        synchronized(lock) {
            _accounts.value = _accounts.value.filter { !it.kind.isPlex }
            persist()
        }
        forgetHistory(plexIds)
    }

    /**
     * Asks plex.tv for the servers of the signed-in account and finds, for each, an address that
     * answers (the home network first). Needs the internet; the only call that does.
     */
    fun refreshPlexServers(gdm: List<GdmServer> = emptyList()): List<ServerAccount> {
        val token = plexToken ?: throw ServerHttpException(401, "Not signed in")
        val resources = plexTv.resources(token)
        val found = java.util.concurrent.ConcurrentHashMap<String, ServerAccount>()
        val threads = resources.map { r ->
            Thread {
                val hosts = gdm.filter { it.machineId == r.machineId }.map { "${it.host}:${it.port}" }
                val addresses = r.addressesToTry(hosts)
                val working = addresses.firstOrNull { PlexClient.identify(it) != null }
                val base = working ?: addresses.firstOrNull() ?: return@Thread
                found[r.machineId] = ServerAccount(
                    id = "plex-" + r.machineId,
                    kind = ServerKind.PLEX,
                    name = r.name,
                    baseUrl = base,
                    token = r.accessToken ?: token,
                    userName = plexUser.value.orEmpty(),
                    serverId = r.machineId,
                    alternates = addresses.filter { it != base },
                )
            }.also { it.start() }
        }
        threads.forEach { it.join(20_000) }
        val list = resources.mapNotNull { found[it.machineId] }
        synchronized(lock) {
            _accounts.value = _accounts.value.filter { !it.kind.isPlex } + list
            persist()
        }
        return list
    }

    /** Makes sure a saved Plex server still answers at its address; tries the others if not. */
    fun connect(account: ServerAccount): ServerAccount {
        if (!account.kind.isPlex) return account
        if (PlexClient.identify(account.baseUrl, account.trustAll) != null) return account
        for (alt in account.alternates) {
            if (PlexClient.identify(alt, account.trustAll) != null) {
                val updated = account.copy(baseUrl = alt, alternates = (account.alternates - alt) + account.baseUrl)
                add(updated)
                return updated
            }
        }
        throw ServerHttpException(0, "No answer")
    }

    companion object {
        @Volatile private var instance: MediaServers? = null

        fun get(context: Context): MediaServers = instance ?: synchronized(this) {
            instance ?: MediaServers(context.applicationContext).also { instance = it }
        }

        /**
         * Puts what the server knows into the watch history, so the cast session resumes there
         * (and shows "Resumed at ... / Start over"): the server wins over what this phone remembers.
         * [resumeFirst]: false = "Start over" for the first item.
         */
        fun syncHistory(history: WatchHistory, plan: PlaybackPlan, resumeFirst: Boolean) {
            plan.entries.forEachIndexed { i, e ->
                val resumable = ResumeRules.resumeAt(e.resumeMs, e.durationMs, false) != null
                if (resumable && (i > 0 || resumeFirst)) history.record(e.item, e.resumeMs, e.durationMs, force = true)
                else if (history.entries.value.any { it.uri == e.item.uri }) history.remove(e.item.uri)
            }
        }
    }
}
