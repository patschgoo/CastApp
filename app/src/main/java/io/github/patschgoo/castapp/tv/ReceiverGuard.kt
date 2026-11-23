package io.github.patschgoo.castapp.tv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Who may cast to this receiver (a CastApp TV, or a phone that "receives on this phone"). */
enum class ProtectionMode(val id: String) {
    /** Every device on the network (how DLNA normally works). */
    ANYONE("anyone"),

    /** An unknown sender's first cast is confirmed on the receiver. */
    ASK("ask"),

    /** Only CastApp phones that typed in the receiver's code once. */
    PAIRED("paired"),
    ;

    companion object {
        fun fromId(id: String?): ProtectionMode? = entries.firstOrNull { it.id == id }
    }
}

/** How much a request can do; decides whether a sender has to be approved first. */
enum class Access {
    /** Descriptions, capabilities, volume: nothing private. */
    PUBLIC,

    /** What is playing right now (title, address). */
    STATUS,

    /** Pause, stop, seek, volume changes... */
    CONTROL,

    /** Start something new on the receiver. This is what shows the "Allow?" prompt. */
    START,
}

enum class Decision { ALLOW_ONCE, ALLOW_ALWAYS, BLOCK }

/** The device sending a request, as far as the receiver can tell (a LAN has no real identity). */
class Sender(
    val address: String,
    val userAgent: String = "",
    /** Name the sender gave itself (CastApp sends its phone's model), may be empty. */
    val name: String = "",
    val token: String? = null,
    val local: Boolean = true,
) {
    /** Decisions are remembered per address and User-Agent, so another app on the same phone is asked again. */
    val key: String = if (userAgent.isBlank()) address else "$address|${userAgent.trim()}"

    val label: String get() = friendlyName(name, userAgent, address)

    companion object {
        private val GENERIC = setOf("linux", "upnp", "android", "mozilla", "dalvik", "java", "okhttp", "curl", "http", "ubuntu")
        private val MODEL = Regex("""Android [^;)]*;\s*([^;)]+?)(?:\s+Build/[^;)]*)?[;)]""")

        fun friendlyName(name: String, userAgent: String, address: String): String {
            val own = name.filter { !it.isISOControl() }.trim()
            if (own.isNotEmpty()) return own.take(40)
            val ua = userAgent.filter { !it.isISOControl() }.trim()
            MODEL.find(ua)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(40) }
            for (token in ua.split(' ', ';', ',')) {
                val product = token.substringBefore('/').trim()
                if (product.length >= 2 && product.lowercase() !in GENERIC && product.none { it == '(' || it == ')' }) return product.take(40)
            }
            return address
        }
    }
}

sealed interface Verdict {
    data object Allowed : Verdict

    /** [code] is the UPnP error code the sender gets (606 = action not authorized). */
    data class Denied(val code: Int, val message: String) : Verdict
}

sealed interface PairOutcome {
    /** This receiver doesn't ask for a code (another protection mode). */
    data class NotRequired(val mode: ProtectionMode) : PairOutcome

    /** Already paired (valid token) or approved by the owner for now. */
    data object Paired : PairOutcome

    data class Issued(val token: String) : PairOutcome
    data object CodeRequired : PairOutcome
    data class WrongCode(val attemptsLeft: Int) : PairOutcome
    data class Locked(val retryInSeconds: Int) : PairOutcome
}

/** A prompt waiting for the receiver's owner. */
data class PendingCast(val id: Long, val key: String, val name: String, val address: String, val expiresAt: Long)

/** A phone asked to pair: the receiver shows the code for a while. */
data class PairingNotice(val name: String, val address: String, val expiresAt: Long)

data class KnownSender(val key: String, val name: String, val address: String, val since: Long)

data class PairedPhone(val hash: String, val name: String, val since: Long)

