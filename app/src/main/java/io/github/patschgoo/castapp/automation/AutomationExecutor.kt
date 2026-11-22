package io.github.patschgoo.castapp.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.ProjectionActivity
import io.github.patschgoo.castapp.RemoteActivity
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.remote.RemoteTargets
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.tv.TvReceiverService
import io.github.patschgoo.castapp.youtube.YouTubeCast
import io.github.patschgoo.castapp.youtube.YouTubeLink

/**
 * Carries out a validated [AutomationCommand]. [interactive] says the caller is an activity that
 * is on screen (so Android lets us open other screens and start services); a broadcast or the
 * watch is not, and gets "needs_activity" or a notification where Android would refuse.
 */
class AutomationExecutor(private val context: Context) {
    private val app = CastApp.get(context)

    fun run(command: AutomationCommand, interactive: Boolean): AutomationResult {
        ServiceMapping.stepFor(command, CastController.state.value)?.let { return perform(it, interactive) }
        return when (command) {
            is AutomationCommand.Mirror -> mirror(command, interactive)
            is AutomationCommand.CastUrl -> castUrl(command, interactive)
            is AutomationCommand.CastFile -> castFile(command, interactive)
            is AutomationCommand.Receive -> receive(command, interactive)
            is AutomationCommand.OpenRemote -> openRemote(command, interactive)
            else -> AutomationResult.failed("Not handled: $command")
        }
    }

    // ---- commands for the running cast -------------------------------------------------------------

    private fun perform(step: ServiceStep, interactive: Boolean): AutomationResult = when (step) {
        is ServiceStep.Answer -> step.result
        is ServiceStep.Call -> try {
            val intent = Intent(context, CastService::class.java).setAction(step.action)
            for ((key, value) in step.extras) {
                when (value) {
                    is String -> intent.putExtra(key, value)
                    is Double -> intent.putExtra(key, value)
                    is Int -> intent.putExtra(key, value)
                    is Boolean -> intent.putExtra(key, value)
                    is Long -> intent.putExtra(key, value)
                }
            }
            context.startService(intent)
            AutomationResult.ok()
        } catch (e: Exception) {
            backgroundRefused(e, interactive)
        }
    }

    // ---- start something ------------------------------------------------------------------------------

    private fun resolve(ref: DeviceRef): DeviceMatch =
        DeviceMatcher.resolve(app.devices.devices.value.map { it.device }, ref, app.devices.lastUsed())

    private inline fun withDevice(ref: DeviceRef, block: (CastDevice) -> AutomationResult): AutomationResult =
        when (val match = resolve(ref)) {
            is DeviceMatch.Found -> block(match.device)
            is DeviceMatch.NotFound -> AutomationResult.noDevice(match.message)
        }

    private fun mirror(c: AutomationCommand.Mirror, interactive: Boolean): AutomationResult = withDevice(c.device) { device ->
        if (c.mode == MirrorMode.AUDIO && Build.VERSION.SDK_INT < 29) {
            return@withDevice AutomationResult.unsupported("Casting only the sound needs Android 10 or newer")
        }
        if (c.mode == MirrorMode.APP && Build.VERSION.SDK_INT < 34) {
            return@withDevice AutomationResult.unsupported("Casting a single app needs Android 14 or newer")
        }
        if (interactive) {
            try {
                context.startActivity(ProjectionActivity.intent(context, device, c.mode.wire).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                AutomationResult.pendingUser("Android asks for permission to capture the screen; casting to ${device.name} starts when it is confirmed")
            } catch (e: Exception) {
                AutomationResult.failed("Could not open the screen-capture prompt: ${e.message}")
            }
        } else if (MirrorPrompt.show(context, device, c.mode)) {
            AutomationResult.pendingUser("Tap the CastApp notification on the phone to confirm screen capture for ${device.name}")
        } else {
            AutomationResult.needsActivity("Notifications are off for CastApp; start the MIRROR action as an activity instead")
        }
    }

    private fun castUrl(c: AutomationCommand.CastUrl, interactive: Boolean): AutomationResult = withDevice(c.device) { device ->
        val youTube = YouTubeLink.parse(c.url)
        if (youTube != null && YouTubeCast.supports(device)) {
            // The TV's own YouTube app plays it best (the same as sharing a YouTube link to CastApp).
            YouTubeCast.play(app, device, youTube)
            return@withDevice AutomationResult.ok("Opening the video in YouTube on ${device.name}")
        }
        startMedia(device, listOf(MediaItem(title = c.title, uri = c.url, mimeType = c.mime)), interactive)
    }

    private fun castFile(c: AutomationCommand.CastFile, interactive: Boolean): AutomationResult = withDevice(c.device) { device ->
        val items = try {
            MediaItem.fromUris(context.contentResolver, listOf(Uri.parse(c.uri)))
        } catch (e: Exception) {
            emptyList()
        }.map { item -> item.copy(title = c.title ?: item.title, mimeType = c.mime ?: item.mimeType) }
        if (items.isEmpty()) {
            AutomationResult.invalid("CastApp cannot read a video, music or photo at that URI (is read access granted to CastApp?)")
        } else {
            startMedia(device, items, interactive)
        }
    }

    private fun startMedia(device: CastDevice, items: List<MediaItem>, interactive: Boolean): AutomationResult = try {
        CastService.startMedia(context, device, items)
        AutomationResult.ok("Casting to ${device.name}")
    } catch (e: Exception) {
        backgroundRefused(e, interactive)
    }

    // ---- other ----------------------------------------------------------------------------------------

    private fun receive(c: AutomationCommand.Receive, interactive: Boolean): AutomationResult {
        val turnOn = c.enabled ?: !TvReceiverService.running.value
        return if (turnOn) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TvReceiverService::class.java))
                AutomationResult.ok("Receiving on this phone is starting")
            } catch (e: Exception) {
                backgroundRefused(e, interactive)
            }
        } else {
            TvReceiverService.stop(context)
            AutomationResult.ok("Receiving on this phone is off")
        }
    }

    private fun openRemote(c: AutomationCommand.OpenRemote, interactive: Boolean): AutomationResult {
        if (!interactive) return AutomationResult.needsActivity("Opening a screen only works when the OPEN_REMOTE action is started as an activity")
        val targetId = if (c.device.isLast) null else when (val match = resolve(c.device)) {
            is DeviceMatch.Found -> RemoteTargets.idFor(match.device)
            is DeviceMatch.NotFound -> return AutomationResult.noDevice(match.message)
        }
        return try {
            context.startActivity(RemoteActivity.intent(context, targetId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AutomationResult.ok("Remote opened")
        } catch (e: Exception) {
            AutomationResult.failed("Could not open the remote: ${e.message}")
        }
    }

    /** Android 12+ refuses some service starts from the background; tell the caller how to avoid that. */
    private fun backgroundRefused(e: Exception, interactive: Boolean): AutomationResult =
        if (!interactive && e is IllegalStateException) {
            AutomationResult.needsActivity("Android does not let CastApp start this from the background. Start the action as an activity, or exempt CastApp from battery optimization")
        } else {
            AutomationResult.failed(e.message ?: e.javaClass.simpleName)
        }
}
