package io.github.patschgoo.castapp.iptv

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.TvChannelsActivity
import io.github.patschgoo.castapp.service.CastService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Somewhere to keep one piece of text (a SharedPreferences value on the phone, a variable in tests). */
interface TextStore {
    fun read(): String?
    fun write(text: String)
}

class PrefsTextStore(context: Context, file: String, private val key: String) : TextStore {
    private val prefs = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString(key, null)
    override fun write(text: String) = prefs.edit { putString(key, text) }
}

/** Sets or cancels the one wake-up of a kind of alarm (null cancels). */
fun interface AlarmPort {
    fun schedule(triggerAtMs: Long?)
}

/** A reminder: "tell me before [title] starts on [channelName]". */
data class Reminder(
    val id: String,
    val title: String,
    val channelName: String,
    val channelUrl: String,
    val startMs: Long,
    val stopMs: Long,
    val userAgent: String? = null,
    val referrer: String? = null,
    val leadMs: Long = DEFAULT_LEAD_MS,
) {
    /** When the notification is due. */
    val triggerAt: Long get() = startMs - leadMs

    fun toChannel(): Channel = Channel(name = channelName, url = channelUrl, userAgent = userAgent, referrer = referrer)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("cn", channelName).put("cu", channelUrl)
        .put("start", startMs).put("stop", stopMs).put("ua", userAgent ?: "").put("ref", referrer ?: "").put("lead", leadMs)

    companion object {
        const val DEFAULT_LEAD_MS = 5 * 60_000L

        /** The same programme on the same channel always has the same id, so it cannot be added twice. */
        fun idFor(channelUrl: String, startMs: Long): String = "${channelUrl.hashCode().toUInt().toString(16)}-$startMs"

        fun fromJson(o: JSONObject) = Reminder(
            id = o.getString("id"),
            title = o.optString("title"),
            channelName = o.optString("cn"),
            channelUrl = o.optString("cu"),
            startMs = o.getLong("start"),
            stopMs = o.getLong("stop"),
            userAgent = o.optString("ua").ifEmpty { null },
            referrer = o.optString("ref").ifEmpty { null },
            leadMs = o.optLong("lead", DEFAULT_LEAD_MS),
        )

        fun of(channel: Channel, programme: Programme, leadMs: Long = DEFAULT_LEAD_MS) = Reminder(
            id = idFor(channel.url, programme.start),
            title = programme.title,
            channelName = channel.name,
            channelUrl = channel.url,
            startMs = programme.start,
            stopMs = programme.stop,
            userAgent = channel.userAgent,
            referrer = channel.referrer,
            leadMs = leadMs,
        )
    }
}

/** The reminders, kept in [store] and listed as a flow. */
class ReminderBook(private val store: TextStore) {
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Reminder>> = _items.asStateFlow()

    @Synchronized
    fun add(reminder: Reminder) {
        _items.value = (_items.value.filter { it.id != reminder.id } + reminder).sortedBy { it.startMs }
        save()
    }

    @Synchronized
    fun remove(id: String) {
        val left = _items.value.filter { it.id != id }
        if (left.size == _items.value.size) return
        _items.value = left
        save()
    }

    fun has(channelUrl: String, startMs: Long): Boolean = _items.value.any { it.id == Reminder.idFor(channelUrl, startMs) }

    /** The earliest time a notification is due, or null when there is no reminder. */
    fun nextTrigger(): Long? = _items.value.minOfOrNull { it.triggerAt }

    /**
     * Takes the reminders that are due at [now] (and, quietly, those whose programme is over by now)
     * out of the list. Returns the due ones that are still worth telling about.
     */
    @Synchronized
    fun popDue(now: Long): List<Reminder> {
        val due = _items.value.filter { it.triggerAt <= now }
        if (due.isEmpty()) return emptyList()
        _items.value = _items.value - due.toSet()
        save()
        return due.filter { it.stopMs > now }
    }

    private fun load(): List<Reminder> = try {
        val arr = JSONArray(store.read() ?: "[]")
        (0 until arr.length()).mapNotNull { i -> runCatching { Reminder.fromJson(arr.getJSONObject(i)) }.getOrNull() }.sortedBy { it.startMs }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save() {
        store.write(JSONArray().also { a -> _items.value.forEach { a.put(it.toJson()) } }.toString())
    }
}

/** Keeps one wake-up set for the earliest reminder; sets it again after every change and after a restart. */
class ReminderScheduler(private val book: ReminderBook, private val alarm: AlarmPort) {
    fun reschedule(now: Long) {
        val next = book.nextTrigger()
        alarm.schedule(next?.let { maxOf(it, now + MIN_DELAY_MS) })
    }

    /** The wake-up fired: the reminders to show now. Sets the next wake-up. */
    fun onAlarm(now: Long): List<Reminder> {
        val due = book.popDue(now)
        reschedule(now)
        return due
    }

