package io.github.patschgoo.castapp.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Invisible activity that shows one system dialog for the hardware remotes and hands the
 * answer back to the waiting coroutine: a runtime permission request, "Turn on Bluetooth"
 * or "Make this phone visible". Same idea as [io.github.patschgoo.castapp.PinActivity].
 */
class RemoteHelperActivity : ComponentActivity() {
    private var requestId = 0
    private var answered = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        answer(if (result.isNotEmpty() && result.values.all { it }) Activity.RESULT_OK else Activity.RESULT_CANCELED)
    }
    private val systemLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        answer(result.resultCode)
    }

    // The permissions these system dialogs need are requested first by the caller.
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getIntExtra(EXTRA_ID, 0)
        if (!pending.containsKey(requestId)) {
            answered = true
            finish()
            return
        }
        // After a rotation the result is delivered to the new instance by the launcher.
        if (savedInstanceState != null) return
        try {
            when (intent.getStringExtra(EXTRA_KIND)) {
                KIND_PERMISSIONS -> permissionLauncher.launch(intent.getStringArrayExtra(EXTRA_PERMISSIONS) ?: emptyArray())
                KIND_ENABLE_BLUETOOTH -> systemLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                KIND_DISCOVERABLE -> systemLauncher.launch(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, intent.getIntExtra(EXTRA_SECONDS, 120)),
                )
                else -> answer(Activity.RESULT_CANCELED)
            }
        } catch (_: Exception) {
            answer(Activity.RESULT_CANCELED)
        }
    }

    private fun answer(resultCode: Int) {
        answered = true
        pending.remove(requestId)?.complete(resultCode)
        finish()
    }

    override fun onDestroy() {
        if (!answered && isFinishing) pending.remove(requestId)?.complete(Activity.RESULT_CANCELED)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_PERMISSIONS = "permissions"
        private const val EXTRA_SECONDS = "seconds"
        private const val KIND_PERMISSIONS = "permissions"
        private const val KIND_ENABLE_BLUETOOTH = "enable_bluetooth"
        private const val KIND_DISCOVERABLE = "discoverable"

        private val pending = ConcurrentHashMap<Int, CompletableDeferred<Int>>()
        private val ids = AtomicInteger(1)

        /** Starts the helper and waits for the dialog's result code (RESULT_CANCELED if it can't be shown). */
        private suspend fun ask(context: Context, kind: String, fill: Intent.() -> Unit = {}): Int {
            val id = ids.getAndIncrement()
            val answer = CompletableDeferred<Int>()
            pending[id] = answer
            try {
                val intent = Intent(context, RemoteHelperActivity::class.java)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_KIND, kind)
                    .apply(fill)
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return withTimeoutOrNull(5 * 60_000L) { answer.await() } ?: Activity.RESULT_CANCELED
            } catch (_: Exception) {
                return Activity.RESULT_CANCELED
            } finally {
                pending.remove(id)
            }
        }

        /** True if every permission was granted. */
        suspend fun requestPermissions(context: Context, permissions: Array<String>): Boolean =
            ask(context, KIND_PERMISSIONS) { putExtra(EXTRA_PERMISSIONS, permissions) } == Activity.RESULT_OK

        /** Shows Android's "Turn on Bluetooth?" dialog; true if the user agreed. */
        suspend fun requestEnableBluetooth(context: Context): Boolean =
            ask(context, KIND_ENABLE_BLUETOOTH) == Activity.RESULT_OK

        /** Shows Android's "Make this phone visible to other Bluetooth devices?" dialog; true if allowed. */
        suspend fun requestDiscoverable(context: Context, seconds: Int): Boolean =
            ask(context, KIND_DISCOVERABLE) { putExtra(EXTRA_SECONDS, seconds) } != Activity.RESULT_CANCELED
    }
}
