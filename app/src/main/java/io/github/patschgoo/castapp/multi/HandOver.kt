package io.github.patschgoo.castapp.multi

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.service.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** How the other device takes over a cast. */
enum class HandOverMode(val id: String) {
    /** It becomes the host: casts the same thing to the same screens, and this device stops. */
    TAKE_OVER("takeover"),

    /** This device keeps serving (files only it can read); the other one gets the controls. */
    CONTROL("control"),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** "<A> offers you <TVs>": everything the other device needs to carry on. */
data class HandOverOffer(
    val id: String,
    val fromId: String,
    val fromName: String,
    /** "http://host:port" of the offering device's peer server. */
    val fromBase: String,
    val screens: List<CastDevice>,
    /** "screen" (mirroring / sound) or "file". */
    val kind: String,
    val audioOnly: Boolean = false,
    val mode: HandOverMode = HandOverMode.TAKE_OVER,
    val items: List<MediaItem> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val title: String = "",
    /** The other device asked for it ("Ask to cast"). */
    val requested: Boolean = false,
) {
    val isScreen: Boolean get() = kind == KIND_SCREEN

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("fromId", fromId)
        .put("fromName", fromName)
        .put("fromBase", fromBase)
        .put("screens", JSONArray().apply { screens.forEach { put(it.toJson()) } })
        .put("kind", kind)
        .put("audioOnly", audioOnly)
        .put("mode", mode.id)
        .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        .put("index", index)
        .put("positionMs", positionMs)
        .put("title", title)
        .put("requested", requested)

    companion object {
        const val KIND_SCREEN = "screen"
        const val KIND_FILE = "file"

        fun fromJson(o: JSONObject): HandOverOffer? {
            val id = o.optString("id").ifEmpty { return null }
            val screensJson = o.optJSONArray("screens") ?: return null
            val screens = (0 until screensJson.length()).mapNotNull { i -> screensJson.optJSONObject(i)?.let(CastDevice::fromJson) }
            if (screens.isEmpty()) return null
            val kind = o.optString("kind").takeIf { it == KIND_SCREEN || it == KIND_FILE } ?: return null
            val items = o.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(MediaItem::fromJson) } }.orEmpty()
            val mode = HandOverMode.fromId(o.optString("mode")) ?: HandOverMode.TAKE_OVER
            // Taking over a file cast needs the play list; taking over the controls doesn't.
            if (kind == KIND_FILE && mode == HandOverMode.TAKE_OVER && items.isEmpty()) return null
            return HandOverOffer(
                id = id,
                fromId = o.optString("fromId"),
                fromName = o.optString("fromName").filter { !it.isISOControl() }.take(60).ifBlank { "CastApp" },
                fromBase = o.optString("fromBase"),
                screens = screens.take(MAX_SCREENS),
                kind = kind,
                audioOnly = o.optBoolean("audioOnly"),
                mode = mode,
                items = items,
                index = o.optInt("index").coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                positionMs = o.optLong("positionMs").coerceAtLeast(0),
                title = o.optString("title").take(120),
                requested = o.optBoolean("requested"),
            )
        }

        private const val MAX_SCREENS = 8

        /**
         * Files only this device can read (on the phone, or on a share it logged in to) can't be
         * taken over: the other device gets the controls instead. Web videos, NAS streams over
         * HTTP and TV channels move over (subtitles on this phone stay behind).
         */
        fun modeFor(kind: String, items: List<MediaItem>): HandOverMode = when {
            kind == KIND_SCREEN -> HandOverMode.TAKE_OVER
            items.isNotEmpty() && items.all { it.isWeb && !it.trustAll } -> HandOverMode.TAKE_OVER
            else -> HandOverMode.CONTROL
        }

