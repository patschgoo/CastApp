package io.github.patschgoo.castapp.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import io.github.patschgoo.castapp.wearproto.NowPlaying
import io.github.patschgoo.castapp.wearproto.WearCommand

/** The watch remote: what the phone casts right now, and the buttons to control it. */
class WearActivity : ComponentActivity() {
    private lateinit var link: PhoneLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = PhoneLink(this)
        setContent {
            val state by link.state.collectAsStateWithLifecycle()
            WearRemote(state, onCommand = link::send, onRetry = link::refresh, onMirrorNoticeShown = link::clearMirrorNotice)
        }
    }

    override fun onStart() {
        super.onStart()
        link.start()
    }

    override fun onStop() {
        link.stop()
        super.onStop()
    }
}

@Composable
fun WearRemote(
    state: WatchState,
    onCommand: (WearCommand) -> Unit,
    onRetry: () -> Unit,
    onMirrorNoticeShown: () -> Unit,
) {
    MaterialTheme {
        AppScaffold {
            val listState = rememberScalingLazyListState()
            ScreenScaffold(scrollState = listState) { padding ->
                ScalingLazyColumn(
                    state = listState,
                    contentPadding = padding,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    when (state.link) {
                        Link.SEARCHING -> item { Message(stringResource(R.string.nothing_playing)) }
                        Link.NO_SERVICES -> item { Message(stringResource(R.string.no_play_services)) }
                        Link.NO_PHONE -> {
                            item { Message(stringResource(R.string.no_phone), strong = true) }
                            item { Message(stringResource(R.string.no_phone_hint)) }
                            item { FilledTonalButton(onClick = onRetry, label = { Text(stringResource(R.string.retry)) }) }
                        }
                        Link.CONNECTED -> remoteItems(state, onCommand, onMirrorNoticeShown)
                    }
                }
            }
        }
    }
}

private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.remoteItems(
    state: WatchState,
    onCommand: (WearCommand) -> Unit,
    onMirrorNoticeShown: () -> Unit,
) {
    val now = state.playing
    item { Headline(now) }
    if (now.active) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteButton(R.drawable.ic_w_previous, R.string.action_previous, enabled = !now.mirroring) { onCommand(WearCommand.PREVIOUS) }
                RemoteButton(
                    if (now.paused) R.drawable.ic_w_play else R.drawable.ic_w_pause,
                    if (now.paused) R.string.action_play else R.string.action_pause,
                    enabled = !now.mirroring,
                ) { onCommand(WearCommand.PLAY_PAUSE) }
                RemoteButton(R.drawable.ic_w_next, R.string.action_next, enabled = !now.mirroring) { onCommand(WearCommand.NEXT) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteButton(R.drawable.ic_w_volume_down, R.string.action_volume_down) { onCommand(WearCommand.VOLUME_DOWN) }
                RemoteButton(R.drawable.ic_w_stop, R.string.action_stop) { onCommand(WearCommand.STOP) }
                RemoteButton(R.drawable.ic_w_volume_up, R.string.action_volume_up) { onCommand(WearCommand.VOLUME_UP) }
            }
        }
        if (now.volumePercent >= 0) {
            item { Message(stringResource(R.string.volume_percent, now.volumePercent)) }
        }
    }
    item {
        Button(
            onClick = {
                onCommand(WearCommand.MIRROR_LAST)
            },
            icon = { Icon(painterResource(R.drawable.ic_w_cast), contentDescription = null, modifier = Modifier.size(24.dp)) },
            label = {
                Text(
                    if (now.lastTv.isNotEmpty()) stringResource(R.string.mirror_to, now.lastTv) else stringResource(R.string.mirror_pick_on_phone),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            secondaryLabel = if (state.mirrorRequested) {
                { Text(stringResource(R.string.mirror_sent)) }
            } else null,
        )
    }
    if (state.mirrorRequested) {
        item { MirrorNoticeTimer(onMirrorNoticeShown) }
    }
}

@Composable
private fun MirrorNoticeTimer(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(5_000)
        onDone()
    }
}

@Composable
private fun Headline(now: NowPlaying) {
    val title = when {
        !now.active -> stringResource(R.string.nothing_playing)
        now.mirroring -> stringResource(R.string.mirroring_screen)
        now.title.isNotEmpty() -> now.title
        else -> now.device
    }
    val subtitle = when {
        !now.active -> ""
        now.paused -> stringResource(R.string.paused_on, now.device)
        else -> stringResource(R.string.playing_on, now.device)
    }
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (now.active && now.hasQueue) {
            Text(
                stringResource(R.string.queue_position, now.queueIndex + 1, now.queueSize),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RemoteButton(icon: Int, description: Int, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), contentDescription = stringResource(description))
    }
}

@Composable
private fun Message(text: String, strong: Boolean = false) {
    Text(
        text,
        style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
