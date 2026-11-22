package io.github.patschgoo.castapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.ui.AppPickerScreen
import io.github.patschgoo.castapp.ui.DevicesScreen
import io.github.patschgoo.castapp.ui.SettingsScreen
import io.github.patschgoo.castapp.ui.theme.CastAppTheme
import io.github.patschgoo.castapp.ui.theme.isAppInDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (io.github.patschgoo.castapp.tv.TvReceiver.isTv(this)) {
            // On a Fire TV / Android TV this app is the receiver.
            startActivity(Intent(this, io.github.patschgoo.castapp.tv.TvHomeActivity::class.java))
            finish()
            return
        }
        val app = CastApp.get(this)
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            val dark = isAppInDarkTheme(settings.theme)
            LaunchedEffect(dark) {
                // Status/navigation bar icons must match the app's theme, not just the system's.
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CastAppTheme(settings.theme) {
                AppRoot(app, this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        CastApp.get(this).devices.startDiscovery()
    }

    override fun onStop() {
        CastApp.get(this).devices.stopDiscovery()
        super.onStop()
    }

    /** Turns the phone screen black; the shared app comes back to the front underneath. */
    fun startDarkScreen() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.dark_screen_permission, Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        CastService.darkScreen(this, true)
        moveTaskToBack(true)
    }

    /** "Display over other apps" (Dark screen, Turn apps sideways) is granted in system settings. */
    fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
    }
}

private enum class Route { DEVICES, SETTINGS, APP_PICKER }

@Composable
private fun AppRoot(app: CastApp, activity: MainActivity) {
    val context = LocalContext.current
    var route by rememberSaveable { mutableStateOf(Route.DEVICES) }
    var pickerDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var fileDeviceId by rememberSaveable { mutableStateOf<String?>(null) }

    fun cast(device: CastDevice, mode: String, launchPackage: String? = null) {
        context.startActivity(ProjectionActivity.intent(context, device, mode, launchPackage))
    }

    var audioGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 29 ||
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioGranted = granted
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // "Cast videos, music or photos": the system file picker (several files = a play list).
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val device = fileDeviceId?.let { app.devices.find(it) }
        if (uris.isNotEmpty() && device != null) {
            for (uri in uris) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
            }
            val items = MediaItem.fromUris(context.contentResolver, uris)
            if (items.isEmpty()) {
                Toast.makeText(context, R.string.share_unsupported, Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }
            CastService.startMedia(context, device, items)
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                // The notification holds the lock-screen controls.
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    BackHandler(enabled = route != Route.DEVICES) { route = Route.DEVICES }

    when (route) {
        Route.DEVICES -> DevicesScreen(
            app = app,
            onOpenSettings = { route = Route.SETTINGS },
            onMirror = { cast(it, ProjectionActivity.MODE_SCREEN) },
            onCastApp = { device ->
                if (Build.VERSION.SDK_INT >= 34) {
                    // Android 14+ lets the user pick a single app in the system dialog.
                    cast(device, ProjectionActivity.MODE_APP)
                } else {
                    pickerDeviceId = device.id
                    route = Route.APP_PICKER
                }
            },
            onCastFiles = { device ->
                app.devices.save(device)
                fileDeviceId = device.id
                val types = if (device.video && !app.devices.audioMode.value) {
                    arrayOf("video/*", "audio/*", "image/*", "application/x-subrip", "text/vtt", "application/octet-stream")
                } else {
                    arrayOf("audio/*")
                }
                filePicker.launch(types)
            },
            onCastSound = { cast(it, ProjectionActivity.MODE_AUDIO) },
            onOpenBrowser = { context.startActivity(Intent(context, BrowserActivity::class.java)) },
            onDarkScreen = { activity.startDarkScreen() },
            onOpenTvChannels = { context.startActivity(Intent(context, TvChannelsActivity::class.java)) },
            onOpenNetwork = { context.startActivity(Intent(context, NetworkActivity::class.java)) },
        )
        Route.SETTINGS -> SettingsScreen(
            settings = app.settings,
            audioPermissionGranted = audioGranted,
            onRequestAudioPermission = { audioPermission.launch(Manifest.permission.RECORD_AUDIO) },
            onRequestOverlayPermission = { activity.requestOverlayPermission() },
            onBack = { route = Route.DEVICES },
        )
        Route.APP_PICKER -> AppPickerScreen(
            onBack = { route = Route.DEVICES },
            onPick = { pkg ->
                val device = pickerDeviceId?.let { app.devices.find(it) }
                route = Route.DEVICES
                if (device != null) cast(device, ProjectionActivity.MODE_SCREEN, pkg)
            },
        )
    }
}
