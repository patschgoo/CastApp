package io.github.patschgoo.castapp.support

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.patschgoo.castapp.R
import kotlinx.coroutines.launch

/** Settings entry "Test this phone". */
@Composable
internal fun SelfTestItem() {
    var show by remember { mutableStateOf(false) }
    ListItem(
        onClick = { show = true },
        supportingContent = { Text(stringResource(R.string.selftest_summary)) },
    ) { Text(stringResource(R.string.selftest_title)) }
    if (show) SelfTestSheet(onDismiss = { show = false })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SelfTestSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var round by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<SelfItem>?>(null) }
    LaunchedEffect(round) {
        items = null
        items = SelfTest.run(context)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.selftest_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            val list = items
            if (list == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.selftest_running))
                }
            } else {
                val (ok, warn, fail) = SelfTest.counts(list)
                Text(
                    stringResource(R.string.selftest_counts, ok, warn, fail),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.selftest_logged),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                for (item in list) {
                    SelfItemRow(item)
                    Spacer(Modifier.height(12.dp))
                }
                val subject = stringResource(R.string.send_log_subject)
                val failed = stringResource(R.string.send_log_failed)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { round++ }) { Text(stringResource(R.string.selftest_again)) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                val file = DebugLog.createReport(context)
                                context.startActivity(DebugLog.shareIntent(context, file, subject))
                            } catch (e: Exception) {
                                Toast.makeText(context, failed + ": " + e.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text(stringResource(R.string.selftest_send)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.support_close)) }
                }
            }
        }
    }
}

@Composable
private fun SelfItemRow(item: SelfItem) {
    val (icon, tint) = when (item.status) {
        SelfStatus.FAIL -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
        SelfStatus.WARN -> Icons.Rounded.Warning to MaterialTheme.colorScheme.tertiary
        SelfStatus.PASS -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
    }
    val label = stringResource(
        when (item.status) {
            SelfStatus.PASS -> R.string.selftest_pass
            SelfStatus.WARN -> R.string.selftest_warn
            SelfStatus.FAIL -> R.string.selftest_fail
        },
    )
    // One stop for TalkBack: "OK: H.264 video …". The result is also written out, not only coloured.
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                label + ": " + stringResource(titleOf(item.check)),
                style = MaterialTheme.typography.titleSmall,
            )
            explanationOf(item.why)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun titleOf(c: SelfCheck): Int = when (c) {
    SelfCheck.H264 -> R.string.selftest_t_h264
    SelfCheck.HEVC -> R.string.selftest_t_hevc
    SelfCheck.AAC -> R.string.selftest_t_aac
    SelfCheck.SCREEN_CAPTURE -> R.string.selftest_t_screen_capture
    SelfCheck.APP_AUDIO -> R.string.selftest_t_app_audio
    SelfCheck.NOTIFICATIONS -> R.string.selftest_t_notifications
    SelfCheck.BATTERY -> R.string.selftest_t_battery
    SelfCheck.INFRARED -> R.string.selftest_t_infrared
    SelfCheck.BLUETOOTH -> R.string.selftest_t_bluetooth
    SelfCheck.NETWORK -> R.string.selftest_t_network
    SelfCheck.MULTICAST -> R.string.selftest_t_multicast
    SelfCheck.PORT -> R.string.selftest_t_port
    SelfCheck.MODELS -> R.string.selftest_t_models
}

/** The plain-words explanation for a result key from [SelfTest.evaluate]. */
internal fun explanationOf(why: String): Int? = when (why) {
    "h264_ok" -> R.string.selftest_w_h264_ok
    "h264_missing" -> R.string.selftest_w_h264_missing
    "hevc_ok" -> R.string.selftest_w_hevc_ok
    "hevc_missing" -> R.string.selftest_w_hevc_missing
    "aac_ok" -> R.string.selftest_w_aac_ok
    "aac_missing" -> R.string.selftest_w_aac_missing
    "capture_ok" -> R.string.selftest_w_capture_ok
    "capture_missing" -> R.string.selftest_w_capture_missing
    "appaudio_ok" -> R.string.selftest_w_appaudio_ok
    "appaudio_old" -> R.string.selftest_w_appaudio_old
    "notif_ok" -> R.string.selftest_w_notif_ok
    "notif_off" -> R.string.selftest_w_notif_off
    "battery_ok" -> R.string.selftest_w_battery_ok
    "battery_limited" -> R.string.selftest_w_battery_limited
    "ir_ok" -> R.string.selftest_w_ir_ok
    "ir_none" -> R.string.selftest_w_ir_none
    "bt_ok" -> R.string.selftest_w_bt_ok
    "bt_none" -> R.string.selftest_w_bt_none
    "bt_no_hid" -> R.string.selftest_w_bt_no_hid
    "bt_unknown" -> R.string.selftest_w_bt_unknown
    "net_ok" -> R.string.selftest_w_net_ok
    "net_vpn" -> R.string.selftest_w_net_vpn
    "net_cellular" -> R.string.selftest_w_net_cellular
    "net_none" -> R.string.selftest_w_net_none
    "multicast_ok" -> R.string.selftest_w_multicast_ok
    "multicast_fail" -> R.string.selftest_w_multicast_fail
    "port_free" -> R.string.selftest_w_port_free
    "port_ours" -> R.string.selftest_w_port_ours
    "port_busy" -> R.string.selftest_w_port_busy
    "models_ok" -> R.string.selftest_w_models_ok
    "models_none" -> R.string.selftest_w_models_none
    else -> null
}
