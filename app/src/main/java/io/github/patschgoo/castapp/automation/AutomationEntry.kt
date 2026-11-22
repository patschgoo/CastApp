package io.github.patschgoo.castapp.automation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Reads the extras of an Intent. A bundle that cannot be read (foreign Parcelable) counts as empty. */
class IntentExtras(private val intent: Intent) : ExtrasSource {
    private val bundle: Bundle? = try {
        intent.extras
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    override fun get(key: String): Any? = try {
        bundle?.get(key)
    } catch (_: Exception) {
        null
    }

    override val data: String? get() = intent.dataString
}

/** What both entry points do: check, run, and answer the caller. */
object AutomationDispatcher {
    fun handle(context: Context, intent: Intent, interactive: Boolean): AutomationResult {
        val result = AutomationParser.parse(intent.action, IntentExtras(intent), AutomationSettings.get(context).current)
        return when (result) {
            is ParsedRequest.Rejected -> result.result
            is ParsedRequest.Accepted -> try {
                AutomationExecutor(context).run(result.command, interactive)
            } catch (e: Exception) {
                AutomationResult.failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** The result as the extras of the result broadcast and of the activity result. */
    fun resultExtras(intent: Intent, result: AutomationResult): Bundle = Bundle().apply {
        putString(AutomationApi.RESULT_EXTRA_ACTION, intent.action)
        putInt(AutomationApi.RESULT_EXTRA_CODE, result.code)
        putString(AutomationApi.RESULT_EXTRA_STATUS, result.status)
        putString(AutomationApi.RESULT_EXTRA_MESSAGE, result.message)
        IntentExtras(intent)[AutomationApi.EXTRA_REQUEST_ID]?.toString()?.let { putString(AutomationApi.EXTRA_REQUEST_ID, it) }
    }

    /** Broadcasts the result to [packageName]: the `result_package` extra, else [fallbackPackage]. Nothing if neither is known. */
    fun sendResult(context: Context, intent: Intent, result: AutomationResult, fallbackPackage: String?) {
        val target = IntentExtras(intent)[AutomationApi.EXTRA_RESULT_PACKAGE]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackPackage
            ?: return
        try {
            context.sendBroadcast(Intent(AutomationApi.ACTION_RESULT).setPackage(target).putExtras(resultExtras(intent, result)))
        } catch (_: Exception) {
        }
    }
}

/**
 * Start it as an activity (Tasker "Send Intent" with target Activity, `am start`, Home Assistant
 * `command_activity`). Invisible; works for every action, including those that open a screen.
 * The result goes back as the activity result and, if the caller can be told apart, as a broadcast.
 */
class AutomationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val request = intent
            val result = AutomationDispatcher.handle(this, request, interactive = true)
            val caller = referrer?.takeIf { it.scheme == "android-app" }?.host
            AutomationDispatcher.sendResult(this, request, result, caller)
            setResult(result.code, Intent().putExtras(AutomationDispatcher.resultExtras(request, result)))
        }
        finish()
    }
}

/**
 * Send it as a broadcast (Tasker "Send Intent" with target Broadcast, `am broadcast`, Home Assistant
 * `command_broadcast_intent`). Cannot open screens; for the rest it answers an ordered broadcast
 * with a result code and data, and sends the result broadcast when `result_package` is given.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = AutomationDispatcher.handle(context, intent, interactive = false)
        if (isOrderedBroadcast) setResult(result.code, result.status, AutomationDispatcher.resultExtras(intent, result))
        AutomationDispatcher.sendResult(context, intent, result, fallbackPackage = null)
    }
}
