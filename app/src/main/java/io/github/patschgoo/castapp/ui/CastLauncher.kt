package io.github.patschgoo.castapp.ui

import io.github.patschgoo.castapp.R
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.PickTvSheet
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.service.MediaType

/**
 * Sends a play list to a TV: straight to the TV that is already playing files
 * (switching channels or files there without connecting again), otherwise to a
 * TV the user picks in a sheet.
 */
class CastLauncher(private val context: Context) {
    class Request(val title: String, val items: List<MediaItem>, val index: Int)

    var request by mutableStateOf<Request?>(null)
    internal var askNotifications: (() -> Unit)? = null

    fun cast(items: List<MediaItem>, index: Int = 0, title: String = items.getOrNull(index)?.title.orEmpty()) {
        if (items.isEmpty()) return
        val needsPicture = items.any { it.type != MediaType.AUDIO }
        val playing = (CastController.state.value as? CastState.Active)?.takeIf { it.kind == CastKind.FILE }?.device
        if (playing != null && (playing.video || !needsPicture)) {
            CastService.startMedia(context, playing, items, index)
            Toast.makeText(context, context.getString(R.string.toast_playing_on, playing.name), Toast.LENGTH_SHORT).show()
        } else {
            request = Request(title, items, index)
        }
    }

    internal fun started() {
        io.github.patschgoo.castapp.support.BatteryPrompt.onBackgroundCastStarted(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications?.invoke() // the notification holds the lock-screen controls
        }
    }
}

@Composable
fun rememberCastLauncher(): CastLauncher {
    val context = LocalContext.current
    val launcher = remember { CastLauncher(context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    launcher.askNotifications = {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    return launcher
}

/** The "which TV?" sheet of a [CastLauncher]; shown while it has a request. */
@Composable
fun CastLauncherSheet(launcher: CastLauncher) {
    val request = launcher.request ?: return
    val context = LocalContext.current
    val app = CastApp.get(context)
    val devices by app.devices.devices.collectAsStateWithLifecycle()
    val scanning by app.devices.scanning.collectAsStateWithLifecycle()
    val needsPicture = request.items.any { it.type != MediaType.AUDIO }
    PickTvSheet(
        title = request.title,
        devices = if (needsPicture) devices.filter { it.device.video } else devices,
        scanning = scanning,
        onPick = { device ->
            launcher.request = null
            app.devices.save(device)
            CastService.startMedia(context, device, request.items, request.index)
            launcher.started()
        },
        onScan = { app.devices.scanNetwork() },
        onDismiss = { launcher.request = null },
    )
}
