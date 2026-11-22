package io.github.patschgoo.castapp.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.patschgoo.castapp.ProjectionActivity
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.discovery.CastDevice

/**
 * Android only shows its screen-capture dialog to an app the user is looking at. When mirroring is
 * asked for from the background (a broadcast, the watch), CastApp shows a notification instead:
 * a tap opens the dialog (on a locked or idle phone it comes up by itself, as a full-screen intent).
 */
object MirrorPrompt {
    private const val CHANNEL = "automation_prompt"
    private const val NOTIFICATION_ID = 61

    /** Shows the notification; false if notifications are switched off for CastApp. */
    @Suppress("MissingPermission") // checked through areNotificationsEnabled()
    fun show(context: Context, device: CastDevice, mode: MirrorMode): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        createChannel(context)
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            ProjectionActivity.intent(context, device, mode.wire).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(context.getString(R.string.automation_prompt_title, device.name))
            .setContentText(context.getString(R.string.automation_prompt_text))
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .build()
        return try {
            nm.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.automation_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.automation_channel_description)
            },
        )
    }
}
