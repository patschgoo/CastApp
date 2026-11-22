package io.github.patschgoo.castapp.support

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.BuildConfig
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.discovery.CastDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Settings → Help & support ------------------------------------------------------------

/** The "Help & support" section of the settings list. */
fun LazyListScope.supportSettingsSection() {
    item { SupportTitle(stringResource(R.string.support_section)) }
    item { SendLogItem() }
    item { NetworkCheckItem() }
    item { SelfTestItem() }
    item { BatteryItem() }
    item { UpdateItems() }
    item { DoNotDisturbItem() }
}

@Composable
private fun SupportTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SendLogItem() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val subject = stringResource(R.string.send_log_subject)
    val failed = stringResource(R.string.send_log_failed)
    ListItem(
        onClick = {
            if (busy) return@ListItem
            busy = true
            scope.launch {
                try {
                    val file = DebugLog.createReport(context)
                    context.startActivity(DebugLog.shareIntent(context, file, subject))
                } catch (e: Exception) {
                    Toast.makeText(context, failed + ": " + e.message, Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        },
        supportingContent = { Text(stringResource(if (busy) R.string.send_log_preparing else R.string.send_log_summary)) },
    ) { Text(stringResource(R.string.send_log_title)) }
}

@Composable
private fun NetworkCheckItem() {
    var show by remember { mutableStateOf(false) }
    ListItem(
        onClick = { show = true },
        supportingContent = { Text(stringResource(R.string.network_check_summary)) },
    ) { Text(stringResource(R.string.network_check_title)) }
    if (show) NetworkCheckSheet(onDismiss = { show = false })
}

@Composable
private fun BatteryItem() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(BatteryHelp.isIgnoring(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = BatteryHelp.isIgnoring(context) }
    ListItem(
        onClick = { BatteryPrompt.showFromSettings() },
        supportingContent = { Text(stringResource(if (exempt) R.string.battery_summary_exempt else R.string.battery_summary_limited)) },
    ) { Text(stringResource(R.string.battery_title)) }
}

@Composable
private fun UpdateItems() {
    val context = LocalContext.current
    var auto by remember { mutableStateOf(SupportPrefs.of(context).getBoolean(SupportPrefs.AUTO_UPDATE_CHECK, true)) }
    ListItem(
        onClick = { Updates.checkNow(context) },
        supportingContent = { Text(stringResource(R.string.check_updates_summary)) },
    ) { Text(stringResource(R.string.check_updates_title) + " (${BuildConfig.VERSION_NAME})") }
    ListItem(
        onClick = {
            auto = !auto
            SupportPrefs.of(context).edit().putBoolean(SupportPrefs.AUTO_UPDATE_CHECK, auto).apply()
        },
        trailingContent = {
            Switch(checked = auto, onCheckedChange = null)
        },
        supportingContent = { Text(stringResource(R.string.auto_update_summary)) },
    ) { Text(stringResource(R.string.auto_update_title)) }
}

@Composable
private fun DoNotDisturbItem() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(DoNotDisturb.enabled(context)) }
    var access by remember { mutableStateOf(DoNotDisturb.hasAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = DoNotDisturb.hasAccess(context) }
    fun askAccess() {
        Toast.makeText(context, R.string.dnd_access_hint, Toast.LENGTH_LONG).show()
        try { context.startActivity(DoNotDisturb.accessIntent()) } catch (_: Exception) {}
    }
    ListItem(
        onClick = {
            on = !on
            DoNotDisturb.setEnabled(context, on)
            if (on && !access) askAccess()
        },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.dnd_summary))
                if (on && !access) {
                    TextButton(onClick = { askAccess() }) { Text(stringResource(R.string.dnd_needs_access) + ": " + stringResource(R.string.dnd_grant)) }
                }
            }
        },
    ) { Text(stringResource(R.string.dnd_title)) }
}

// ---- Dialogs shown over the whole app ---------------------------------------------------------

/**
 * Hosts what the support tools show over the app: the update dialog, the battery
 * dialog, and the once-a-day update check at start. Put it once into the main screen.
 */
@Composable
fun SupportHost() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Updates.checkOnStart(context) }
    UpdateDialog()
    if (BatteryPrompt.pending.collectAsStateWithLifecycle().value) BatteryDialog()
}

