package io.github.patschgoo.castapp.iptv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.RecordingsActivity
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastLocks
import io.github.patschgoo.castapp.service.CastState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Times and sizes of recordings as short text. */
object RecordingFormat {
    /** `1:05:09` or `5:09`. */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/** Providers often allow one connection per account: find recordings that would take it from what is being watched. */
object RecordingConflicts {
    /** True when the TV plays a live channel of the same account as [channelUrl]. */
    suspend fun watchingSameAccount(context: Context, channelUrl: String): Boolean {
        val state = CastController.state.value as? CastState.Active ?: return false
        val title = state.title?.takeIf { state.kind == CastKind.FILE && state.live } ?: return false
        val repo = CastApp.get(context).iptv
        return withContext(Dispatchers.IO) {
            repo.playlists.value.any { p ->
                runCatching { repo.channels(p) }.getOrDefault(emptyList()).any { it.name == title && ConnectionGuard.sameAccount(it.url, channelUrl) }
            }
        }
    }

    /** True when another recording that runs now uses the same account. */
    fun recordingSameAccount(center: RecordingCenter, channelUrl: String, ignoreId: String? = null): Boolean =
        center.book.items.value.any { it.state == RecordingState.RECORDING && it.id != ignoreId && ConnectionGuard.sameAccount(it.channelUrl, channelUrl) }
}

/** Records channels in the background (a foreground service, so the phone keeps them running with the screen off). */
class RecordingService : Service() {
    private class Running(val recording: Recording) {
        @Volatile var job: Job? = null
        @Volatile var recorder: StreamRecorder? = null
        @Volatile var waiting = true
        @Volatile var stopRequested = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<String, Running>()
    private var locks: CastLocks? = null
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RecordingNotifier.createChannels(this)
        goForeground()
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_START -> if (id != null) begin(id)
            ACTION_STOP -> if (id != null) stopOne(id)
            ACTION_STOP_ALL -> running.keys.toList().forEach(::stopOne)
        }
        if (running.isEmpty()) endService()
        return START_NOT_STICKY
    }

