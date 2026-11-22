package io.github.patschgoo.castapp

import android.content.Context
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.patschgoo.castapp.discovery.CastDevice

/** Launcher shortcuts (long-press the app icon) for casting to the last used TV in one tap. */
object Shortcuts {
    fun update(context: Context, device: CastDevice) {
        try {
            val mirror = ShortcutInfoCompat.Builder(context, "cast_screen")
                .setShortLabel(context.getString(R.string.shortcut_mirror_short))
                .setLongLabel(context.getString(R.string.shortcut_mirror_long, device.name))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_shortcut_cast))
                .setIntent(ProjectionActivity.intent(context, device.id, ProjectionActivity.MODE_SCREEN))
                .setRank(0)
                .build()
            val shortcuts = mutableListOf(mirror)
            if (Build.VERSION.SDK_INT >= 34) {
                shortcuts += ShortcutInfoCompat.Builder(context, "cast_app")
                    .setShortLabel(context.getString(R.string.shortcut_app_short))
                    .setLongLabel(context.getString(R.string.shortcut_app_long, device.name))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_shortcut_app))
                    .setIntent(ProjectionActivity.intent(context, device.id, ProjectionActivity.MODE_APP))
                    .setRank(1)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (_: Exception) {
        }
    }
}