@Composable
private fun BatteryDialog() {
    val context = LocalContext.current
    val exempt = BatteryHelp.isIgnoring(context)
    val brand = BatteryHelp.currentBrand()
    AlertDialog(
        onDismissRequest = { BatteryPrompt.finished(context) },
        title = { Text(stringResource(R.string.battery_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(if (exempt) R.string.battery_already else R.string.battery_dialog_text), style = MaterialTheme.typography.bodyMedium)
                brand.tipRes?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.battery_tips_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (!exempt) {
                TextButton(onClick = {
                    BatteryPrompt.finished(context)
                    try {
                        context.startActivity(BatteryHelp.requestIntent(context))
                    } catch (_: Exception) {
                        try { context.startActivity(BatteryHelp.listIntent()) } catch (_: Exception) {}
                    }
                }) { Text(stringResource(R.string.battery_allow)) }
            } else {
                TextButton(onClick = { BatteryPrompt.finished(context) }) { Text(stringResource(R.string.support_close)) }
            }
        },
        dismissButton = {
            if (!exempt) TextButton(onClick = { BatteryPrompt.finished(context) }) { Text(stringResource(R.string.battery_not_now)) }
        },
    )
}

@Composable
private fun UpdateDialog() {
    val context = LocalContext.current
    val state = Updates.ui.collectAsStateWithLifecycle().value ?: return
    fun openReleases(url: String = Updates.RELEASES_PAGE) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
    when (state) {
        UpdateUi.Checking -> AlertDialog(
            onDismissRequest = { Updates.dismiss() },
            title = { Text(stringResource(R.string.check_updates_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.update_checking))
                }
            },
            confirmButton = { TextButton(onClick = { Updates.dismiss() }) { Text(stringResource(R.string.cancel)) } },
        )
        is UpdateUi.UpToDate -> AlertDialog(
            onDismissRequest = { Updates.dismiss() },
            title = { Text(stringResource(R.string.update_uptodate_title)) },
            text = { Text(stringResource(R.string.update_uptodate_text, state.version)) },
            confirmButton = { TextButton(onClick = { Updates.dismiss() }) { Text(stringResource(R.string.support_close)) } },
        )
        UpdateUi.NoRelease -> AlertDialog(
            onDismissRequest = { Updates.dismiss() },
            title = { Text(stringResource(R.string.update_norelease_title)) },
            text = { Text(stringResource(R.string.update_norelease_text)) },
            confirmButton = { TextButton(onClick = { Updates.dismiss(); openReleases() }) { Text(stringResource(R.string.update_open_releases)) } },
            dismissButton = { TextButton(onClick = { Updates.dismiss() }) { Text(stringResource(R.string.support_close)) } },
        )
        is UpdateUi.Failed -> AlertDialog(
            onDismissRequest = { Updates.dismiss() },
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(state.reason) },
            confirmButton = { TextButton(onClick = { Updates.dismiss(); openReleases() }) { Text(stringResource(R.string.update_open_releases)) } },
            dismissButton = { TextButton(onClick = { Updates.dismiss() }) { Text(stringResource(R.string.support_close)) } },
        )
        is UpdateUi.Available -> AlertDialog(
            onDismissRequest = { Updates.dismiss() },
            title = { Text(stringResource(R.string.update_title_available, state.release.version)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.update_current, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(state.release.notes.ifEmpty { stringResource(R.string.update_no_notes) }, style = MaterialTheme.typography.bodyMedium)
                    if (state.release.apkUrl == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.update_no_apk), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                if (state.release.apkUrl != null) {
                    TextButton(onClick = { Updates.downloadAndInstall(context, state.release) }) { Text(stringResource(R.string.update_install)) }
                } else {
                    TextButton(onClick = { Updates.dismiss(); openReleases(state.release.pageUrl) }) { Text(stringResource(R.string.update_open_releases)) }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { Updates.dismiss(skipVersion = true, context = context) }) { Text(stringResource(R.string.update_skip)) }
                    TextButton(onClick = { Updates.dismiss() }) { Text(stringResource(R.string.update_later)) }
                }
            },
        )
        is UpdateUi.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading, state.release.version)) },
            text = {
                if (state.total > 0) LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {},
        )
        is UpdateUi.NeedsInstallPermission -> AlertDialog(
            onDismissRequest = { Updates.dismiss() },
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_text)) },
            confirmButton = {
                TextButton(onClick = {
                    Updates.openInstallPermission(context)
                    Updates.dismiss()
                }) { Text(stringResource(R.string.update_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { Updates.dismiss() }) { Text(stringResource(R.string.support_close)) } },
        )
    }
}

// ---- Network check ------------------------------------------------------------------------------

/** The "Why can't I find my TV?" button for the help card. */
@Composable
fun NetworkCheckButton() {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }) { Text(stringResource(R.string.check_button)) }
    if (show) NetworkCheckSheet(onDismiss = { show = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkCheckSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var round by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<NetworkMeasurements?>(null) }
    LaunchedEffect(round) {
        result = null
        CastApp.get(context).devices.refresh()
        result = NetworkProbe.measure(context)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.check_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            val m = result
            if (m == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.check_running))
                }
            } else {
                m.phoneAddress?.let {
                    Text(stringResource(R.string.check_phone_address, it + "/" + m.prefixLength), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                for (f in NetworkCheck.analyze(m)) {
                    FindingRow(f)
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { round++ }) { Text(stringResource(R.string.check_again)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.support_close)) }
                }
            }
        }
    }
}

