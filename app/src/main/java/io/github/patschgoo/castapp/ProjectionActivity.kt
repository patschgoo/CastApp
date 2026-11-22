package io.github.patschgoo.castapp

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.service.CastService
import org.json.JSONObject

/**
 * Invisible activity that asks Android for permission to capture the screen
 * (Android shows its own "Start casting?" dialog) and then starts [CastService].
 * Used by the main screen, the Quick Settings tile and launcher shortcuts.
 */
class ProjectionActivity : ComponentActivity() {
    private var device: CastDevice? = null
    private var mode: String = MODE_SCREEN
    private var launchPackage: String? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestProjection()
    }

    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            requestProjection()
        } else {
            Toast.makeText(this, R.string.sound_needs_permission, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val d = device
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null && d != null) {
            CastService.start(
                this, d, result.resultCode, data,
                singleApp = mode == MODE_APP && Build.VERSION.SDK_INT >= 34,
                audioOnly = mode == MODE_AUDIO,
            )
            launchPackage?.let { pkg ->
                packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                    try {
                        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                    }
                }
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = CastApp.get(this)
        device = intent.getStringExtra(EXTRA_DEVICE_JSON)?.let { runCatching { CastDevice.fromJson(JSONObject(it)) }.getOrNull() }
            ?: intent.getStringExtra(EXTRA_DEVICE_ID)?.let { app.devices.find(it) }
            ?: app.devices.lastUsed()
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SCREEN
        launchPackage = intent.getStringExtra(EXTRA_LAUNCH_PACKAGE)

        if (savedInstanceState != null) return // the system dialog is already showing
        if (device == null) {
            Toast.makeText(this, R.string.pick_a_tv_first, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        if (mode == MODE_AUDIO) {
            if (Build.VERSION.SDK_INT < 29) {
                Toast.makeText(this, R.string.sound_needs_android10, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            // Sound casting needs this permission; ask every time it's missing.
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }
        // "Phone microphone" is on: it needs the recording permission (asked whenever it's missing).
        if (app.settings.current.microphone && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        askOptionalPermissionsThenProject()
    }

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
        askOptionalPermissionsThenProject()
    }

    private fun askOptionalPermissionsThenProject() {
        val app = CastApp.get(this)
        val missing = optionalPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && !app.settings.askedForPermissions) {
            app.settings.askedForPermissions = true
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            requestProjection()
        }
    }

    private fun optionalPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 29 && mode != MODE_AUDIO && CastApp.get(this@ProjectionActivity).settings.current.audio) {
            add(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            val config = if (mode == MODE_APP) MediaProjectionConfig.createConfigForUserChoice()
            else MediaProjectionConfig.createConfigForDefaultDisplay()
            manager.createScreenCaptureIntent(config)
        } else {
            manager.createScreenCaptureIntent()
        }
        try {
            projectionLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_screen_capture, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        const val ACTION_CAST = "io.github.patschgoo.castapp.action.CAST"
        const val MODE_SCREEN = "screen"
        const val MODE_APP = "app"
        /** Only the phone's sound (speakers, or a TV without picture). */
        const val MODE_AUDIO = "audio"
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_DEVICE_JSON = "device_json"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_LAUNCH_PACKAGE = "launch_package"

        /** Intent for shortcuts/tile: the device is looked up by id (or the last used one). */
        fun intent(context: Context, deviceId: String?, mode: String = MODE_SCREEN): Intent =
            Intent(context, ProjectionActivity::class.java)
                .setAction(ACTION_CAST)
                .putExtra(EXTRA_MODE, mode)
                .apply { if (deviceId != null) putExtra(EXTRA_DEVICE_ID, deviceId) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun intent(context: Context, device: CastDevice, mode: String, launchPackage: String? = null): Intent =
            Intent(context, ProjectionActivity::class.java)
                .setAction(ACTION_CAST)
                .putExtra(EXTRA_DEVICE_JSON, device.toJson().toString())
                .putExtra(EXTRA_MODE, mode)
                .apply { if (launchPackage != null) putExtra(EXTRA_LAUNCH_PACKAGE, launchPackage) }
    }
}
