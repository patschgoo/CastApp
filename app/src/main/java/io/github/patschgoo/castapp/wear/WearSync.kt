package io.github.patschgoo.castapp.wear

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.wearproto.NowPlaying
import io.github.patschgoo.castapp.wearproto.WearCodec
import io.github.patschgoo.castapp.wearproto.WearPaths
import kotlin.math.roundToInt

/** What the watch is told about a cast. No Android code, so it is tested on the JVM. */
object WearState {
    /** Also tells the watch what this phone understands ([WearCodec.REVISION]: volume levels and repeated steps). */
    fun nowPlaying(state: CastState, lastTv: String): NowPlaying = when (state) {
        CastState.Idle -> NowPlaying(lastTv = lastTv, revision = WearCodec.REVISION)
        is CastState.Active -> NowPlaying(
            revision = WearCodec.REVISION,
            active = true,
            title = if (state.kind == CastKind.FILE) state.title.orEmpty() else "",
            device = state.device.name,
            paused = state.paused,
            mirroring = state.kind == CastKind.SCREEN,
            queueIndex = state.queueIndex,
            queueSize = state.queueSize,
            volumePercent = state.volume?.let { (it * 100).roundToInt().coerceIn(0, 100) } ?: -1,
            lastTv = lastTv,
        )
    }
}

/**
 * Tells paired watches what is playing. Runs only while a cast changes (called by CastService),
 * asks Google Play services which watches have the CastApp watch app (at most once a minute),
 * and does nothing at all when there are none or Play services are missing.
 */
object WearSync {
    private const val CACHE_MS = 60_000L
    private val lock = Any()
    private var nodes: List<String> = emptyList()
    private var nodesAt = -CACHE_MS
    private var pending: NowPlaying? = null
    private var lastSent: NowPlaying? = null
    private var lookingUp = false

    fun onState(context: Context, state: CastState) {
        val app = context.applicationContext
        val now = WearState.nowPlaying(state, lastTvName(app))
        val ids: List<String>? = synchronized(lock) {
            pending = now
            if (now == lastSent) return
            if (SystemClock.elapsedRealtime() - nodesAt < CACHE_MS) nodes else null
        }
        if (ids != null) send(app, ids) else lookUp(app)
    }

    /** Answers a watch that asks what is playing (it just opened the app). */
    fun reply(context: Context, nodeId: String, state: CastState) {
        val app = context.applicationContext
        val bytes = WearCodec.encodeState(WearState.nowPlaying(state, lastTvName(app)))
        try {
            Wearable.getMessageClient(app).sendMessage(nodeId, WearPaths.STATE, bytes)
        } catch (_: Throwable) {
        }
    }

    private fun lookUp(app: Context) {
        synchronized(lock) {
            if (lookingUp) return
            lookingUp = true
        }
        fun done(ids: List<String>) {
            synchronized(lock) {
                nodes = ids
                nodesAt = SystemClock.elapsedRealtime()
                lookingUp = false
            }
            send(app, ids)
        }
        try {
            Wearable.getCapabilityClient(app)
                .getCapability(WearPaths.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { info -> done(info.nodes.map { it.id }) }
                .addOnFailureListener { done(emptyList()) }
        } catch (_: Throwable) {
            done(emptyList())
        }
    }

    private fun send(app: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        val now = synchronized(lock) {
            val p = pending ?: return
            if (p == lastSent) return
            lastSent = p
            p
        }
        val bytes = WearCodec.encodeState(now)
        try {
            val client = Wearable.getMessageClient(app)
            for (id in ids) client.sendMessage(id, WearPaths.STATE, bytes)
        } catch (_: Throwable) {
        }
    }

    private fun lastTvName(app: Context): String = try {
        CastApp.get(app).devices.lastUsed()?.name.orEmpty()
    } catch (_: Exception) {
        ""
    }
}