@Composable
private fun FindingRow(f: Finding) {
    val (title, text) = findingTexts(f)
    val (icon, tint) = when (f.severity) {
        Severity.PROBLEM -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
        Severity.WARNING -> Icons.Rounded.Warning to MaterialTheme.colorScheme.tertiary
        Severity.INFO -> Icons.Rounded.Info to MaterialTheme.colorScheme.onSurfaceVariant
        Severity.OK -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            val body = if (f.detail != null && f.code == FindingCode.DEVICES_FOUND) f.detail else if (text != null) stringResource(text, f.detail ?: "") else null
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The string resources (title, text) that explain a finding; the text can be null. */
private fun findingTexts(f: Finding): Pair<Int, Int?> = when (f.code) {
    FindingCode.NO_NETWORK -> R.string.finding_no_network_title to R.string.finding_no_network_text
    FindingCode.MOBILE_DATA -> R.string.finding_mobile_data_title to R.string.finding_mobile_data_text
    FindingCode.NO_LOCAL_ADDRESS -> R.string.finding_no_local_address_title to R.string.finding_no_local_address_text
    FindingCode.VPN_ACTIVE -> R.string.finding_vpn_title to R.string.finding_vpn_text
    FindingCode.ROUTER_SILENT -> R.string.finding_router_title to R.string.finding_router_text
    FindingCode.NOTHING_ANSWERED -> R.string.finding_nothing_title to R.string.finding_nothing_text
    FindingCode.MDNS_SILENT -> R.string.finding_mdns_title to R.string.finding_mdns_text
    FindingCode.SSDP_SILENT -> R.string.finding_ssdp_title to R.string.finding_ssdp_text
    FindingCode.DEVICES_FOUND -> R.string.finding_found_title to null
    FindingCode.SAVED_UNREACHABLE -> R.string.finding_saved_off_title to R.string.finding_saved_off_text
    FindingCode.SAVED_OTHER_SUBNET -> R.string.finding_saved_subnet_title to R.string.finding_saved_subnet_text
    FindingCode.SAVED_REACHABLE_NOT_FOUND -> R.string.finding_saved_silent_title to R.string.finding_saved_silent_text
    FindingCode.DATA_SAVER -> R.string.finding_data_saver_title to R.string.finding_data_saver_text
    FindingCode.BATTERY_SAVER -> R.string.finding_battery_saver_title to R.string.finding_battery_saver_text
    FindingCode.ALL_GOOD -> R.string.finding_ok_title to R.string.finding_ok_text
}

// ---- Wake-on-LAN in the device sheet ------------------------------------------------------------

private val wakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** "Wake up" and "MAC address" entries for the device sheet of a remembered TV. */
@Composable
fun WakeOnLanSheetItems(device: CastDevice) {
    val context = LocalContext.current
    val repo = CastApp.get(context).devices
    val mac = device.mac
    var editing by remember { mutableStateOf(false) }

    // Samsung TVs report their MAC address; fill it in once.
    LaunchedEffect(device.id, mac) {
        if (mac == null) WakeOnLan.discoverMac(device)?.let { repo.setMac(device, it, onlyIfSaved = true) }
    }

    if (mac != null) {
        WakeItem(Icons.Rounded.Power, stringResource(R.string.wol_wake), stringResource(R.string.wol_wake_hint, device.name)) {
            Toast.makeText(context, R.string.wol_sent, Toast.LENGTH_SHORT).show()
            wakeScope.launch {
                WakeOnLan.wake(mac)
                repo.refresh()
                // A TV needs some seconds to start; search again while it does.
                delay(6000); repo.refresh()
                delay(10000); repo.refresh()
            }
        }
        WakeItem(null, stringResource(R.string.wol_edit_mac, mac), stringResource(R.string.wol_edit_hint)) { editing = true }
    } else {
        WakeItem(Icons.Rounded.Power, stringResource(R.string.wol_set_mac), stringResource(R.string.wol_set_hint)) { editing = true }
    }

    if (editing) MacDialog(
        initial = mac.orEmpty(),
        onSave = { repo.setMac(device, it); editing = false },
        onRemove = { repo.setMac(device, null); editing = false },
        onDismiss = { editing = false },
        canRemove = mac != null,
    )
}

@Composable
private fun WakeItem(icon: ImageVector?, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 8.dp),
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        supportingContent = { Text(subtitle) },
    ) { Text(title) }
}

@Composable
private fun MacDialog(initial: String, canRemove: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val normalized = WakeOnLan.normalizeMac(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wol_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.wol_dialog_label)) },
                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                singleLine = true,
                isError = text.isNotBlank() && normalized == null,
                supportingText = { if (text.isNotBlank() && normalized == null) Text(stringResource(R.string.wol_dialog_invalid)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { normalized?.let(onSave) }, enabled = normalized != null) { Text(stringResource(R.string.wol_save)) } },
        dismissButton = {
            Row {
                if (canRemove) TextButton(onClick = onRemove) { Text(stringResource(R.string.wol_remove), color = Color.Unspecified) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