        /**
         * What travels to the other device: subtitle files only this one can read are dropped, and
         * other languages' files are kept only for the item playing ([index]) and its neighbours
         * (the others keep the one they show). A WebDAV folder with 29 languages per episode would
         * outgrow what the peer server takes ([PeerServer.MAX_BODY]).
         */
        fun portable(items: List<MediaItem>, index: Int = 0): List<MediaItem> = items.mapIndexed { i, item ->
            val files = item.subtitleFiles
            val readable = files.filter { it.startsWith("http://") || it.startsWith("https://") }
            val kept = if (kotlin.math.abs(i - index) <= NEIGHBOURS) readable else readable.take(1)
            if (kept.size == files.size) item else item.copy(subtitleUri = kept.firstOrNull(), moreSubtitles = kept.drop(1))
        }

        /** Items before and after the one playing that keep all their subtitle files. */
        private const val NEIGHBOURS = 2

        /** What an offer may take of [PeerServer.MAX_BODY], leaving room for the rest of the request. */
        const val MAX_OFFER_BYTES = PeerServer.MAX_BODY - 16 * 1024
    }

    /** The size of the offer as sent. */
    fun sizeBytes(): Int = toJson().toString().toByteArray(Charsets.UTF_8).size

    /**
     * This offer small enough for the other device's peer server, where possible: if it is too
     * big, only the item playing keeps the subtitle files of other languages. Still too big (a very
     * long play list), the other device answers 413 and the user is told.
     */
    fun fitted(maxBytes: Int = MAX_OFFER_BYTES): HandOverOffer {
        if (items.isEmpty() || sizeBytes() <= maxBytes) return this
        return copy(items = items.mapIndexed { i, item -> if (i == index || item.moreSubtitles.isEmpty()) item else item.copy(moreSubtitles = emptyList()) })
    }
}

/** The offering device's side of a hand-over ("A"). */
class OutgoingHandOver(
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long the other device has to answer. */
    private val answerTimeoutMs: Long = 60_000,
    /** After a yes: how long the other device has to start casting (Android asks for its capture permission). */
    private val completeTimeoutMs: Long = 90_000,
) {
    sealed interface State {
        data object Idle : State
        data class Offered(val offer: HandOverOffer, val to: String, val since: Long) : State
        data class Accepted(val offer: HandOverOffer, val to: String, val since: Long) : State
        data class Completed(val offer: HandOverOffer) : State
        data class Declined(val offer: HandOverOffer) : State
        data class TimedOut(val offer: HandOverOffer) : State
        data class Failed(val offer: HandOverOffer) : State
    }

    enum class Answer { ACCEPTED, DECLINED, UNKNOWN }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Starts an offer to the device at [toAddress]; false while another one is running. */
    @Synchronized
    fun offer(offer: HandOverOffer, toAddress: String): Boolean {
        tick()
        if (_state.value is State.Offered || _state.value is State.Accepted) return false
        _state.value = State.Offered(offer, toAddress, clock())
        return true
    }

    /** The other device's answer; only counts from the device the offer went to. */
    @Synchronized
    fun answer(id: String, fromAddress: String, accept: Boolean): Answer {
        tick()
        val s = _state.value as? State.Offered ?: return Answer.UNKNOWN
        if (s.offer.id != id || s.to != fromAddress) return Answer.UNKNOWN
        return if (accept) {
            _state.value = State.Accepted(s.offer, s.to, clock())
            Answer.ACCEPTED
        } else {
            _state.value = State.Declined(s.offer)
            Answer.DECLINED
        }
    }

    /** The other device is casting now ([ok]), or couldn't start. Returns the offer it was about. */
    @Synchronized
    fun done(id: String, fromAddress: String, ok: Boolean): HandOverOffer? {
        tick()
        val s = _state.value as? State.Accepted ?: return null
        if (s.offer.id != id || s.to != fromAddress) return null
        _state.value = if (ok) State.Completed(s.offer) else State.Failed(s.offer)
        return s.offer
    }

    @Synchronized
    fun cancel() {
        val s = _state.value
        if (s is State.Offered || s is State.Accepted) _state.value = State.Idle
    }

    /** Lets offers run out; returns the state after that. */
    @Synchronized
    fun tick(): State {
        val now = clock()
        when (val s = _state.value) {
            is State.Offered -> if (now - s.since > answerTimeoutMs) _state.value = State.TimedOut(s.offer)
            is State.Accepted -> if (now - s.since > completeTimeoutMs) _state.value = State.TimedOut(s.offer)
            else -> Unit
        }
        return _state.value
    }

    /** Back to idle after the outcome was shown. */
    @Synchronized
    fun reset() {
        val s = _state.value
        if (s !is State.Offered && s !is State.Accepted) _state.value = State.Idle
    }

    val busy: Boolean get() = tick().let { it is State.Offered || it is State.Accepted }
}

