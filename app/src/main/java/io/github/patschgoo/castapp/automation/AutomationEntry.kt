package io.github.patschgoo.castapp.automation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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

    override val grantedUris: Set<String> by lazy {
        try {
            if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return@lazy emptySet()
            val clip = intent.clipData
            val fromClip = clip?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri?.toString() } }.orEmpty()
            (fromClip + listOfNotNull(intent.dataString)).filter(::isContentUri).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}

/** Who sent a request, as far as Android tells ([CallerEvidence]). */
object Callers {
    fun of(activity: Activity): CallerEvidence = CallerEvidence(
        callingActivity = try { activity.callingActivity?.packageName } catch (_: Exception) { null },
        sharedIdentity = if (Build.VERSION.SDK_INT >= 34) activity.launchedFromPackage else null,
        referrer = systemReferrer(activity),
        sdk = Build.VERSION.SDK_INT,
    )

    /** Call it in onReceive. */
    fun of(receiver: BroadcastReceiver): CallerEvidence =
        CallerEvidence(sharedIdentity = if (Build.VERSION.SDK_INT >= 34) receiver.sentFromPackage else null, sdk = Build.VERSION.SDK_INT)

    /** The app that started [activity] as Android recorded it: getReferrer() prefers the caller's own EXTRA_REFERRER, so it goes. */
    private fun systemReferrer(activity: Activity): String? {
        val original = activity.intent ?: return null
        return try {
            activity.intent = Intent(original).apply {
                removeExtra(Intent.EXTRA_REFERRER)
                removeExtra(Intent.EXTRA_REFERRER_NAME)
            }
            activity.referrer?.takeIf { it.scheme == "android-app" }?.host
        } catch (_: Exception) {
            null
        } finally {
            activity.intent = original
        }
    }
}

/** What both entry points do: check, run, and answer the caller. */
object AutomationDispatcher {
    fun handle(context: Context, intent: Intent, interactive: Boolean, caller: CallerEvidence = CallerEvidence.NONE): AutomationResult {
        val result = AutomationParser.parse(intent.action, IntentExtras(intent), AutomationSettings.get(context).current, caller)
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
            val result = AutomationDispatcher.handle(this, request, interactive = true, Callers.of(this))
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
        val result = AutomationDispatcher.handle(context, intent, interactive = false, Callers.of(this))
        if (isOrderedBroadcast) setResult(result.code, result.status, AutomationDispatcher.resultExtras(intent, result))
        AutomationDispatcher.sendResult(context, intent, result, fallbackPackage = null)
    }
}
