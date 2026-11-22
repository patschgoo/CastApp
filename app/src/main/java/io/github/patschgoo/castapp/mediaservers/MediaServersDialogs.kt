package io.github.patschgoo.castapp.mediaservers

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.patschgoo.castapp.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A message for a failed request, in the phone's language. */
@Composable
internal fun errorText(e: Throwable): String = when {
    e is ServerHttpException && (e.code == 401 || e.code == 403) -> stringResource(R.string.ms_err_auth)
    e is ServerHttpException && e.code == 0 -> stringResource(R.string.ms_err_unreachable, e.message.orEmpty())
    e is ServerHttpException -> stringResource(R.string.ms_err_http, e.message.orEmpty())
    else -> stringResource(R.string.ms_err_unreachable, e.message ?: e.javaClass.simpleName)
}

/** Same for code outside of a composition (toasts). */
internal fun errorMessage(context: Context, e: Throwable): String = when {
    e is ServerHttpException && (e.code == 401 || e.code == 403) -> context.getString(R.string.ms_err_auth)
    e is ServerHttpException && e.code == 0 -> context.getString(R.string.ms_err_unreachable, e.message.orEmpty())
    e is ServerHttpException -> context.getString(R.string.ms_err_http, e.message.orEmpty())
    else -> context.getString(R.string.ms_err_unreachable, e.message ?: e.javaClass.simpleName)
}

/** Broadcasts need the Wi-Fi's multicast lock on many phones. */
internal fun <T> withMulticastLock(context: Context, block: () -> T): T {
    val lock = try {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.createMulticastLock("castapp-mediaservers")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (_: Exception) {
        null
    }
    try {
        return block()
    } finally {
        try { lock?.release() } catch (_: Exception) {}
    }
}

/** Jellyfin / Emby: address, user name and password. */
@Composable
internal fun JellyfinSignInDialog(servers: MediaServers, initialAddress: String, onDone: (ServerAccount) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    var user by rememberSaveable { mutableStateOf("") }
    var trustAll by rememberSaveable { mutableStateOf(false) }
    val passwordState = rememberTextFieldState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val enterAddress = stringResource(R.string.ms_err_enter_address)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.ms_jf_signin_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it; error = null },
                        label = { Text(stringResource(R.string.ms_address)) },
                        placeholder = { Text(stringResource(R.string.ms_address_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it; error = null },
                        label = { Text(stringResource(R.string.ms_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedSecureTextField(
                        state = passwordState,
                        label = { Text(stringResource(R.string.ms_password)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = trustAll, onCheckedChange = { trustAll = it })
                        Text(stringResource(R.string.ms_trust_all), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val normalized = JellyfinAuth.normalizeAddress(address)
                if (normalized.startsWith("http://") && !JellyfinAuth.isPrivateHost(normalized)) {
                    item {
                        Text(stringResource(R.string.ms_http_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                item {
                    Text(
                        stringResource(R.string.ms_token_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && address.isNotBlank() && user.isNotBlank(),
                onClick = {
                    if (JellyfinAuth.normalizeAddress(address).isEmpty()) {
                        error = enterAddress
                        return@TextButton
                    }
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val account = withContext(Dispatchers.IO) { servers.signInJellyfin(address, user, passwordState.text.toString(), trustAll) }
                            onDone(account)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = errorMessage(context, e)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.ms_sign_in))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Plex: shows the four-letter code for plex.tv/link and waits until it was entered there. */
@Composable
internal fun PlexSignInDialog(servers: MediaServers, onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf<PlexTv.Pin?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val expired = stringResource(R.string.ms_plex_code_expired)

    LaunchedEffect(attempt) {
        error = null
        pin = null
        try {
            val tv = servers.plexTv
            val created = withContext(Dispatchers.IO) { tv.createPin() }
            pin = created
            when (val result = PlexPinFlow(tv).waitForToken(created)) {
                is PinResult.Token -> {
                    val name = withContext(Dispatchers.IO) { tv.username(result.token) }
                    servers.savePlexSignIn(result.token, name)
                    onDone()
                }
                PinResult.Expired -> error = expired
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorMessage(context, e)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ms_plex_code_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val current = pin
                if (current == null && error == null) {
                    CircularProgressIndicator()
                } else if (current != null && error == null) {
                    Text(stringResource(R.string.ms_plex_code_text), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        current.code,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.ms_plex_code_waiting), style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (error != null) {
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.ms_try_again)) }
            } else {
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://plex.tv/link".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                    }
                }) { Text(stringResource(R.string.ms_plex_open_link)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
