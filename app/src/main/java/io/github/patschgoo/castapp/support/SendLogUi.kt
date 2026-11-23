package io.github.patschgoo.castapp.support

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.patschgoo.castapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Send debug log": makes the report (reading the log can take a few seconds, so it says so),
 * then offers three ways out, because sharing alone fails on some phones: the share sheet, saving
 * it as a file (Downloads, a cloud folder, to send it later or from the PC), and copying the short
 * version (no log lines) to paste into a chat.
 */
@Stable
internal class LogSender(private val context: Context, private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set

    /** The report, once made: the dialog with the three choices shows while it is set. */
    var ready by mutableStateOf<File?>(null)

    fun prepare() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                ready = DebugLog.createReport(context)
            } catch (e: Exception) {
                Log.w(TAG, "Debug report failed", e)
                toast(context.getString(R.string.send_log_failed) + ": " + (e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    fun share(file: File) {
        try {
            context.startActivity(DebugLog.shareIntent(context, file, context.getString(R.string.send_log_subject)))
        } catch (e: ActivityNotFoundException) {
            toast(context.getString(R.string.send_log_no_app))
        } catch (e: Exception) {
            Log.w(TAG, "Share sheet failed", e)
            toast(context.getString(R.string.send_log_no_app))
        }
    }

    fun saveTo(file: File, uri: Uri) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val out = context.contentResolver.openOutputStream(uri) ?: return@withContext false
                    out.use { o -> file.inputStream().use { it.copyTo(o) } }
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Saving the debug report failed", e)
                    false
                }
            }
            toast(context.getString(if (ok) R.string.send_log_saved else R.string.send_log_save_failed))
        }
    }

    fun copyShort(file: File) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { DebugLog.shortVersion(file.readText()) }.getOrNull() }
            if (text == null) {
                toast(context.getString(R.string.send_log_failed))
                return@launch
            }
            try {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.send_log_subject), text))
                // Android 13+ confirms copies itself.
                if (Build.VERSION.SDK_INT < 33) toast(context.getString(R.string.send_log_copied))
            } catch (e: Exception) {
                Log.w(TAG, "Copying the debug report failed", e)
                toast(context.getString(R.string.send_log_failed))
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "SendLog"
    }
}

/** A [LogSender] for this screen; its "log ready" dialog shows by itself. */
@Composable
internal fun rememberLogSender(): LogSender {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sender = remember(context) { LogSender(context, scope) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val file = sender.ready
        if (uri != null && file != null) sender.saveTo(file, uri)
    }
    sender.ready?.let { file ->
        LogReadyDialog(
            size = Formatter.formatShortFileSize(context, file.length()),
            onShare = { sender.share(file) },
            onSave = {
                try {
                    save.launch(DebugLog.fileName())
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.send_log_save_failed, Toast.LENGTH_LONG).show()
                }
            },
            onCopy = { sender.copyShort(file) },
            onDismiss = { sender.ready = null },
        )
    }
    return sender
}

@Composable
private fun LogReadyDialog(size: String, onShare: () -> Unit, onSave: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.send_log_ready_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.send_log_ready_text, size))
                Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.send_log_share)) }
                OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.send_log_save)) }
                OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.send_log_copy)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.support_close)) } },
    )
}
