package io.github.patschgoo.castapp.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Looking for TVs to steer, shared by every screen that shows remotes (the full remote and the
 * small one on the main screen): each holds a lease while it's visible, and a provider's
 * [RemoteProvider.startDiscovery] / [RemoteProvider.stopDiscovery] run only for the first and
 * after the last of them ([SharedRun]). Screens never call those two themselves.
 */
object RemoteDiscovery {
    /** How long discovery keeps running after the last screen let go (turning the phone, switching screens). */
    const val LINGER_MS = 5_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private val registry = DiscoveryRegistry(LINGER_MS) { delayMs, task ->
        val runnable = Runnable { task() }
        main.postDelayed(runnable, delayMs)
        SharedRun.Cancel { main.removeCallbacks(runnable) }
    }

    /** Discovery of every provider in [providers] until the returned lease is closed. */
    fun acquire(context: Context, providers: List<RemoteProvider> = RemoteProviders.all): Closeable =
        registry.acquire(context, providers)

    /** Holds a lease while [owner] is started (between onStart and onStop), across any number of restarts. */
    fun whileStarted(owner: LifecycleOwner, context: Context, providers: List<RemoteProvider> = RemoteProviders.all) {
        val app = context.applicationContext ?: context
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var lease: Closeable? = null

            override fun onStart(owner: LifecycleOwner) {
                lease?.close()
                lease = acquire(app, providers)
            }

            override fun onStop(owner: LifecycleOwner) {
                lease?.close()
                lease = null
            }

            override fun onDestroy(owner: LifecycleOwner) {
                lease?.close()
                lease = null
                owner.lifecycle.removeObserver(this)
            }
        })
    }
}

/** One [SharedRun] per provider; the testable core of [RemoteDiscovery]. */
internal class DiscoveryRegistry(private val lingerMs: Long, private val timer: SharedRun.Timer) {
    private val runs = ConcurrentHashMap<RemoteProvider, SharedRun>()

    fun runFor(provider: RemoteProvider, context: Context): SharedRun {
        runs[provider]?.let { return it }
        // Providers keep the context for as long as they run: never an activity.
        val app = context.applicationContext ?: context
        val run = SharedRun(lingerMs, timer, start = { provider.startDiscovery(app) }, stop = { provider.stopDiscovery() })
        return runs.putIfAbsent(provider, run) ?: run
    }

    fun acquire(context: Context, providers: List<RemoteProvider>): Closeable {
        val leases = providers.map { runFor(it, context).acquire() }
        return Closeable { leases.forEach { it.close() } }
    }
}