/** The receiving device's side ("B"): at most one offer waits for the owner's answer. */
class IncomingHandOver(
    private val clock: () -> Long = System::currentTimeMillis,
    private val lifetimeMs: Long = 60_000,
    /** After "Ask to cast": how long an offer from that device is taken without asking again. */
    private val requestLifetimeMs: Long = 120_000,
) {
    data class Pending(val offer: HandOverOffer, val fromAddress: String, val expiresAt: Long)

    enum class Receipt { PROMPT, AUTO_ACCEPT, BUSY, INVALID }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()
    private val asked = HashMap<String, Long>()

    /** This device asked [address] to hand over: its offer needs no second yes. */
    @Synchronized
    fun expectOfferFrom(address: String) {
        asked[address] = clock() + requestLifetimeMs
    }

    @Synchronized
    fun forgetRequest(address: String) {
        asked.remove(address)
    }

    @Synchronized
    fun hasRequestTo(address: String): Boolean {
        prune()
        return asked.containsKey(address)
    }

    /**
     * An offer arrived from [fromAddress]. It must come from the device it names (the answers go
     * back there) and only one offer waits at a time.
     */
    @Synchronized
    fun receive(offer: HandOverOffer, fromAddress: String): Receipt {
        prune()
        if (baseHost(offer.fromBase) != fromAddress) return Receipt.INVALID
        val current = _pending.value
        if (current != null && current.offer.id != offer.id) return Receipt.BUSY
        if (asked.remove(fromAddress) != null) return Receipt.AUTO_ACCEPT
        _pending.value = Pending(offer, fromAddress, clock() + lifetimeMs)
        return Receipt.PROMPT
    }

    /** The owner said yes: the offer, or null if it ran out meanwhile. */
    @Synchronized
    fun accept(id: String): Pending? = take(id)

    @Synchronized
    fun decline(id: String): Pending? = take(id)

    private fun take(id: String): Pending? {
        prune()
        val p = _pending.value ?: return null
        if (p.offer.id != id) return null
        _pending.value = null
        return p
    }

    /** Lets the offer run out; the prompt calls this now and then. */
    @Synchronized
    fun prune() {
        val now = clock()
        if (_pending.value?.let { it.expiresAt <= now } == true) _pending.value = null
        asked.entries.removeAll { it.value <= now }
    }

    companion object {
        fun baseHost(base: String): String? = try {
            java.net.URI(base).host?.trim('[', ']')
        } catch (_: Exception) {
            null
        }
    }
}

/** "<B> wants to cast": requests waiting for the casting device's owner. */
class CastRequests(private val clock: () -> Long = System::currentTimeMillis, private val lifetimeMs: Long = 60_000) {
    data class Request(val id: String, val name: String, val base: String, val address: String, val expiresAt: Long)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    @Synchronized
    fun add(name: String, base: String, address: String): Request? {
        prune()
        if (IncomingHandOver.baseHost(base) != address) return null
        _pending.value?.let { if (it.address == address) return it else return null }
        return Request(java.util.UUID.randomUUID().toString(), name.filter { !it.isISOControl() }.take(60), base, address, clock() + lifetimeMs)
            .also { _pending.value = it }
    }

    @Synchronized
    fun take(id: String): Request? {
        prune()
        val r = _pending.value ?: return null
        if (r.id != id) return null
        _pending.value = null
        return r
    }

    @Synchronized
    fun prune() {
        if (_pending.value?.let { it.expiresAt <= clock() } == true) _pending.value = null
    }
}
