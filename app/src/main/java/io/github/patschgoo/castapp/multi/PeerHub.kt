package io.github.patschgoo.castapp.multi

import io.github.patschgoo.castapp.tv.Access
import io.github.patschgoo.castapp.tv.ProtectionMode
import io.github.patschgoo.castapp.tv.ReceiverGuard
import io.github.patschgoo.castapp.tv.Sender
import io.github.patschgoo.castapp.tv.Verdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** What this device is casting, as other CastApp devices see it. */
data class CastInfo(
    /** [HandOverOffer.KIND_SCREEN] or [HandOverOffer.KIND_FILE]. */
    val kind: String,
    val title: String,
    val screens: List<String>,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = false,
    val live: Boolean = false,
    val audioOnly: Boolean = false,
)

/** What a viewer who joins plays. */
data class JoinMedia(
    val url: String,
    val mimeType: String,
    val title: String,
    /** The mirroring stream: play with minimal buffering. */
    val lowLatency: Boolean,
    val live: Boolean,
    val subtitleUrl: String? = null,
)

/** The cast running on this device (bridged to the casting service on Android, faked in tests). */
interface LocalCast {
    fun info(): CastInfo?

    /** The stream for a viewer at [remote]; null if there is nothing a viewer could play. */
    fun joinMedia(remote: InetAddress?): JoinMedia?

    fun timeline(): Timeline?

    /** A command from a device that took over the controls: play, pause, toggle, seek, next, previous, stop. */
    fun control(action: String, positionMs: Long): Boolean
}

/** Things that need this device's owner (prompts) or the casting service. Called from network threads. */
interface PeerEvents {
    fun offerReceived(pending: IncomingHandOver.Pending, auto: Boolean) {}
    fun offerAnswered(offer: HandOverOffer, accepted: Boolean) {}
    fun handOverDone(offer: HandOverOffer, ok: Boolean) {}
    fun castRequested(request: CastRequests.Request) {}
    fun requestDeclined(fromName: String) {}
}

