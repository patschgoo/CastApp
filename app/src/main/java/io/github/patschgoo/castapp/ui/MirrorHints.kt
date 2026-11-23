package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.discovery.MirrorLatency
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastState

/**
 * Mirroring through Google Cast to a box that can do better: CastApp TV on the same box
 * (one tap moves the mirroring there), or an Android TV box without CastApp yet (a hint).
 */
@Composable
internal fun FasterMirroringHint(state: CastState.Active, actions: DevicesActions) {
    if (state.kind != CastKind.SCREEN || state.audioOnly || state.lowLatencyPlayer) return
    val app = CastApp.get(LocalContext.current)
    val entries by app.devices.devices.collectAsStateWithLifecycle()
    val twin = MirrorLatency.fasterTwin(state.device, entries.filter { it.online }.map { it.device })
    val installHint = twin == null && MirrorLatency.couldRunCastAppTv(state.device)
    if (twin == null && !installHint) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.mode_faster_title, state.device.name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (twin != null) R.string.mode_faster_text else R.string.mode_faster_install),
                style = MaterialTheme.typography.bodySmall,
            )
            if (twin != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { if (state.singleApp) actions.onCastApp(twin) else actions.onMirror(twin) }) {
                    Text(stringResource(R.string.mode_faster_switch))
                }
            }
        }
    }
}