    fun add(reminder: Reminder, now: Long = System.currentTimeMillis()) {
        book.add(reminder)
        reschedule(now)
    }

    fun cancel(id: String, now: Long = System.currentTimeMillis()) {
        book.remove(id)
        reschedule(now)
    }

    companion object {
        const val MIN_DELAY_MS = 1_000L
    }
}

/** An alarm of this app that wakes [IptvAlarmReceiver] with [action]; inexact, so it needs no special permission. */
class AndroidAlarmPort(private val context: Context, private val action: String, private val requestCode: Int) : AlarmPort {
    override fun schedule(triggerAtMs: Long?) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            context, requestCode, Intent(context, IptvAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (triggerAtMs == null) am.cancel(pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (_: Exception) {
        }
    }
}

/** The reminders of the app. */
class ReminderCenter(private val context: Context) {
    val book = ReminderBook(PrefsTextStore(context, "iptv_extras", "reminders"))
    val scheduler = ReminderScheduler(book, AndroidAlarmPort(context, IptvAlarmReceiver.ACTION_REMINDER, 41))
    val items: StateFlow<List<Reminder>> get() = book.items

    fun add(reminder: Reminder) = scheduler.add(reminder)
    fun cancel(id: String) = scheduler.cancel(id)
    fun rescheduleAll() = scheduler.reschedule(System.currentTimeMillis())
}

object Reminders {
    @SuppressLint("StaticFieldLeak") // the center only keeps the application context
    @Volatile private var instance: ReminderCenter? = null

    fun get(context: Context): ReminderCenter =
        instance ?: synchronized(this) { instance ?: ReminderCenter(context.applicationContext).also { instance = it } }
}

/** Notifications for reminders. */
object ReminderNotifier {
    private const val CHANNEL_ID = "iptv_reminders"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.iptvx_channel_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.iptvx_channel_reminders_description)
            },
        )
    }

    fun show(context: Context, reminder: Reminder, now: Long) {
        createChannel(context)
        val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(reminder.startMs))
        val text = context.getString(
            if (now >= reminder.startMs) R.string.iptvx_reminder_started else R.string.iptvx_reminder_text,
            reminder.title, time, reminder.channelName,
        )
        val open = PendingIntent.getActivity(
            context, reminder.id.hashCode(), Intent(context, TvChannelsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(reminder.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
        val device = CastApp.get(context).devices.lastUsed()
        if (device != null) {
            val watch = Intent(context, IptvAlarmReceiver::class.java)
                .setAction(IptvAlarmReceiver.ACTION_WATCH)
                .putExtra(IptvAlarmReceiver.EXTRA_CHANNEL, reminder.toChannel().toJson().toString())
                .putExtra(IptvAlarmReceiver.EXTRA_DEVICE, device.id)
                .putExtra(IptvAlarmReceiver.EXTRA_NOTIFICATION, reminder.id.hashCode())
            val pi = PendingIntent.getBroadcast(
                context, reminder.id.hashCode(), watch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_cast, context.getString(R.string.iptvx_reminder_watch_on, device.name), pi)
        }
        try {
            NotificationManagerCompat.from(context).notify(reminder.id.hashCode(), builder.build())
        } catch (_: SecurityException) {
            // No notification permission (Android 13+): the reminder is lost, the list explains it.
        }
    }
}

/**
 * Wake-ups of reminders and scheduled recordings, the "Watch on <TV>" button of a reminder, and
 * the restart of the phone (alarms are gone then and are set again from the saved lists).
 */
class IptvAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_REMINDER -> {
                val now = System.currentTimeMillis()
                for (r in Reminders.get(app).scheduler.onAlarm(now)) ReminderNotifier.show(app, r, now)
            }
            ACTION_RECORDING -> Recordings.get(app).onAlarm()
            ACTION_WATCH -> {
                val id = intent.getStringExtra(EXTRA_DEVICE)
                val channel = try {
                    Channel.fromJson(JSONObject(intent.getStringExtra(EXTRA_CHANNEL).orEmpty()))
                } catch (_: Exception) {
                    return
                }
                val device = id?.let { CastApp.get(app).devices.find(it) } ?: CastApp.get(app).devices.lastUsed() ?: return
                try {
                    CastService.startMedia(app, device, IptvRepository.toItems(listOf(channel)))
                } catch (_: Exception) {
                }
                NotificationManagerCompat.from(app).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                Reminders.get(app).rescheduleAll()
                Recordings.get(app).rescheduleAll()
            }
        }
    }

    companion object {
        const val ACTION_REMINDER = "io.github.patschgoo.castapp.action.IPTV_REMINDER"
        const val ACTION_RECORDING = "io.github.patschgoo.castapp.action.IPTV_RECORDING"
        const val ACTION_WATCH = "io.github.patschgoo.castapp.action.IPTV_WATCH"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_NOTIFICATION = "notification"
    }
}