/** Everything the receiver remembers about senders. */
data class GuardData(
    val mode: ProtectionMode? = null,
    val code: String? = null,
    val allowed: List<KnownSender> = emptyList(),
    val blocked: List<KnownSender> = emptyList(),
    val paired: List<PairedPhone> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("mode", mode?.id ?: "")
        .put("code", code ?: "")
        .put("allowed", JSONArray().apply { allowed.forEach { put(it.toJson()) } })
        .put("blocked", JSONArray().apply { blocked.forEach { put(it.toJson()) } })
        .put(
            "paired",
            JSONArray().apply {
                paired.forEach { put(JSONObject().put("hash", it.hash).put("name", it.name).put("since", it.since)) }
            },
        )
        .toString()

    companion object {
        fun fromJson(text: String?): GuardData {
            if (text.isNullOrBlank()) return GuardData()
            return try {
                val o = JSONObject(text)
                fun known(a: JSONArray?): List<KnownSender> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
                    val k = a!!.optJSONObject(i) ?: return@mapNotNull null
                    val key = k.optString("key").ifEmpty { return@mapNotNull null }
                    KnownSender(key, k.optString("name"), k.optString("address"), k.optLong("since"))
                }
                val pairedArray = o.optJSONArray("paired")
                GuardData(
                    mode = ProtectionMode.fromId(o.optString("mode")),
                    code = o.optString("code").takeIf { it.isNotEmpty() },
                    allowed = known(o.optJSONArray("allowed")),
                    blocked = known(o.optJSONArray("blocked")),
                    paired = (0 until (pairedArray?.length() ?: 0)).mapNotNull { i ->
                        val p = pairedArray!!.optJSONObject(i) ?: return@mapNotNull null
                        val hash = p.optString("hash").ifEmpty { return@mapNotNull null }
                        PairedPhone(hash, p.optString("name"), p.optLong("since"))
                    },
                )
            } catch (_: Exception) {
                GuardData()
            }
        }

        private fun KnownSender.toJson() = JSONObject().put("key", key).put("name", name).put("address", address).put("since", since)
    }
}

interface GuardStore {
    fun load(): GuardData
    fun save(data: GuardData)
}

class MemoryGuardStore(var data: GuardData = GuardData()) : GuardStore {
    override fun load() = data
    override fun save(data: GuardData) {
        this.data = data
    }
}

/**
 * The receiver's gatekeeper: decides whether a request from the network may do
 * what it asks. Pure logic (no Android classes), called from the DLNA server's
 * worker threads and from the receiver's UI.
 */
