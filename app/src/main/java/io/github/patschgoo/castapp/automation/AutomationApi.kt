package io.github.patschgoo.castapp.automation

/**
 * The public, documented intent API (see AUTOMATION.md). Everything here is a contract with
 * Tasker, MacroDroid, Home Assistant and shell scripts: do not rename.
 */
object AutomationApi {
    private const val PREFIX = "io.github.patschgoo.castapp.automation."

    const val ACTION_MIRROR = PREFIX + "MIRROR"
    const val ACTION_CAST_URL = PREFIX + "CAST_URL"
    const val ACTION_CAST_FILE = PREFIX + "CAST_FILE"
    const val ACTION_STOP = PREFIX + "STOP"
    const val ACTION_PLAY_PAUSE = PREFIX + "PLAY_PAUSE"
    const val ACTION_PLAY = PREFIX + "PLAY"
    const val ACTION_PAUSE = PREFIX + "PAUSE"
    const val ACTION_NEXT = PREFIX + "NEXT"
    const val ACTION_PREVIOUS = PREFIX + "PREVIOUS"
    const val ACTION_VOLUME_SET = PREFIX + "VOLUME_SET"
    const val ACTION_VOLUME_UP = PREFIX + "VOLUME_UP"
    const val ACTION_VOLUME_DOWN = PREFIX + "VOLUME_DOWN"
    const val ACTION_MUTE = PREFIX + "MUTE"
    const val ACTION_QUALITY = PREFIX + "QUALITY"
    const val ACTION_RECEIVE = PREFIX + "RECEIVE"
    const val ACTION_OPEN_REMOTE = PREFIX + "OPEN_REMOTE"

    /**
     * A whole play list in one request ([EXTRA_QUEUE]). Sent to [AutomationActivity] or
     * [AutomationReceiver] it casts right away (switch and secret apply); sent to
     * [PlayOnTvActivity] it shows the "Cast to TV" sheet and casts only when the user picks a TV.
     */
    const val ACTION_CAST_QUEUE = PREFIX + "CAST_QUEUE"

    /** Sent back to the caller (see [AutomationResult]). */
    const val ACTION_RESULT = PREFIX + "RESULT"

    /** Sent to the [EXTRA_REPORT_TO] package while a [ACTION_CAST_QUEUE] plays (see [PlaybackEvent]). */
    const val ACTION_PLAYBACK = PREFIX + "PLAYBACK"

    val ALL_ACTIONS: List<String> = listOf(
        ACTION_MIRROR, ACTION_CAST_URL, ACTION_CAST_FILE, ACTION_STOP, ACTION_PLAY_PAUSE, ACTION_PLAY, ACTION_PAUSE,
        ACTION_NEXT, ACTION_PREVIOUS, ACTION_VOLUME_SET, ACTION_VOLUME_UP, ACTION_VOLUME_DOWN, ACTION_MUTE,
        ACTION_QUALITY, ACTION_RECEIVE, ACTION_OPEN_REMOTE, ACTION_CAST_QUEUE,
    )

    // ---- extras the callers send --------------------------------------------------------------
    /** The shared secret from Settings (only checked when one is set there). */
    const val EXTRA_SECRET = "secret"
    /** A TV by id or by name. Optional: the TV used last is the default. */
    const val EXTRA_DEVICE = "device"
    const val EXTRA_DEVICE_ID = "device_id"
    const val EXTRA_DEVICE_NAME = "device_name"
    /** MIRROR: "screen" (default), "app" (one app, Android 14+) or "audio" (sound only). */
    const val EXTRA_MODE = "mode"
    const val EXTRA_URL = "url"
    const val EXTRA_URI = "uri"
    const val EXTRA_TITLE = "title"
    const val EXTRA_MIME = "mime"
    /** VOLUME_SET: 0..100. */
    const val EXTRA_LEVEL = "level"
    /** VOLUME_UP / VOLUME_DOWN: percentage points (default: one step). */
    const val EXTRA_AMOUNT = "amount"
    const val EXTRA_QUALITY = "quality"
    /** RECEIVE: true / false; left out it switches. */
    const val EXTRA_ENABLED = "enabled"
    /** Echoed in the result so a script can match answers to requests. */
    const val EXTRA_REQUEST_ID = "request_id"
    /** Package that gets the result broadcast (default: the app that started CastApp, if known). */
    const val EXTRA_RESULT_PACKAGE = "result_package"

    /** CAST_QUEUE: the play list, a JSON array of items (see [QueueParser]). */
    const val EXTRA_QUEUE = "queue"
    /** CAST_QUEUE: which item to start with (default 0). */
    const val EXTRA_START_INDEX = "start_index"
    /** CAST_QUEUE: where to start that item, in ms (default: where this phone stopped it last time). */
    const val EXTRA_START_POSITION = "start_position_ms"
    /** CAST_QUEUE: the caller's own package, which then gets [ACTION_PLAYBACK] broadcasts. */
    const val EXTRA_REPORT_TO = "report_to"

