package io.github.patschgoo.castapp.iptv

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.R
import java.util.Date

/** A recording about to be started: a programme of [channel], or ([programme] null) the channel right now. */
internal class RecordRequest(val channel: Channel, val programme: Programme?)

/**
 * What the TV channels screen needs for the extras (programme sheet, recording, reminders,
 * channel menu, parental lock), kept apart from the screen so it stays small.
 */
@Stable
internal class IptvExtras(context: Context) {
    val parental = ParentalLock.get(context)
    val reminders = Reminders.get(context)
    val recordings = Recordings.get(context)

    var programme by mutableStateOf<Pair<ChannelEntry, Programme>?>(null)
    var channelMenu by mutableStateOf<ChannelEntry?>(null)
    var recordRequest by mutableStateOf<RecordRequest?>(null)
    var showParental by mutableStateOf(false)
    var pinAction by mutableStateOf<(() -> Unit)?>(null)

    /** Runs [action] now, or after the PIN when [channel] is locked. */
    fun guarded(channel: Channel, action: () -> Unit) {
        if (parental.state.value.needsPin(channel, System.currentTimeMillis())) pinAction = action else action()
    }
}

@Composable
internal fun rememberIptvExtras(): IptvExtras {
    val context = LocalContext.current
    return remember { IptvExtras(context.applicationContext) }
}

/** The sheets and dialogs of [IptvExtras]. */
@Composable
internal fun IptvExtrasHost(
    extras: IptvExtras,
    now: Long,
    parental: ParentalSnapshot,
    groups: List<String>,
    /** Days of catch-up the provider keeps for this channel (0 = none). */
    catchUpDays: (ChannelEntry) -> Int,
    onPlay: (ChannelEntry) -> Unit,
    onReplay: (ChannelEntry, Programme) -> Unit,
    onSchedule: (ChannelEntry) -> Unit,
    message: (String) -> Unit,
) {
    val context = LocalContext.current
    val askNotifications = rememberNotificationAsker()
    val reminders by extras.reminders.items.collectAsStateWithLifecycle()
    val recordings by extras.recordings.items.collectAsStateWithLifecycle()
    val recSettings by extras.recordings.settings.values.collectAsStateWithLifecycle()
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }

    extras.programme?.let { (entry, programme) ->
        val days = catchUpDays(entry)
        val reminded = reminders.any { it.id == Reminder.idFor(entry.channel.url, programme.start) }
        val planned = recordings.firstOrNull {
            it.isActive && it.channelUrl == entry.channel.url && it.programmeStartMs == programme.start
        }
        ProgrammeSheet(
            entry = entry,
            programme = programme,
            now = now,
            canReplay = days > 0 && programme.stop <= now && programme.start >= now - days * DAY_MS,
            reminder = reminded,
            plannedRecording = planned,
            onWatch = {
                extras.programme = null
                extras.guarded(entry.channel) { onPlay(entry) }
            },
            onReplay = {
                extras.programme = null
                extras.guarded(entry.channel) { onReplay(entry, programme) }
            },
            onToggleReminder = {
                if (reminded) {
                    extras.reminders.cancel(Reminder.idFor(entry.channel.url, programme.start))
                    message(context.getString(R.string.iptvx_reminder_removed))
                } else {
                    extras.guarded(entry.channel) {
                        askNotifications()
                        extras.reminders.add(Reminder.of(entry.channel, programme))
                        message(context.getString(R.string.iptvx_reminder_set, programme.title, timeFormat.format(Date(programme.start))))
                    }
                }
            },
            onRecord = {
                extras.programme = null
                extras.guarded(entry.channel) { extras.recordRequest = RecordRequest(entry.channel, programme) }
            },
            onCancelRecording = {
                planned?.let { extras.recordings.cancelScheduled(it.id) }
                extras.programme = null
                message(context.getString(R.string.iptvx_recording_cancelled))
            },
            onSchedule = {
                extras.programme = null
                onSchedule(entry)
            },
            onDismiss = { extras.programme = null },
        )
    }

    extras.recordRequest?.let { request ->
        val starts = request.programme == null || request.programme.isOn(now)
        // Only a recording that starts now can take the connection of what is playing.
        val clash by produceState<Int?>(null, request) {
            value = when {
                !starts -> null
                RecordingConflicts.recordingSameAccount(extras.recordings, request.channel.url) -> R.string.iptvx_warn_two_recordings
                RecordingConflicts.watchingSameAccount(context, request.channel.url) -> R.string.iptvx_warn_watching
                else -> null
            }
        }
        val warning = clash?.let { stringResource(it) }
        RecordDialog(
            title = request.programme?.let { "${request.channel.name} · ${it.title}" } ?: request.channel.name,
            programme = request.programme,
            now = now,
            values = recSettings,
            warning = warning,
            onConfirm = { before, after, minutes ->
                extras.recordRequest = null
                askNotifications()
                val p = request.programme
                if (p == null) {
                    extras.recordings.recordNow(request.channel, minutes * 60_000L)
                    message(context.getString(R.string.iptvx_recording_started))
                } else {
                    extras.recordings.settings.setPadding(before, after)
                    extras.recordings.schedule(request.channel, p, before * 60_000L, after * 60_000L)
                    message(context.getString(R.string.iptvx_recording_planned, p.title))
                }
            },
            onDismiss = { extras.recordRequest = null },
        )
    }

    extras.channelMenu?.let { entry ->
        ChannelActionsSheet(
            channel = entry.channel,
            parental = parental,
            now = now,
            onRecordNow = {
                extras.channelMenu = null
                extras.guarded(entry.channel) { extras.recordRequest = RecordRequest(entry.channel, null) }
            },
            onProtect = { p ->
                if (!extras.parental.setChannel(entry.channel.url, p)) {
                    // Changing rules needs the PIN first.
                    extras.pinAction = { extras.parental.setChannel(entry.channel.url, p) }
                }
            },
            onSetUpLock = {
                extras.channelMenu = null
                extras.showParental = true
            },
            onDismiss = { extras.channelMenu = null },
        )
    }

    if (extras.showParental) {
        ParentalSheet(extras.parental, parental, groups, now, onDismiss = { extras.showParental = false })
    }

    extras.pinAction?.let { action ->
        PinDialog(
            rules = extras.parental,
            title = stringResource(R.string.iptvx_parental_pin_needed),
            onUnlocked = {
                extras.pinAction = null
                action()
            },
            onDismiss = { extras.pinAction = null },
        )
    }
}

private const val DAY_MS = 24 * 3600_000L
