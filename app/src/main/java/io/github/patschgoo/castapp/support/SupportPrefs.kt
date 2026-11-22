package io.github.patschgoo.castapp.support

import android.content.Context
import android.content.SharedPreferences

/** Small switches of the support tools, kept apart from the casting settings. */
object SupportPrefs {
    const val AUTO_UPDATE_CHECK = "auto_update_check"
    const val LAST_UPDATE_CHECK = "last_update_check"
    const val SKIPPED_VERSION = "skipped_version"
    const val DND_WHILE_MIRRORING = "dnd_while_mirroring"
    const val DND_SAVED_FILTER = "dnd_saved_filter"
    const val DND_SET_FILTER = "dnd_set_filter"
    const val BATTERY_ASKED = "battery_asked"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("support", Context.MODE_PRIVATE)
}

/** The FileProvider that shares the debug log and the downloaded update with other apps. */
object SupportFiles {
    fun authority(context: Context): String = context.packageName + ".support.files"
}
