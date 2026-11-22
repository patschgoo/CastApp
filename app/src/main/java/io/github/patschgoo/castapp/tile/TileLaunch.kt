package io.github.patschgoo.castapp.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Opens an activity from a tile and collapses the shade. Android 14 wants a PendingIntent for that
 * and drops the Intent overload; older versions only have the Intent one. Asks to unlock first.
 */
@SuppressLint("StartActivityAndCollapseDeprecated")
internal fun TileService.launchFromTile(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val start = {
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
    if (isLocked) unlockAndRun { start() } else start()
}
