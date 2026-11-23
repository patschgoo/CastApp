package io.github.patschgoo.castapp.automation

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.PickTvSheet
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.service.MediaType
import io.github.patschgoo.castapp.ui.theme.CastAppTheme

/**
 * "Play on TV" for other apps: a CAST_QUEUE sent here shows CastApp's "Cast to TV" sheet with the
 * queue's title, and casts only when the user taps a TV. It needs neither the automation switch nor
 * the secret, because the user decides (like sharing a file to "Cast to TV"); it never casts by itself.
 * The answer goes back as the activity result and, like for [AutomationActivity], as a broadcast.
 */
class PlayOnTvActivity : ComponentActivity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A tap through another app's overlay is not the user's choice.
        window.decorView.filterTouchesWhenObscured = true
        val app = CastApp.get(this)
        val parsed = AutomationParser.parseForConfirmation(intent.action, IntentExtras(intent), AutomationSettings.get(this).current, Callers.of(this))
        val command = when (parsed) {
            is ParsedRequest.Rejected -> return refuse(parsed.result)
            is ParsedRequest.Accepted -> parsed.command as? AutomationCommand.CastQueue ?: return refuse(AutomationResult.invalid("Not a queue"))
        }
        val queue = command.queue
        val items = QueueCast.mediaItems(contentResolver, queue)
        val soundOnly = items.all { it.type == MediaType.AUDIO }
        val title = QueueMapping.sheetTitle(queue, items) { first, others -> resources.getQuantityString(R.plurals.queue_sheet_more, others, first, others) }
        // A TV the request names goes first; the user still picks.
        val named = if (command.device.isLast) null else {
            (DeviceMatcher.resolve(app.devices.devices.value.map { it.device }, command.device, null) as? DeviceMatch.Found)?.device?.id
        }
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            CastAppTheme(settings.theme) {
                val all by app.devices.devices.collectAsStateWithLifecycle()
                val scanning by app.devices.scanning.collectAsStateWithLifecycle()
                PickTvSheet(
                    title = title,
                    devices = (if (soundOnly) all else all.filter { it.device.video }).sortedByDescending { it.device.id == named },
                    scanning = scanning,
                    onPick = { device -> castTo(device, queue, items) },
                    onScan = { app.devices.scanNetwork() },
                    onDismiss = { answer(AutomationResult.CANCELLED) },
                )
            }
        }
    }

    private fun castTo(device: CastDevice, queue: CastQueueRequest, items: List<MediaItem>) {
        if (answered) return
        val result = try {
            QueueCast.start(this, device, queue, items)
            AutomationResult.ok("Casting to ${device.name}")
        } catch (e: Exception) {
            AutomationResult.failed(e.message ?: e.javaClass.simpleName)
        }
        answer(result)
    }

    private fun refuse(result: AutomationResult) {
        Toast.makeText(this, R.string.queue_request_invalid, Toast.LENGTH_LONG).show()
        answer(result)
    }

    private fun answer(result: AutomationResult) {
        if (answered) return
        answered = true
        val request = intent
        AutomationDispatcher.sendResult(this, request, result, referrer?.takeIf { it.scheme == "android-app" }?.host)
        setResult(result.code, Intent().putExtras(AutomationDispatcher.resultExtras(request, result)))
        finish()
    }

    override fun onStart() {
        super.onStart()
        CastApp.get(this).devices.startDiscovery()
    }

    override fun onStop() {
        CastApp.get(this).devices.stopDiscovery()
        super.onStop()
    }
}