    // ---- extras of the result broadcast ----------------------------------------------------------
    const val RESULT_EXTRA_ACTION = "action"
    const val RESULT_EXTRA_CODE = "code"
    const val RESULT_EXTRA_STATUS = "status"
    const val RESULT_EXTRA_MESSAGE = "message"

    // ---- extras of the playback broadcast ([ACTION_PLAYBACK]) ------------------------------------
    const val PLAYBACK_EXTRA_STATE = "state"
    /** The item's `id` from the queue (left out if it had none). */
    const val PLAYBACK_EXTRA_ITEM_ID = "item_id"
    /** The item's place in the queue as it was sent (0 = the first). */
    const val PLAYBACK_EXTRA_ITEM_INDEX = "item_index"
    const val PLAYBACK_EXTRA_POSITION = "position_ms"
    /** 0 while the TV has not said how long the item is. */
    const val PLAYBACK_EXTRA_DURATION = "duration_ms"
    /** The TV's name. */
    const val PLAYBACK_EXTRA_DEVICE = "device"
    /** "failed" only: one of the REASON_ values. */
    const val PLAYBACK_EXTRA_REASON = "reason"
    /** "failed" only: a sentence for humans (English). */
    const val PLAYBACK_EXTRA_MESSAGE = "message"

    const val STATE_STARTED = "started"
    const val STATE_PAUSED = "paused"
    const val STATE_RESUMED = "resumed"
    const val STATE_PROGRESS = "progress"
    const val STATE_STOPPED = "stopped"
    const val STATE_FINISHED = "finished"
    const val STATE_FAILED = "failed"

    /** The TV could not play the item in any way CastApp knows (it goes on with the next one). */
    const val REASON_CANNOT_PLAY = "cannot_play"
    /** The TV went away and did not come back. */
    const val REASON_CONNECTION_LOST = "connection_lost"
    /** CastApp could not connect to the TV or start the cast at all. */
    const val REASON_CAST_FAILED = "cast_failed"

    const val MODE_SCREEN = "screen"
    const val MODE_APP = "app"
    const val MODE_AUDIO = "audio"
}

/** What a request came to: a number for scripts that wait for a result code, a name, and a sentence. */
data class AutomationResult(val code: Int, val status: String, val message: String) {
    val ok: Boolean get() = code == CODE_OK || code == CODE_PENDING_USER

    companion object {
        /** Done (or handed to the casting service, which does the rest). */
        const val CODE_OK = 1

        /** Started, but Android or the user has to confirm first (screen-capture prompt, notification tap). */
        const val CODE_PENDING_USER = 2

        /** "Let other apps control CastApp" is off in Settings. */
        const val CODE_DISABLED = 3

        /** The secret is missing or wrong. */
        const val CODE_UNAUTHORIZED = 4

        /** The request itself is wrong: unknown action, missing or invalid extra. */
        const val CODE_INVALID = 5

        /** No TV with that id / name (or none used yet). */
        const val CODE_NO_DEVICE = 6

        /** Needs a running cast and nothing is cast. */
        const val CODE_NOT_CASTING = 7

        /** Android would not let CastApp do this from the background; start it as an activity instead. */
        const val CODE_NEEDS_ACTIVITY = 8

        /** Anything else that went wrong. */
        const val CODE_FAILED = 9

        /** Not possible for what is being cast (play/pause while mirroring, a quality the TV does not offer). */
        const val CODE_UNSUPPORTED = 10

        /** "Cast to TV" sheet ([PlayOnTvActivity]): the user closed it without picking a TV. */
        const val CODE_CANCELLED = 11

        fun ok(message: String = "Done") = AutomationResult(CODE_OK, "ok", message)
        fun pendingUser(message: String) = AutomationResult(CODE_PENDING_USER, "pending_user", message)
        val DISABLED = AutomationResult(CODE_DISABLED, "disabled", "Automation is off: turn on \"Let other apps control CastApp\" in CastApp's settings")
        val UNAUTHORIZED = AutomationResult(CODE_UNAUTHORIZED, "unauthorized", "Missing or wrong secret")
        fun unauthorized(message: String) = AutomationResult(CODE_UNAUTHORIZED, "unauthorized", message)
        fun invalid(message: String) = AutomationResult(CODE_INVALID, "invalid_request", message)
        fun noDevice(message: String) = AutomationResult(CODE_NO_DEVICE, "device_not_found", message)
        val NOT_CASTING = AutomationResult(CODE_NOT_CASTING, "not_casting", "Nothing is being cast")
        fun needsActivity(message: String) = AutomationResult(CODE_NEEDS_ACTIVITY, "needs_activity", message)
        fun failed(message: String) = AutomationResult(CODE_FAILED, "failed", message)
        fun unsupported(message: String) = AutomationResult(CODE_UNSUPPORTED, "unsupported", message)
        val CANCELLED = AutomationResult(CODE_CANCELLED, "cancelled", "Closed without picking a TV")
    }
}
