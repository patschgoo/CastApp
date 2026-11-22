package io.github.patschgoo.castapp.support

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * "Do not disturb while mirroring": switches the phone to Do Not Disturb (priority only)
 * when screen mirroring starts and puts the previous mode back when it ends.
 * The previous mode is stored, so it is restored even if the app was restarted meanwhile.
 */
object DoNotDisturb {
    fun enabled(context: Context): Boolean = SupportPrefs.of(context).getBoolean(SupportPrefs.DND_WHILE_MIRRORING, false)

    fun setEnabled(context: Context, on: Boolean) {
        SupportPrefs.of(context).edit().putBoolean(SupportPrefs.DND_WHILE_MIRRORING, on).apply()
    }

    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun hasAccess(context: Context): Boolean = try { manager(context).isNotificationPolicyAccessGranted } catch (_: Exception) { false }

    /** The system page where the user grants "Do Not Disturb access". */
    fun accessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /** Mirroring started: turn Do Not Disturb on (if wanted and allowed). */
    fun onMirroringStarted(context: Context) {
        val app = context.applicationContext
        if (!enabled(app) || !hasAccess(app)) return
        val prefs = SupportPrefs.of(app)
        try {
            val nm = manager(app)
            val current = nm.currentInterruptionFilter
            // Already restoring from an earlier start (e.g. switching TVs): keep the first saved mode.
            if (prefs.getInt(SupportPrefs.DND_SAVED_FILTER, NO_SAVED) == NO_SAVED) {
                prefs.edit().putInt(SupportPrefs.DND_SAVED_FILTER, current).apply()
            }
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            prefs.edit().putInt(SupportPrefs.DND_SET_FILTER, NotificationManager.INTERRUPTION_FILTER_PRIORITY).apply()
        } catch (_: Exception) {
        }
    }

    /** Mirroring ended: put the earlier mode back, unless the user changed it meanwhile. */
    fun onMirroringEnded(context: Context) {
        val app = context.applicationContext
        val prefs = SupportPrefs.of(app)
        val saved = prefs.getInt(SupportPrefs.DND_SAVED_FILTER, NO_SAVED)
        if (saved == NO_SAVED) return
        val set = prefs.getInt(SupportPrefs.DND_SET_FILTER, NO_SAVED)
        try {
            if (hasAccess(app)) {
                val nm = manager(app)
                if (shouldRestore(nm.currentInterruptionFilter, set, saved)) nm.setInterruptionFilter(saved)
            }
        } catch (_: Exception) {
        }
        prefs.edit().remove(SupportPrefs.DND_SAVED_FILTER).remove(SupportPrefs.DND_SET_FILTER).apply()
    }

    /** Restore only when the mode is still the one we set (or unknown): the user's own change wins. */
    fun shouldRestore(current: Int, setByUs: Int, saved: Int): Boolean =
        saved != NO_SAVED && current == setByUs && saved != current

    private const val NO_SAVED = -1
}
