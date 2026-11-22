package io.github.patschgoo.castapp.multi

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import io.github.patschgoo.castapp.ProjectionActivity
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.service.MediaCastSession
import io.github.patschgoo.castapp.tv.PrefsGuardStore
import io.github.patschgoo.castapp.tv.ProtectionMode
import io.github.patschgoo.castapp.tv.ReceiverGuard
import io.github.patschgoo.castapp.tv.TvMedia
import io.github.patschgoo.castapp.tv.TvMediaKind
import io.github.patschgoo.castapp.tv.TvReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CastApp devices finding and helping each other on the local network: every CastApp phone,
 * tablet and TV runs a small [PeerServer] while the app is open, while it casts or while it is
 * a receiver, and announces it as `_castapp._tcp` (name, what it casts, whether one may join).
 * On top of that: joining a cast, synced playback, handing a cast over and asking for it.
 * None of this is ever needed to cast to a TV: it only shows up when another CastApp is around.
 */
@android.annotation.SuppressLint("StaticFieldLeak") // holds the application context only
object Peers {
    private const val TAG = "Peers"
    private const val SERVICE_TYPE = "_castapp._tcp"
    private const val PREFS = "multi_cast"
    private const val KEY_POLICY = "join_policy"
    private const val POLL_MS = 4_000L
    private const val FORGET_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The application context only (never an activity). */
    @android.annotation.SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private val reasons = HashSet<String>()

    private var server: PeerServer? = null
    lateinit var hub: PeerHub
        private set
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var registeredTxt: Map<String, String>? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var watchJob: Job? = null
    private var pollJob: Job? = null

    /** Addresses ("http://host:port") of CastApp devices found on the network, by service name. */
    private val found = ConcurrentHashMap<String, String>()
    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    /** Other CastApp devices on the network (never this one). */
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    /** What this device is doing about another device's cast (joining, asking, controlling). */
    sealed interface Activity {
        data class Joining(val peer: String) : Activity
        data class Asking(val peer: String) : Activity
        data class Offering(val peer: String) : Activity
    }

    private val _activity = MutableStateFlow<Activity?>(null)
    val activity: StateFlow<Activity?> = _activity.asStateFlow()

    /** "Taken over the controls": another device keeps casting files only it can read, this one has the buttons. */
    data class RemoteSession(val base: String, val name: String, val token: String, val status: PeerInfo? = null)

    private val _remote = MutableStateFlow<RemoteSession?>(null)
    val remote: StateFlow<RemoteSession?> = _remote.asStateFlow()

    val isReady: Boolean get() = ::hub.isInitialized

    // ---- lifecycle --------------------------------------------------------------------------------

