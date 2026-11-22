package io.github.patschgoo.castapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.ui.AppPickerScreen
import io.github.patschgoo.castapp.ui.DevicesScreen
import io.github.patschgoo.castapp.ui.SettingsScreen
import io.github.patschgoo.castapp.ui.theme.CastAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = CastApp.get(this)
        setContent {
            CastAppTheme {
                AppRoot(app)
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
}

private enum class Route { DEVICES, SETTINGS, APP_PICKER }

@Composable
private fun AppRoot(app: CastApp) {
    val context = LocalContext.current
    var route by rememberSaveable { mutableStateOf(Route.DEVICES) }
    var pickerDeviceId by rememberSaveable { mutableStateOf<String?>(null) }

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
        )
        Route.SETTINGS -> SettingsScreen(
            settings = app.settings,
            audioPermissionGranted = audioGranted,
            onRequestAudioPermission = { audioPermission.launch(Manifest.permission.RECORD_AUDIO) },
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