class ReceiverGuard(
    private val store: GuardStore,
    private val defaultMode: ProtectionMode,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    /** How long a request waits for the owner. CastApp's phones wait 10 s for SetAVTransportURI (8 s for a swap), other apps less. */
    private val askTimeoutMs: Long = 6_000,
    /** How long the prompt stays on screen (a retry within this time succeeds once allowed). */
    private val promptLifetimeMs: Long = 30_000,
) {
    private class Waiter(val info: PendingCast, val future: CompletableFuture<Decision?> = CompletableFuture())

    private val lock = Any()
    private val _data = MutableStateFlow(store.load())
    val data: StateFlow<GuardData> = _data.asStateFlow()

    private val waiters = ArrayList<Waiter>()
    private val _pending = MutableStateFlow<List<PendingCast>>(emptyList())
    /** Prompts waiting for an answer ("Allow Pixel 8 to cast?"). */
    val pending: StateFlow<List<PendingCast>> = _pending.asStateFlow()

    private val _pairing = MutableStateFlow<PairingNotice?>(null)
    /** Set while a phone is asking to pair, so the receiver can show its code. */
    val pairing: StateFlow<PairingNotice?> = _pairing.asStateFlow()

    /** Called (on a worker thread) when a new prompt or pairing request needs the owner's attention. */
    @Volatile var onAttention: (() -> Unit)? = null

    private val temporary = HashMap<String, Long>()
    private val expectedHosts = HashMap<String, Long>()
    private var nextId = 1L
    private var failures = 0
    private var lockedUntil = 0L

    val mode: ProtectionMode get() = _data.value.mode ?: defaultMode

    val isCustomMode: Boolean get() = _data.value.mode != null

    /** The code a phone has to type in "Only paired phones" mode (created on first use). */
    val code: String
        get() = _data.value.code ?: synchronized(lock) { _data.value.code ?: newCodeLocked() }

    fun setMode(mode: ProtectionMode) {
        synchronized(lock) {
            update { it.copy(mode = mode) }
            if (mode == ProtectionMode.PAIRED && _data.value.code == null) newCodeLocked()
            temporary.clear()
        }
        if (mode == ProtectionMode.ANYONE) cancelPrompts()
    }

    /** Shows a different code (the old one is dead at once). */
    fun newCode(): String = synchronized(lock) { newCodeLocked() }

    // ---- checking requests ---------------------------------------------------------------------

    fun authorize(sender: Sender, access: Access): Verdict {
        val mode = mode
        if (mode == ProtectionMode.ANYONE || access == Access.PUBLIC) return Verdict.Allowed
        if (!sender.local) return Verdict.Denied(CODE_NOT_AUTHORIZED, REMOTE)
        val now = clock()
        synchronized(lock) {
            prune(now)
            if (isPaired(sender.token)) return Verdict.Allowed
            if (expectedHosts.containsKey(sender.address)) return Verdict.Allowed
            if (mode == ProtectionMode.PAIRED) {
                return Verdict.Denied(CODE_NOT_AUTHORIZED, PAIRING_REQUIRED)
            }
            // Ask first
            if (_data.value.blocked.any { it.key == sender.key }) return Verdict.Denied(CODE_NOT_AUTHORIZED, BLOCKED)
            if (_data.value.allowed.any { it.key == sender.key }) return Verdict.Allowed
            val until = temporary[sender.key]
            if (until != null && until > now) {
                temporary[sender.key] = now + TEMPORARY_MS
                return Verdict.Allowed
            }
            if (access == Access.STATUS) return Verdict.Allowed
            if (access == Access.CONTROL) return Verdict.Denied(CODE_NOT_AUTHORIZED, NOT_ALLOWED)
        }
        return ask(sender)
    }

    private fun ask(sender: Sender): Verdict {
        val now = clock()
        var created = false
        val waiter = synchronized(lock) {
            waiters.firstOrNull { it.info.key == sender.key }
                ?: if (waiters.size >= MAX_PENDING) {
                    return Verdict.Denied(CODE_NOT_AUTHORIZED, BUSY)
                } else {
                    created = true
                    Waiter(PendingCast(nextId++, sender.key, sender.label, sender.address, now + promptLifetimeMs)).also {
                        waiters += it
                        publishPending()
                    }
                }
        }
        if (created) onAttention?.invoke()
        val decision = try {
            waiter.future.get(askTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }
        return when (decision) {
            Decision.ALLOW_ONCE, Decision.ALLOW_ALWAYS -> Verdict.Allowed
            Decision.BLOCK -> Verdict.Denied(CODE_NOT_AUTHORIZED, BLOCKED)
            null -> Verdict.Denied(CODE_NOT_AUTHORIZED, WAITING)
        }
    }

    /** The owner's answer to a prompt. */
    fun answer(id: Long, decision: Decision) {
        val now = clock()
        val waiter = synchronized(lock) {
            val w = waiters.firstOrNull { it.info.id == id } ?: return
            waiters.remove(w)
            val known = KnownSender(w.info.key, w.info.name, w.info.address, now)
            when (decision) {
                Decision.ALLOW_ONCE -> temporary[w.info.key] = now + TEMPORARY_MS
                Decision.ALLOW_ALWAYS -> update { d ->
                    d.copy(allowed = d.allowed.filter { it.key != known.key } + known, blocked = d.blocked.filter { it.key != known.key })
                }
                Decision.BLOCK -> {
                    temporary.remove(w.info.key)
                    update { d ->
                        d.copy(blocked = d.blocked.filter { it.key != known.key } + known, allowed = d.allowed.filter { it.key != known.key })
                    }
                }
            }
            publishPending()
            w
        }
        waiter.future.complete(decision)
    }

    /** Drops prompts and notices that ran out; the prompt screen calls this now and then. */
    fun prune() {
        synchronized(lock) { prune(clock()) }
    }

    private fun prune(now: Long) {
        val expired = waiters.filter { it.info.expiresAt <= now }
        if (expired.isNotEmpty()) {
            waiters.removeAll(expired.toSet())
            publishPending()
            expired.forEach { it.future.complete(null) }
        }
        temporary.entries.removeAll { it.value <= now }
        expectedHosts.entries.removeAll { it.value <= now }
        if (_pairing.value?.let { it.expiresAt <= now } == true) _pairing.value = null
    }

    private fun cancelPrompts() {
        val all = synchronized(lock) {
            val copy = waiters.toList()
            waiters.clear()
            publishPending()
            copy
        }
        all.forEach { it.future.complete(null) }
    }

    private fun publishPending() {
        _pending.value = waiters.map { it.info }
    }

    /** The user started a mirror swap with [host] themselves: don't ask again for what that device sends next. */
    fun expectSwap(host: String, ttlMs: Long = 120_000) {
        synchronized(lock) { expectedHosts[host] = clock() + ttlMs }
    }

    // ---- pairing -------------------------------------------------------------------------------

    fun pair(sender: Sender, name: String, code: String?): PairOutcome {
        val mode = mode
        if (mode != ProtectionMode.PAIRED) return PairOutcome.NotRequired(mode)
        val now = clock()
        val outcome: PairOutcome
        var notice = false
        synchronized(lock) {
            prune(now)
            if (isPaired(sender.token) || expectedHosts.containsKey(sender.address)) return PairOutcome.Paired
            if (lockedUntil > now) return PairOutcome.Locked(((lockedUntil - now + 999) / 1000).toInt())
            val current = _data.value.code ?: newCodeLocked()
            if (code.isNullOrBlank()) {
                _pairing.value = PairingNotice(Sender.friendlyName(name, sender.userAgent, sender.address), sender.address, now + PAIRING_NOTICE_MS)
                notice = true
                outcome = PairOutcome.CodeRequired
            } else if (MessageDigest.isEqual(code.trim().toByteArray(), current.toByteArray())) {
                val token = randomToken()
                val phone = PairedPhone(hash(token), Sender.friendlyName(name, sender.userAgent, sender.address), now)
                update { it.copy(paired = (it.paired + phone).takeLast(MAX_PAIRED)) }
                failures = 0
                newCodeLocked() // a code works once
                _pairing.value = null
                outcome = PairOutcome.Issued(token)
            } else {
                failures++
                if (failures >= MAX_FAILURES) {
                    failures = 0
                    lockedUntil = now + LOCK_MS
                    newCodeLocked() // whoever was guessing has to start over
                    outcome = PairOutcome.Locked((LOCK_MS / 1000).toInt())
                } else {
                    outcome = PairOutcome.WrongCode(MAX_FAILURES - failures)
                }
            }
        }
        if (notice) onAttention?.invoke()
        return outcome
    }

    // ---- the list of remembered senders --------------------------------------------------------

    fun forgetAllowed(key: String) {
        synchronized(lock) { temporary.remove(key) }
        update { d -> d.copy(allowed = d.allowed.filter { it.key != key }) }
    }

    fun forgetBlocked(key: String) = update { d -> d.copy(blocked = d.blocked.filter { it.key != key }) }

    fun forgetPaired(hash: String) = update { d -> d.copy(paired = d.paired.filter { it.hash != hash }) }

    fun forgetAll() {
        synchronized(lock) { temporary.clear() }
        update { it.copy(allowed = emptyList(), blocked = emptyList(), paired = emptyList()) }
    }

    // ---- internals -----------------------------------------------------------------------------

    private fun update(transform: (GuardData) -> GuardData) {
        synchronized(lock) {
            val next = transform(_data.value)
            _data.value = next
            store.save(next)
        }
    }

    private fun newCodeLocked(): String {
        val code = String.format(java.util.Locale.ROOT, "%04d", random.nextInt(10_000))
        update { it.copy(code = code) }
        return code
    }

    private fun isPaired(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val h = hash(token).toByteArray()
        var found = false
        for (p in _data.value.paired) if (MessageDigest.isEqual(p.hash.toByteArray(), h)) found = true
        return found
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val CODE_NOT_AUTHORIZED = 606
        const val TOKEN_HEADER = "X-CastApp-Token"
        const val NAME_HEADER = "X-CastApp-Name"

        /** A marker the phone looks for to tell "pair again" from other errors. */
        const val PAIRING_REQUIRED = "Pairing required: this receiver only accepts paired phones. In CastApp, pick this receiver and type in the code it shows."
        const val NOT_ALLOWED = "Not allowed: this sender hasn't been approved on the receiver yet."
        const val BLOCKED = "Blocked: the owner of this receiver doesn't allow this sender."
        const val WAITING = "Waiting for permission on the receiver. Allow it there, then try again."
        const val BUSY = "The receiver is busy asking about other senders. Try again in a moment."
        const val REMOTE = "Only devices on the local network may cast here."

        private const val TEMPORARY_MS = 30 * 60_000L
        private const val MAX_PENDING = 5
        private const val MAX_FAILURES = 5
        private const val LOCK_MS = 60_000L
        private const val MAX_PAIRED = 50
        private const val PAIRING_NOTICE_MS = 2 * 60_000L

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

        /** What a SOAP action needs. */
        fun accessFor(service: String, action: String): Access = when (service) {
            "ConnectionManager" -> Access.PUBLIC
            "RenderingControl" -> if (action.startsWith("Get") || action == "ListPresets") Access.PUBLIC else Access.CONTROL
            "AVTransport" -> when (action) {
                // Queuing the next item starts it later without asking again: the same as starting it.
                "SetAVTransportURI", "SetNextAVTransportURI" -> Access.START
                "GetPositionInfo", "GetMediaInfo" -> Access.STATUS
                "GetTransportInfo", "GetTransportSettings", "GetDeviceCapabilities", "GetCurrentTransportActions" -> Access.PUBLIC
                else -> Access.CONTROL
            }
            else -> Access.CONTROL
        }
    }
}
