package io.github.patschgoo.castapp.ui

/** How the main screen was opened, as far as the first-run tour cares. */
enum class LaunchSource {
    /** The app icon in the launcher (or the same task again from Recents). */
    LAUNCHER,
    /** "Cast to TV" in the share sheet / "Open with". */
    SHARE,
    /** A Quick Settings tile (tap or long-press). */
    TILE,
    /** The home-screen widget. */
    WIDGET,
    /** An automation app (Tasker, a shortcut, an intent from another app). */
    AUTOMATION,
    /** Anything else: our own notification, a screen of the app, an unknown explicit intent. */
    OTHER,
}

/**
 * Decides whether the short first-run tour is shown. Plain values only, so it can be tested
 * on the JVM: the activity reads them from its intent.
 */
object TourDecision {
    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    const val ACTION_QS_TILE_PREFERENCES = "android.service.quicksettings.action.QS_TILE_PREFERENCES"
    private val SHARE_ACTIONS = setOf(
        "android.intent.action.SEND",
        "android.intent.action.SEND_MULTIPLE",
        "android.intent.action.VIEW",
    )

    /**
     * Optional extra naming where an intent comes from (a [LaunchSource] name, e.g. "AUTOMATION").
     * Without it, explicit intents of the widget, tiles and notifications count as [LaunchSource.OTHER].
     */
    const val EXTRA_LAUNCH_SOURCE = "io.github.patschgoo.castapp.extra.LAUNCH_SOURCE"

    /**
     * Where the activity was started from: [action] and [categories] of its intent, and the value
     * of [EXTRA_LAUNCH_SOURCE] if there is one (it wins).
     */
    fun sourceOf(action: String?, categories: Set<String>, sourceExtra: String? = null): LaunchSource {
        sourceExtra?.let { name -> LaunchSource.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it } }
        return when {
            action == ACTION_QS_TILE_PREFERENCES -> LaunchSource.TILE
            action in SHARE_ACTIONS -> LaunchSource.SHARE
            action == ACTION_MAIN && CATEGORY_LAUNCHER in categories -> LaunchSource.LAUNCHER
            // A custom action ("…action.CAST", Tasker, a shortcut) is an automation.
            action != null && action != ACTION_MAIN -> LaunchSource.AUTOMATION
            else -> LaunchSource.OTHER
        }
    }

    /**
     * Show the tour only once ([seen]), never on a TV (the TV has its own receiver screen), and only
     * when the person opened the app themselves from the launcher: not in the middle of sharing a
     * file, tapping a tile or widget, or when an automation started it.
     */
    fun shouldShow(seen: Boolean, isTv: Boolean, source: LaunchSource): Boolean =
        !seen && !isTv && source == LaunchSource.LAUNCHER
}