/** Who may join this device's cast. */
enum class JoinPolicy(val id: String) {
    ASK("ask"),
    ALLOW("allow"),
    OFF("off"),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** This device as the others see it: role is "phone", "tablet" or "tv". */
data class SelfInfo(val id: String, val name: String, val role: String)

/**
 * The logic behind the peer server: the session summary, joining (with "Allow <name> to
 * watch?" through a [ReceiverGuard] in "ask first" mode), viewers, hand-over and remote control.
 * Pure Kotlin, so it is tested over real HTTP on the JVM.
 */
class PeerHub(
    private val self: () -> SelfInfo,
    private val cast: LocalCast,
    private val events: PeerEvents,
    /** Decides who may watch: "ask first" by default; "anyone" when the owner allows everybody. */
    val joinGuard: ReceiverGuard,
    private val clock: () -> Long = System::currentTimeMillis,
    private val viewerTimeoutMs: Long = 20_000,
) {
    private class Viewer(val name: String, @Volatile var lastSeen: Long)

    val outgoing = OutgoingHandOver(clock)
    val incoming = IncomingHandOver(clock)
    val requests = CastRequests(clock)

    private val viewers = ConcurrentHashMap<String, Viewer>()
    private val controlTokens: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val random = SecureRandom()

    private val _watchers = MutableStateFlow(0)
    /** How many devices are watching this cast right now. */
    val watchers: StateFlow<Int> = _watchers.asStateFlow()

    @Volatile var policy: JoinPolicy = JoinPolicy.ASK
        set(value) {
            field = value
            joinGuard.setMode(if (value == JoinPolicy.ALLOW) ProtectionMode.ANYONE else ProtectionMode.ASK)
        }

    fun handle(req: PeerRequest): PeerResponse = when {
        req.method == "GET" && req.path == "/castapp/session" -> PeerResponse.json(200, sessionJson())
        req.method == "GET" && req.path == "/castapp/timeline" -> timelineResponse(req.query["viewer"])
        req.method != "POST" -> PeerResponse.error(404, "not_found")
        req.path == "/castapp/join" -> join(req)
        req.path == "/castapp/leave" -> {
            viewers.remove(req.json.optString("viewer"))
            publishWatchers()
            PeerResponse.ok()
        }
        req.path == "/castapp/handover/offer" -> offer(req)
        req.path == "/castapp/handover/answer" -> answer(req)
        req.path == "/castapp/handover/done" -> done(req)
        req.path == "/castapp/handover/request" -> request(req)
        req.path == "/castapp/handover/declined" -> declined(req)
        req.path == "/castapp/control" -> control(req)
        else -> PeerResponse.error(404, "not_found")
    }

    // ---- what is cast here ----------------------------------------------------------------------

    fun sessionJson(): JSONObject {
        val me = self()
        val info = cast.info()
        val o = JSONObject()
            .put("v", VERSION)
            .put("id", me.id)
            .put("name", me.name)
            .put("role", me.role)
            .put("casting", info != null)
        if (info != null) {
            o.put("kind", info.kind)
                .put("title", info.title)
                .put("screens", JSONArray(info.screens))
                .put("joinable", policy != JoinPolicy.OFF)
                .put("policy", policy.id)
                .put("watchers", watcherCount())
                .put("positionMs", info.positionMs)
                .put("durationMs", info.durationMs)
                .put("paused", info.paused)
                .put("live", info.live)
                .put("audioOnly", info.audioOnly)
        }
        return o
    }

    private fun timelineResponse(viewer: String?): PeerResponse {
        if (viewer != null) viewers[viewer]?.let { it.lastSeen = clock(); publishWatchers() }
        val t = cast.timeline() ?: return PeerResponse.json(200, JSONObject().put("casting", cast.info() != null))
        return PeerResponse.json(200, t.toJson().put("casting", true))
    }

    // ---- joining --------------------------------------------------------------------------------

    private fun join(req: PeerRequest): PeerResponse {
        if (cast.info() == null) return PeerResponse.error(409, "not_casting", "Nothing is being cast here any more")
        if (policy == JoinPolicy.OFF) return PeerResponse.error(403, "off", "Joining is turned off on this device")
        val name = req.json.optString("name").ifBlank { req.headers[ReceiverGuard.NAME_HEADER.lowercase()].orEmpty() }
        val sender = Sender(
            address = req.remoteAddress,
            userAgent = req.headers["user-agent"].orEmpty().take(200),
            name = name.take(60),
            token = req.headers[ReceiverGuard.TOKEN_HEADER.lowercase()],
            local = true,
        )
        val verdict = joinGuard.authorize(sender, Access.START)
        if (verdict is Verdict.Denied) {
            val error = when (verdict.message) {
                ReceiverGuard.WAITING -> "waiting"
                ReceiverGuard.BLOCKED -> "blocked"
                ReceiverGuard.BUSY -> "busy"
                else -> "denied"
            }
            return PeerResponse.error(if (error == "waiting" || error == "busy") 202 else 403, error, verdict.message)
        }
        val media = cast.joinMedia(req.remote) ?: return PeerResponse.error(409, "not_joinable", "This cast can't be watched elsewhere")
        val id = randomToken(8)
        viewers[id] = Viewer(sender.label, clock())
        publishWatchers()
        return PeerResponse.json(
            200,
            JSONObject()
                .put("viewer", id)
                .put("url", media.url)
                .put("mime", media.mimeType)
                .put("title", media.title)
                .put("lowLatency", media.lowLatency)
                .put("live", media.live)
                .put("subtitles", media.subtitleUrl ?: "")
                .put("timeline", "/castapp/timeline"),
        )
    }

    fun watcherCount(): Int {
        val now = clock()
        viewers.entries.removeAll { now - it.value.lastSeen > viewerTimeoutMs }
        return viewers.size
    }

    private fun publishWatchers() {
        _watchers.value = watcherCount()
    }

    /** Drops viewers that stopped asking for the timeline (called now and then). */
    fun prune() = publishWatchers()

    /** The cast here ended: viewers and remote controls are gone with it. */
    fun castEnded() {
        viewers.clear()
        controlTokens.clear()
        outgoing.cancel()
        publishWatchers()
    }

    // ---- hand-over ------------------------------------------------------------------------------

    /** B: "<A> offers you <TVs>". */
    private fun offer(req: PeerRequest): PeerResponse {
        val offer = HandOverOffer.fromJson(req.json) ?: return PeerResponse.error(400, "bad_offer")
        return when (incoming.receive(offer, req.remoteAddress)) {
            IncomingHandOver.Receipt.INVALID -> PeerResponse.error(403, "invalid", "An offer must come from the device that makes it")
            IncomingHandOver.Receipt.BUSY -> PeerResponse.error(409, "busy", "Another offer is waiting for an answer")
            IncomingHandOver.Receipt.AUTO_ACCEPT -> {
                events.offerReceived(IncomingHandOver.Pending(offer, req.remoteAddress, clock()), auto = true)
                PeerResponse.json(202, JSONObject().put("id", offer.id).put("auto", true))
            }
            IncomingHandOver.Receipt.PROMPT -> {
                incoming.pending.value?.let { events.offerReceived(it, auto = false) }
                PeerResponse.json(202, JSONObject().put("id", offer.id))
            }
        }
    }

    /** A: B said yes or no. A yes to "control" hands out the remote-control token. */
    private fun answer(req: PeerRequest): PeerResponse {
        val id = req.json.optString("id")
        val accept = req.json.optBoolean("accept")
        val offer = (outgoing.state.value as? OutgoingHandOver.State.Offered)?.offer
        return when (outgoing.answer(id, req.remoteAddress, accept)) {
            OutgoingHandOver.Answer.UNKNOWN -> PeerResponse.error(404, "unknown_offer", "That offer is no longer open")
            OutgoingHandOver.Answer.DECLINED -> {
                offer?.let { events.offerAnswered(it, false) }
                PeerResponse.ok()
            }
            OutgoingHandOver.Answer.ACCEPTED -> {
                val o = JSONObject().put("ok", true)
                if (offer?.mode == HandOverMode.CONTROL) {
                    val token = randomToken(16)
                    controlTokens += token
                    o.put("token", token)
                    // Nothing more to wait for: this device keeps casting, the other one has the controls.
                    outgoing.done(id, req.remoteAddress, ok = true)
                }
                offer?.let { events.offerAnswered(it, true) }
                PeerResponse.json(200, o)
            }
        }
    }

    /** A: B is casting now (or gave up): A stops (or carries on). */
    private fun done(req: PeerRequest): PeerResponse {
        val ok = req.json.optBoolean("ok", true)
        val offer = outgoing.done(req.json.optString("id"), req.remoteAddress, ok) ?: return PeerResponse.error(404, "unknown_offer")
        events.handOverDone(offer, ok)
        return PeerResponse.ok()
    }

    /** A: "<B> wants to cast. Hand over?" */
    private fun request(req: PeerRequest): PeerResponse {
        if (cast.info() == null) return PeerResponse.error(409, "not_casting", "Nothing is being cast here")
        val r = requests.add(req.json.optString("name"), req.json.optString("base"), req.remoteAddress)
            ?: return PeerResponse.error(409, "busy", "Another device is already asking")
        events.castRequested(r)
        return PeerResponse.json(202, JSONObject().put("id", r.id))
    }

    /** B: the device B asked said no. */
    private fun declined(req: PeerRequest): PeerResponse {
        if (!incoming.hasRequestTo(req.remoteAddress)) return PeerResponse.error(404, "unknown_request")
        incoming.forgetRequest(req.remoteAddress)
        events.requestDeclined(req.json.optString("name"))
        return PeerResponse.ok()
    }

    private fun control(req: PeerRequest): PeerResponse {
        val token = req.json.optString("token")
        if (token.isEmpty() || token !in controlTokens) return PeerResponse.error(401, "not_allowed", "Take over the controls first")
        val action = req.json.optString("action")
        if (action !in CONTROL_ACTIONS) return PeerResponse.error(400, "unknown_action")
        if (action == "release") {
            controlTokens.remove(token)
            return PeerResponse.ok()
        }
        return if (cast.control(action, req.json.optLong("positionMs"))) PeerResponse.ok() else PeerResponse.error(409, "not_casting")
    }

    private fun randomToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val VERSION = 1
        val CONTROL_ACTIONS = setOf("play", "pause", "toggle", "seek", "rewind", "forward", "next", "previous", "stop", "release")
    }
}