    /** Android 15 ends this kind of service after a few hours: stop the recordings so they are kept as they are. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        running.keys.toList().forEach(::stopOne)
    }

    override fun onDestroy() {
        running.values.forEach { it.recorder?.stop() }
        scope.cancel()
        locks?.release()
        super.onDestroy()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val center = Recordings.get(this)
        val active = running.values.map { center.book.get(it.recording.id) ?: it.recording }
        val recording = active.filter { it.state == RecordingState.RECORDING }
        val text = when {
            active.isEmpty() -> getString(R.string.iptvx_notif_recording_idle)
            recording.isEmpty() -> getString(R.string.iptvx_notif_waiting, active.first().title)
            recording.size == 1 -> {
                val r = recording.first()
                getString(R.string.iptvx_notif_recording_one, r.title, Formatter.formatShortFileSize(this, r.bytes), RecordingFormat.duration(r.recordedMs))
            }
            else -> getString(R.string.iptvx_notif_recording_many, recording.size, Formatter.formatShortFileSize(this, recording.sumOf { it.bytes }))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RecordingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, RecordingNotifier.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.iptvx_notif_recording_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.iptvx_stop), stop)
            .build()
    }

    private fun refreshNotification() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    private fun begin(id: String) {
        val center = Recordings.get(this)
        val rec = center.book.get(id) ?: return
        if (running.containsKey(id) || rec.state != RecordingState.SCHEDULED) return
        val entry = Running(rec)
        running[id] = entry
        if (locks == null) locks = CastLocks(this, "castapp:recording").also { it.acquire() }
        entry.job = scope.launch {
            try {
                runOne(center, entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                center.book.update(id) { it.copy(state = if (it.bytes > 0) RecordingState.PARTIAL else RecordingState.FAILED, error = e.message ?: e.javaClass.simpleName) }
            } finally {
                running.remove(id)
                center.deleteIfRequested(id)
                if (running.isEmpty()) endService() else refreshNotification()
            }
        }
        if (ticker == null) {
            ticker = scope.launch {
                while (isActive) {
                    delay(3_000)
                    for (r in running.values) {
                        val p = r.recorder?.progress ?: continue
                        center.book.update(r.recording.id) { it.copy(bytes = p.bytes, recordedMs = p.recordedMs, gaps = p.gaps) }
                    }
                    refreshNotification()
                }
            }
        }
        refreshNotification()
    }

    private fun stopOne(id: String) {
        val entry = running[id] ?: return
        entry.stopRequested = true
        if (entry.waiting) {
            // It had not started: planned recordings that are stopped before they begin are dropped.
            entry.job?.cancel()
            Recordings.get(this).book.remove(id)
            Recordings.get(this).reschedule(System.currentTimeMillis())
        } else {
            entry.recorder?.stop()
        }
    }

    private fun endService() {
        ticker?.cancel()
        ticker = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runOne(center: RecordingCenter, entry: Running) {
        val rec = entry.recording
        val wait = rec.plannedStartMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        entry.waiting = false

        val warning = when {
            RecordingConflicts.recordingSameAccount(center, rec.channelUrl, rec.id) -> getString(R.string.iptvx_warn_two_recordings)
            RecordingConflicts.watchingSameAccount(this, rec.channelUrl) -> getString(R.string.iptvx_warn_watching)
            else -> null
        }
        center.book.update(rec.id) { it.copy(state = RecordingState.RECORDING, warning = warning, error = null) }
        refreshNotification()

        val recorder = StreamRecorder()
        entry.recorder = recorder
        if (entry.stopRequested) recorder.stop()
        val taken = center.book.takenNames()
        var output: RecordingOutput? = null
        var note: String? = null
        val result = withContext(Dispatchers.IO) {
            recorder.record(rec.channelUrl, rec.toChannel().headers, rec.plannedEndMs) { ext ->
                val wanted = RecordingNames.unique(
                    RecordingNames.fileName(rec.channelName, rec.title, if (rec.programmeStartMs > 0) rec.programmeStartMs else rec.plannedStartMs, ext),
                    taken,
                )
                var target = rec.target
                val opened = try {
                    RecordingSinks.open(this@RecordingService, target, rec.sourceId, wanted)
                } catch (e: java.io.IOException) {
                    if (target != RecordingTarget.NETWORK) throw e
                    // The network folder can't be reached: keep the recording on the phone instead of losing it.
                    target = RecordingTarget.PHONE
                    note = getString(R.string.iptvx_warn_saved_on_phone, e.message.orEmpty())
                    RecordingSinks.open(this@RecordingService, target, "", wanted)
                }
                output = opened
                center.book.update(rec.id) {
                    it.copy(fileName = opened.fileName, location = opened.location, target = target, warning = listOfNotNull(it.warning, note).joinToString("\n").ifEmpty { null })
                }
                opened.stream
            }
        }
        val problem = output?.finish(keep = result.bytes > 0)
        val stoppedEarly = result.outcome == RecordOutcome.STOPPED && rec.plannedEndMs > 0 && System.currentTimeMillis() < rec.plannedEndMs - 10_000
        val state = when {
            result.bytes == 0L || problem != null -> RecordingState.FAILED
            result.outcome == RecordOutcome.COMPLETE -> RecordingState.DONE
            result.outcome == RecordOutcome.STOPPED -> if (stoppedEarly) RecordingState.PARTIAL else RecordingState.DONE
            result.outcome == RecordOutcome.PARTIAL -> RecordingState.PARTIAL
            else -> RecordingState.FAILED
        }
        center.book.update(rec.id) {
            val gone = state == RecordingState.FAILED
            it.copy(
                state = state,
                bytes = result.bytes,
                recordedMs = result.recordedMs,
                gaps = result.gaps,
                error = problem ?: result.error,
                fileName = if (gone) "" else it.fileName,
                location = if (gone) "" else it.location,
            )
        }
        if (!center.isDeleteRequested(rec.id)) center.book.get(rec.id)?.let { RecordingNotifier.showFinished(this, it) }
    }

    companion object {
        private const val NOTIFICATION_ID = 7
        private const val ACTION_START = "io.github.patschgoo.castapp.action.REC_START"
        private const val ACTION_STOP = "io.github.patschgoo.castapp.action.REC_STOP"
        private const val ACTION_STOP_ALL = "io.github.patschgoo.castapp.action.REC_STOP_ALL"
        private const val EXTRA_ID = "id"

        fun start(context: Context, id: String) {
            ContextCompat.startForegroundService(
                context, Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id),
            )
        }

        fun stop(context: Context, id: String) {
            // A plain start: when nothing runs there is nothing to stop (and no foreground promise to keep).
            try {
                context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id))
            } catch (_: Exception) {
            }
        }
    }
}

/** Notifications of recordings besides the running one. */
object RecordingNotifier {
    const val CHANNEL_RECORDING = "iptv_recording"
    private const val CHANNEL_EVENTS = "iptv_recording_events"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_RECORDING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RECORDING, context.getString(R.string.iptvx_channel_recording), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.iptvx_channel_recording_description)
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_EVENTS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_EVENTS, context.getString(R.string.iptvx_channel_recording_events), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun open(context: Context, id: String, startId: String? = null): PendingIntent {
        val intent = Intent(context, RecordingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (startId != null) intent.putExtra(RecordingsActivity.EXTRA_START_ID, startId)
        return PendingIntent.getActivity(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showFinished(context: Context, r: Recording) {
        createChannels(context)
        val text = when (r.state) {
            RecordingState.DONE -> context.getString(R.string.iptvx_notif_finished, r.title)
            RecordingState.PARTIAL -> context.getString(R.string.iptvx_notif_partial, r.title)
            else -> context.getString(R.string.iptvx_notif_failed, r.title)
        }
        notify(context, r.id.hashCode(), NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(text)
            .setContentText(r.channelName)
            .setAutoCancel(true)
            .setContentIntent(open(context, r.id))
            .build())
    }

    /** A scheduled recording is due but Android does not let the app start it from the background: the user taps to start it. */
    fun showStartPrompt(context: Context, r: Recording) {
        createChannels(context)
        notify(context, r.id.hashCode(), NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(context.getString(R.string.iptvx_notif_start_prompt, r.title))
            .setContentText(context.getString(R.string.iptvx_notif_start_prompt_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open(context, r.id, r.id))
            .build())
    }

    private fun notify(context: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