    private fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        TvReceiver.init(app)
        val guard = ReceiverGuard(
            PrefsGuardStore(app.getSharedPreferences("join_guard", Context.MODE_PRIVATE)),
            ProtectionMode.ASK,
            askTimeoutMs = 8_000,
            promptLifetimeMs = 45_000,
        ).also { g -> g.onAttention = { MultiPrompt.attention(app) } }
        hub = PeerHub(::selfInfo, localCast, events, guard, SystemClock::elapsedRealtime)
        hub.policy = JoinPolicy.fromId(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_POLICY, null)) ?: JoinPolicy.ASK
        _policyFlow.value = hub.policy
    }

    /**
     * Keeps the peer server and the announcement running for [reason] ("ui": the main screen is
     * open, which also looks for other devices; "cast": casting; "receiver": receiving).
     */
    @Synchronized
    fun acquire(context: Context, reason: String) {
        init(context)
        val wasEmpty = reasons.isEmpty()
        reasons += reason
        if (wasEmpty) startServer()
        if (reason == "ui") startDiscovery()
    }

    @Synchronized
    fun release(reason: String) {
        if (!reasons.remove(reason)) return
        if (reason == "ui") stopDiscovery()
        if (reasons.isEmpty()) stopServer()
    }

    private fun startServer() {
        if (server != null) return
        val s = PeerServer(hub::handle, SystemClock::elapsedRealtime)
        try {
            s.start(DEFAULT_PORT)
        } catch (e: Exception) {
            Log.w(TAG, "Peer server didn't start", e)
            return
        }
        server = s
        nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
        // Re-announce when what we cast changes; let hand-overs run out; forget viewers who left.
        watchJob = scope.launch {
            var wasCasting = false
            while (isActive) {
                val state = CastController.state.value
                val casting = state is CastState.Active
                if (wasCasting && !casting) hub.castEnded()
                wasCasting = casting
                hub.prune()
                checkOutgoing()
                announce()
                delay(2_000)
            }
        }
    }

    private fun stopServer() {
        watchJob?.cancel()
        watchJob = null
        unregister()
        server?.stop()
        server = null
    }

    /** The port of the peer server; 0 while it doesn't run. */
    val port: Int get() = server?.port ?: 0

    /** Where a CastApp receiver on [local]'s network reads this device's shared timeline; null if not running. */
    fun timelineUrl(local: InetAddress): String? {
        val p = port
        if (p <= 0) return null
        return "http://${hostLiteral(local)}:$p/castapp/timeline"
    }

    private fun hostLiteral(a: InetAddress): String = a.hostAddress?.let { if (it.contains(':')) "[$it]" else it } ?: ""

    // ---- this device --------------------------------------------------------------------------------

    private fun selfInfo(): SelfInfo {
        val role = when {
            TvReceiver.isTv(app) -> "tv"
            app.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
            else -> "phone"
        }
        return SelfInfo(TvReceiver.uuid(app), TvReceiver.deviceName(app), role)
    }

    val policy: JoinPolicy get() = if (isReady) hub.policy else JoinPolicy.ASK

    fun setPolicy(context: Context, policy: JoinPolicy) {
        init(context)
        hub.policy = policy
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_POLICY, policy.id) }
        _policyFlow.value = policy
    }

    private val _policyFlow = MutableStateFlow(JoinPolicy.ASK)
    /** Who may join this device's casts. */
    val policyFlow: StateFlow<JoinPolicy> = _policyFlow.asStateFlow()

    // ---- the cast running here, as the others see it --------------------------------------------------

    private val localCast = object : LocalCast {
        override fun info(): CastInfo? {
            val s = CastController.state.value as? CastState.Active ?: return null
            if (s.handingOver) return null
            val file = s.kind == CastKind.FILE
            val names = s.screens.map { it.name }.ifEmpty { listOf(s.device.name) }
            return CastInfo(
                kind = if (file) HandOverOffer.KIND_FILE else HandOverOffer.KIND_SCREEN,
                title = when {
                    file -> s.title.orEmpty()
                    s.audioOnly -> app.getString(R.string.multi_title_sound)
                    else -> app.getString(R.string.multi_title_screen)
                },
                screens = names,
                positionMs = s.positionNow(SystemClock.elapsedRealtime()),
                durationMs = s.durationMs,
                paused = s.paused,
                live = s.live || !file,
                audioOnly = s.audioOnly,
            )
        }

        override fun joinMedia(remote: InetAddress?): JoinMedia? {
            val cast = CastController.activeCast ?: return null
            val local = remote?.let(::localAddressFor) ?: return null
            return cast.joinMedia(local)
        }

        override fun timeline(): Timeline? {
            val cast = CastController.activeCast ?: return null
            val s = CastController.state.value as? CastState.Active ?: return null
            return cast.timeline() ?: if (s.kind == CastKind.SCREEN) Timeline("screen", 0, SystemClock.elapsedRealtime(), true, live = true, generation = 0) else null
        }

        override fun control(action: String, positionMs: Long): Boolean {
            val s = CastController.state.value as? CastState.Active ?: return false
            val file = CastController.activeCast as? MediaCastSession
            when (action) {
                "play" -> file?.play() ?: return false
                "pause" -> file?.pause() ?: return false
                "toggle" -> CastService.send(app, CastService.ACTION_PLAY_PAUSE)
                "seek" -> CastService.seek(app, positionMs)
                "rewind" -> CastService.send(app, CastService.ACTION_REWIND)
                "forward" -> CastService.send(app, CastService.ACTION_FORWARD)
                "next" -> CastService.send(app, CastService.ACTION_NEXT)
                "previous" -> CastService.send(app, CastService.ACTION_PREVIOUS)
                "stop" -> CastService.stop(app)
                else -> return false
            }
            return s.kind == CastKind.FILE || action == "stop"
        }
    }

    // ---- prompts and hand-over ---------------------------------------------------------------------

    private val events = object : PeerEvents {
        override fun offerReceived(pending: IncomingHandOver.Pending, auto: Boolean) {
            if (auto) acceptOffer(app, pending) else MultiPrompt.attention(app)
        }

        override fun offerAnswered(offer: HandOverOffer, accepted: Boolean) {
            val peer = peerName(offer)
            if (!accepted) {
                _activity.value = null
                CastController.message(app.getString(R.string.multi_handover_declined, peer))
                hub.outgoing.reset()
                return
            }
            if (offer.mode == HandOverMode.TAKE_OVER) {
                setHandingOver(true)
                CastController.message(app.getString(R.string.multi_handover_accepted, peer))
            } else {
                _activity.value = null
                CastController.message(app.getString(R.string.multi_handover_control_given, peer))
            }
        }

        override fun handOverDone(offer: HandOverOffer, ok: Boolean) {
            _activity.value = null
            val peer = peerName(offer)
            if (ok) {
                // The other device plays on the TVs now: stop here without closing its player.
                CastController.activeCast?.let { cast ->
                    cast.handingOver = true
                    cast.finish(null, stopReceiver = false)
                }
                CastController.message(app.getString(R.string.multi_handover_done, peer))
            } else {
                setHandingOver(false)
                CastController.message(app.getString(R.string.multi_handover_failed_other, peer))
            }
            hub.outgoing.reset()
        }

        override fun castRequested(request: CastRequests.Request) = MultiPrompt.attention(app)

        override fun requestDeclined(fromName: String) {
            _activity.value = null
            CastController.message(app.getString(R.string.multi_request_declined, fromName.ifBlank { app.getString(R.string.multi_other_device) }))
        }
    }

    private fun peerName(offer: HandOverOffer): String {
        val to = (hub.outgoing.state.value as? OutgoingHandOver.State.Accepted)?.to
        return _peers.value.firstOrNull { it.host == to }?.name ?: offer.title.ifBlank { app.getString(R.string.multi_other_device) }
    }

    /** Marks the running cast as being handed over (or not any more), and shows it at once. */
    private fun setHandingOver(on: Boolean) {
        val cast = CastController.activeCast ?: return
        cast.handingOver = on
        CastController.update(cast) { it.copy(handingOver = on) }
    }

    /** Offers that got no answer in time: carry on casting here. */
    private fun checkOutgoing() {
        val s = hub.outgoing.tick()
        if (s is OutgoingHandOver.State.TimedOut) {
            setHandingOver(false)
            _activity.value = null
            CastController.message(app.getString(R.string.multi_handover_timeout))
            hub.outgoing.reset()
        }
    }

    /** A: offers the running cast to [peer] ("Hand over…", or after "<B> wants to cast"). */
    fun offerHandOver(context: Context, peer: PeerInfo, requested: Boolean = false) {
        init(context)
        val cast = CastController.activeCast ?: return
        val state = CastController.state.value as? CastState.Active ?: return
        scope.launch {
            val local = localAddressFor(peer.host)
            val p = port
            if (local == null || p <= 0) {
                CastController.message(app.getString(R.string.multi_unreachable, peer.name))
                return@launch
            }
            val kind = if (state.kind == CastKind.FILE) HandOverOffer.KIND_FILE else HandOverOffer.KIND_SCREEN
            val queue = cast.handOverQueue()
            val items = queue?.first.orEmpty()
            val mode = HandOverOffer.modeFor(kind, items)
            val me = selfInfo()
            val offer = HandOverOffer(
                id = UUID.randomUUID().toString(),
                fromId = me.id,
                fromName = me.name,
                fromBase = "http://${hostLiteral(local)}:$p",
                screens = cast.screenDevices,
                kind = kind,
                audioOnly = state.audioOnly,
                mode = mode,
                items = if (mode == HandOverMode.TAKE_OVER) HandOverOffer.portable(items) else emptyList(),
                index = queue?.second ?: 0,
                positionMs = state.positionNow(SystemClock.elapsedRealtime()),
                title = if (kind == HandOverOffer.KIND_FILE) state.title.orEmpty() else app.getString(R.string.multi_title_screen),
                requested = requested,
            )
            if (!hub.outgoing.offer(offer, peer.host)) {
                CastController.message(app.getString(R.string.multi_handover_busy))
                return@launch
            }
            _activity.value = Activity.Offering(peer.name)
            val (code, text) = PeerClient.post(peer.base, "/castapp/handover/offer", offer.toJson())
            if (code != 202 && code != 200) {
                hub.outgoing.cancel()
                _activity.value = null
                val reason = try { JSONObject(text).optString("message") } catch (_: Exception) { "" }
                CastController.message(reason.ifBlank { app.getString(R.string.multi_unreachable, peer.name) })
            } else if (mode == HandOverMode.CONTROL) {
                CastController.message(app.getString(R.string.multi_handover_control_explained))
            }
        }
    }

    /** B: the owner said yes to an offer (or asked for it): answer A, then start casting / take the controls. */
    /**
     * [starter] opens Android's screen-capture question (from the prompt screen, which stays open
     * until then, so Android lets it); [onAnswered] runs on the main thread once A has replied.
     */
    fun acceptOffer(
        context: Context,
        pending: IncomingHandOver.Pending,
        starter: ((Intent) -> Unit)? = null,
        onAnswered: () -> Unit = {},
    ) {
        init(context)
        val offer = pending.offer
        scope.launch {
            val (code, text) = PeerClient.post(offer.fromBase, "/castapp/handover/answer", JSONObject().put("id", offer.id).put("accept", true))
            if (code != 200) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onAnswered() }
                CastController.message(app.getString(R.string.multi_handover_gone, offer.fromName))
                return@launch
            }
            if (offer.mode == HandOverMode.CONTROL) {
                val token = try { JSONObject(text).optString("token") } catch (_: Exception) { "" }
                if (token.isNotEmpty()) {
                    _remote.value = RemoteSession(offer.fromBase, offer.fromName, token)
                    startRemotePolling()
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) { onAnswered() }
                return@launch
            }
            val before = CastController.activeCast
            val first = offer.screens.first()
            PendingScreens.set(offer.screens.drop(1))
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (offer.isScreen) {
                    // Android asks for this device's own screen-capture permission first.
                    val mode = if (offer.audioOnly) ProjectionActivity.MODE_AUDIO else ProjectionActivity.MODE_SCREEN
                    val intent = ProjectionActivity.intent(app, first, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        if (starter != null) starter(intent) else app.startActivity(intent)
                    } catch (_: Exception) {
                    }
                } else {
                    offer.items.getOrNull(offer.index)?.let { PendingStart.set(it.uri, offer.positionMs) }
                    CastService.startMedia(app, first, offer.items, offer.index)
                }
                onAnswered()
            }
            // Tell A once this device casts (A stops then), or that it couldn't.
            val started = withTimeoutOrNull(90_000) {
                CastController.state.first { CastController.activeCast.let { it != null && it !== before } }
                withTimeoutOrNull(25_000) {
                    CastController.state.first { s -> s !is CastState.Active || s.phase == io.github.patschgoo.castapp.service.CastPhase.LIVE }
                }
                CastController.state.value is CastState.Active
            } ?: false
            PeerClient.post(offer.fromBase, "/castapp/handover/done", JSONObject().put("id", offer.id).put("ok", started))
            if (!started) CastController.message(app.getString(R.string.multi_handover_failed_here))
        }
    }

    fun declineOffer(context: Context, pending: IncomingHandOver.Pending) {
        init(context)
        hub.incoming.decline(pending.offer.id)
        scope.launch {
            PeerClient.post(pending.offer.fromBase, "/castapp/handover/answer", JSONObject().put("id", pending.offer.id).put("accept", false))
        }
    }

    /** B: "Ask to cast": [peer] gets "<B> wants to cast. Hand over?". */
    fun askToCast(context: Context, peer: PeerInfo) {
        init(context)
        scope.launch {
            val local = localAddressFor(peer.host)
            val p = port
            if (local == null || p <= 0) {
                CastController.message(app.getString(R.string.multi_unreachable, peer.name))
                return@launch
            }
            hub.incoming.expectOfferFrom(peer.host)
            _activity.value = Activity.Asking(peer.name)
            val body = JSONObject().put("name", selfInfo().name).put("base", "http://${hostLiteral(local)}:$p")
            val (code, text) = PeerClient.post(peer.base, "/castapp/handover/request", body)
            if (code != 202 && code != 200) {
                hub.incoming.forgetRequest(peer.host)
                _activity.value = null
                val reason = try { JSONObject(text).optString("message") } catch (_: Exception) { "" }
                CastController.message(reason.ifBlank { app.getString(R.string.multi_unreachable, peer.name) })
                return@launch
            }
            CastController.message(app.getString(R.string.multi_request_sent, peer.name))
            // No answer in a while: stop showing "asking".
            delay(70_000)
            if (_activity.value == Activity.Asking(peer.name)) _activity.value = null
        }
    }

    /** A: answer to "<B> wants to cast". */
    fun answerRequest(context: Context, request: CastRequests.Request, handOver: Boolean) {
        init(context)
        val r = hub.requests.take(request.id) ?: return
        if (handOver) {
            val peer = _peers.value.firstOrNull { it.host == r.address }
                ?: PeerInfo(id = r.id, name = r.name, role = "phone", base = r.base)
            offerHandOver(context, peer, requested = true)
        } else {
            scope.launch { PeerClient.post(r.base, "/castapp/handover/declined", JSONObject().put("name", selfInfo().name)) }
        }
    }

    // ---- joining ---------------------------------------------------------------------------------------

    /** Plays [peer]'s cast here, in the CastApp player (asks the host first, unless it lets anyone join). */
    fun join(context: Context, peer: PeerInfo) {
        init(context)
        if (_activity.value is Activity.Joining) return
        _activity.value = Activity.Joining(peer.name)
        scope.launch {
            try {
                val deadline = SystemClock.elapsedRealtime() + 50_000
                while (true) {
                    when (val r = PeerClient.join(peer.base, selfInfo().name)) {
                        is PeerClient.JoinResult.Joined -> {
                            play(peer, r.answer)
                            return@launch
                        }
                        PeerClient.JoinResult.Waiting -> if (SystemClock.elapsedRealtime() > deadline) {
                            CastController.message(app.getString(R.string.multi_join_no_answer, peer.name))
                            return@launch
                        } else {
                            delay(1000)
                        }
                        is PeerClient.JoinResult.Denied -> {
                            CastController.message(
                                when (r.error) {
                                    "off" -> app.getString(R.string.multi_join_off, peer.name)
                                    "not_casting" -> app.getString(R.string.multi_join_ended, peer.name)
                                    else -> app.getString(R.string.multi_join_denied, peer.name)
                                },
                            )
                            return@launch
                        }
                        PeerClient.JoinResult.Unreachable -> {
                            CastController.message(app.getString(R.string.multi_unreachable, peer.name))
                            return@launch
                        }
                    }
                }
            } finally {
                _activity.value = null
            }
        }
    }

    private fun play(peer: PeerInfo, answer: JSONObject) {
        val url = answer.optString("url").takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        val mime = answer.optString("mime")
        val viewer = answer.optString("viewer")
        val timeline = answer.optString("timeline").ifEmpty { "/castapp/timeline" }
        val media = TvMedia(
            uri = url,
            metadata = "",
            title = answer.optString("title").ifBlank { peer.title },
            mimeType = mime.ifEmpty { null },
            kind = when {
                mime.startsWith("audio/") -> TvMediaKind.AUDIO
                mime.startsWith("image/") -> TvMediaKind.PHOTO
                else -> TvMediaKind.VIDEO
            },
            subtitleUri = answer.optString("subtitles").takeIf { it.startsWith("http") },
            live = answer.optBoolean("live"),
            lowLatency = answer.optBoolean("lowLatency"),
            syncUrl = "${peer.base}$timeline?viewer=$viewer",
            joinedFrom = peer.base,
        )
        TvReceiver.setMedia(media)
        TvReceiver.play()
    }

    /** A joined viewer's host moved on to the next item: get the new stream (no second question). */
    fun rejoin(base: String) {
        val peer = _peers.value.firstOrNull { it.base == base } ?: PeerInfo(id = base, name = "", role = "phone", base = base)
        scope.launch {
            when (val r = PeerClient.join(base, selfInfo().name)) {
                is PeerClient.JoinResult.Joined -> play(peer, r.answer)
                else -> TvReceiver.stop()
            }
        }
    }

    // ---- remote control of another device's cast -------------------------------------------------------

    fun control(action: String, positionMs: Long = 0) {
        val r = _remote.value ?: return
        scope.launch {
            val (code, _) = PeerClient.post(r.base, "/castapp/control", JSONObject().put("token", r.token).put("action", action).put("positionMs", positionMs))
            if (code == 401 || code == 409 || code == -1 || action == "stop") endRemote(notify = code != 200 && action != "stop")
        }
    }

    fun releaseRemote() {
        val r = _remote.value ?: return
        scope.launch { PeerClient.post(r.base, "/castapp/control", JSONObject().put("token", r.token).put("action", "release")) }
        endRemote(notify = false)
    }

    private fun endRemote(notify: Boolean) {
        val r = _remote.value ?: return
        _remote.value = null
        if (notify) CastController.message(app.getString(R.string.multi_remote_ended, r.name))
    }

    private fun startRemotePolling() {
        scope.launch {
            var misses = 0
            while (true) {
                val r = _remote.value ?: return@launch
                val o = PeerClient.session(r.base)
                val info = o?.let { PeerInfo.fromSession(r.base, it, SystemClock.elapsedRealtime()) }
                if (info == null) misses++ else misses = 0
                if (info != null && !info.casting || misses >= 4) {
                    endRemote(notify = true)
                    return@launch
                }
                if (info != null) _remote.value = _remote.value?.copy(status = info)
                delay(2000)
            }
        }
    }

    // ---- announcing and finding -----------------------------------------------------------------------

    private fun currentTxt(): Map<String, String> {
        val me = selfInfo()
        val info = localCast.info()
        return linkedMapOf(
            "v" to PeerHub.VERSION.toString(),
            "id" to me.id,
            "name" to me.name.take(60),
            "role" to me.role,
            "casting" to if (info != null) "1" else "0",
            "title" to info?.title.orEmpty().take(60),
            "joinable" to if (info != null && hub.policy != JoinPolicy.OFF) "1" else "0",
        )
    }

    @Synchronized
    private fun announce() {
        val m = nsd ?: return
        val p = port
        if (p <= 0) return
        val txt = currentTxt()
        if (txt == registeredTxt && registration != null) return
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = "CastApp ${txt["name"]}".take(60)
            serviceType = SERVICE_TYPE
            port = p
            txt.forEach { (k, v) -> try { setAttribute(k, v) } catch (_: Exception) {} }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@Peers) { if (registration === this) { registration = null; registeredTxt = null } }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registration = listener
            registeredTxt = txt
        } catch (e: Exception) {
            Log.i(TAG, "Couldn't announce: ${e.message}")
        }
    }

    @Synchronized
    private fun unregister() {
        val l = registration ?: return
        registration = null
        registeredTxt = null
        try { nsd?.unregisterService(l) } catch (_: Exception) {}
    }

    private fun startDiscovery() {
        val m = nsd ?: (app.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.also { nsd = it } ?: return
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovery = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = resolver.enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                info.serviceName?.let { found.remove(it) }
            }
        }
        discovery = listener
        try {
            m.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            discovery = null
        }
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                poll()
                delay(POLL_MS)
            }
        }
    }

    private fun stopDiscovery() {
        pollJob?.cancel()
        pollJob = null
        val l = discovery ?: return
        discovery = null
        try { nsd?.stopServiceDiscovery(l) } catch (_: Exception) {}
    }

    /** Asks every device found what it is doing (the announcement can be out of date). */
    private suspend fun poll() {
        val me = if (::hub.isInitialized) selfInfo().id else ""
        val now = SystemClock.elapsedRealtime()
        val previous = _peers.value.associateBy { it.base }
        val bases = found.values.toSet()
        val next = ArrayList<PeerInfo>()
        kotlinx.coroutines.coroutineScope {
            bases.map { base ->
                launch {
                    val info = PeerClient.session(base)?.let { PeerInfo.fromSession(base, it, now) }
                    synchronized(next) {
                        when {
                            info != null -> if (info.id != me) next += info
                            // Missed one poll: keep it a little longer.
                            previous[base]?.let { now - it.lastSeen < FORGET_MS } == true -> next += previous.getValue(base)
                        }
                    }
                }
            }
        }
        // The same device found twice (two addresses): keep one.
        _peers.value = next.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    /** Serial NsdManager resolving (it only does one at a time). */
    private val resolver = object {
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var busy = false

        fun enqueue(info: NsdServiceInfo) {
            synchronized(this) {
                queue.addLast(info)
                if (busy) return
                busy = true
            }
            next()
        }

        private fun next() {
            val info = synchronized(this) {
                val n = queue.removeFirstOrNull()
                if (n == null) busy = false
                n
            } ?: return
            val m = nsd ?: return synchronized(this) { busy = false }
            try {
                @Suppress("DEPRECATION")
                m.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = next()
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = serviceInfo.host
                        val name = serviceInfo.serviceName
                        if (host != null && name != null && serviceInfo.port > 0) {
                            found[name] = "http://${hostLiteral(host)}:${serviceInfo.port}"
                            scope.launch { poll() }
                        }
                        next()
                    }
                })
            } catch (_: Exception) {
                next()
            }
        }
    }

    /** Our address on the network [host] is on. */
    private fun localAddressFor(host: String): InetAddress? = try {
        DatagramSocket().use { s ->
            s.connect(InetAddress.getByName(host), 9)
            s.localAddress.takeIf { !it.isAnyLocalAddress }
        }
    } catch (_: Exception) {
        null
    }

    private fun localAddressFor(remote: InetAddress): InetAddress? = localAddressFor(remote.hostAddress ?: return null)

    /** Uses the usual port when it is free, so devices find each other again quickly. */
    private const val DEFAULT_PORT = 8765
}
