package io.github.patschgoo.castapp

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Looks up user-visible text from code that has no Context at hand (enum labels, exception
 * messages, controller messages). The application context follows the phone's language.
 */
object Txt {
    fun s(@StringRes id: Int, vararg args: Any?): String = try {
        val app = CastApp.instance
        if (args.isEmpty()) app.getString(id) else app.getString(id, *args)
    } catch (_: UninitializedPropertyAccessException) {
        "string#$id" // unit tests run without an Application
    }

    /** Like [s], but returns [fallback] (English, formatted) where there is no Application: pure functions under unit test. */
    fun sOr(@StringRes id: Int, fallback: String, vararg args: Any?): String = try {
        CastApp.instance.getString(id, *args)
    } catch (_: UninitializedPropertyAccessException) {
        if (args.isEmpty()) fallback else fallback.format(*args)
    }

    fun n(@PluralsRes id: Int, count: Int, vararg args: Any?): String = try {
        CastApp.instance.resources.getQuantityString(id, count, *args)
    } catch (_: UninitializedPropertyAccessException) {
        "plural#$id"
    }
}
