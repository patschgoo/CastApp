package io.github.patschgoo.castapp.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.patschgoo.castapp.R
import kotlinx.coroutines.delay

/** Everything the TV player's controls show. The player screen fills it in; this file only draws it. */
internal data class TvPlayerUi(
    val remote: RemoteState = RemoteState(),
    val buttons: List<PlayerButton> = emptyList(),
    val canSeek: Boolean = false,
    val title: String = "",
    /** "Subtitles: German · Sound: Japanese", or empty. */
    val detail: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    /** "Press Back again to stop casting". */
    val backHint: Boolean = false,
    /** The open list's entries, and which of them is in use now. */
    val entries: List<String> = emptyList(),
    val current: Int = 0,
    /** Music: there is no picture, so the title is shown instead. */
    val music: Boolean = false,
)

/**
 * The controls over the video on a TV. They are not focusable: the player screen gets every key
 * of the remote and decides with [TvRemote] what is highlighted, so the remote behaves the same
 * on every box.
 */
@Composable
internal fun TvPlayerOverlay(ui: TvPlayerUi) {
    Box(Modifier.fillMaxSize()) {
        if (ui.music) MusicCard(ui.title)
        if (ui.remote.level != ControlsLevel.HIDDEN) Controls(ui, Modifier.align(Alignment.BottomCenter))
        if (ui.remote.level == ControlsLevel.PANEL && ui.remote.panel != null) TrackList(ui, Modifier.align(Alignment.CenterEnd))
        if (ui.backHint) {
            Text(
                stringResource(R.string.tv_back_again),
                style = tvText(TvSize.label, FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = TvSize.marginTop)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun Controls(ui: TvPlayerUi, modifier: Modifier) {
    val seeking = ui.remote.level == ControlsLevel.BAR
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black.copy(alpha = 0.92f)))
            .padding(start = TvSize.marginSide, end = TvSize.marginSide, top = 72.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (ui.title.isNotBlank()) {
            Text(ui.title, style = tvText(26.sp, FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ui.detail.isNotBlank()) {
            Text(ui.detail, style = tvText(TvSize.value), color = Color(0xFFD4D4D8), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ui.canSeek && ui.durationMs > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(playerClock(ui.positionMs), style = tvText(TvSize.body), color = Color.White)
                val done = (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)
                // Thicker and white-edged while Left and Right move through the file.
                Box(
                    Modifier
                        .weight(1f)
                        .height(if (seeking) 12.dp else 6.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White.copy(alpha = 0.25f))
                        .then(if (seeking) Modifier.border(2.dp, Color.White, RoundedCornerShape(percent = 50)) else Modifier),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(done).background(MaterialTheme.colorScheme.primary))
                }
                Text(playerClock(ui.durationMs), style = tvText(TvSize.body), color = Color.White)
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val focused = ui.remote.button.takeIf { ui.remote.level == ControlsLevel.BUTTONS }
            for (button in ui.buttons) {
                if (button == PlayerButton.STOP) Spacer(Modifier.weight(1f))
                Pill(
                    text = stringResource(
                        when (button) {
                            PlayerButton.PLAY_PAUSE -> if (ui.playing) R.string.tv_btn_pause else R.string.tv_btn_play
                            PlayerButton.SUBTITLES -> R.string.tv_btn_subtitles
                            PlayerButton.SOUND -> R.string.tv_btn_sound
                            PlayerButton.STOP -> R.string.tv_btn_stop
                        },
                    ),
                    focused = button == focused,
                    primary = button == PlayerButton.PLAY_PAUSE,
                )
            }
        }
    }
}

/** A button of the controls as the remote sees it: the highlighted one has the white ring and is lighter. */
@Composable
private fun Pill(text: String, focused: Boolean, primary: Boolean) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .border(3.dp, if (focused) Color.White else Color.Transparent, shape)
            .padding(6.dp)
            .clip(shape)
            .background(if (primary) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = if (focused) 0.28f else 0.14f))
            .heightIn(min = 52.dp)
            .padding(horizontal = 26.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = tvText(TvSize.label, FontWeight.SemiBold),
            color = if (primary) MaterialTheme.colorScheme.onPrimary else Color.White,
            maxLines = 1,
        )
    }
}

/** The list of subtitles or sound tracks, over the right side of the picture. */
@Composable
private fun TrackList(ui: TvPlayerUi, modifier: Modifier) {
    val list = rememberLazyListState()
    val at = ui.remote.panelIndex
    LaunchedEffect(at) { if (at in ui.entries.indices) list.animateScrollToItem((at - 2).coerceAtLeast(0)) }
    Column(
        modifier
            .fillMaxHeight()
            .width(TvSize.panelWidth)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f))
            .padding(horizontal = 32.dp, vertical = TvSize.marginTop),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (ui.remote.panel == PlayerPanel.SUBTITLES) R.string.tv_btn_subtitles else R.string.tv_btn_sound),
            style = tvText(TvSize.title, FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(ui.entries) { index, entry ->
                val shape = RoundedCornerShape(14.dp)
                val focused = index == at
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(3.dp, if (focused) Color.White else Color.Transparent, shape)
                        .padding(6.dp)
                        .clip(shape)
                        .background(if (focused) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow)
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = index == ui.current, onClick = null)
                    Text(entry, style = tvText(TvSize.label, FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(stringResource(R.string.tv_panel_back), style = tvText(TvSize.small), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Music has no picture: its title, moving to another place every half minute so that it
 * doesn't burn into an OLED screen.
 */
@Composable
private fun MusicCard(title: String) {
    val places = listOf(-0.5f to -0.3f, 0.4f to -0.15f, 0.3f to 0.3f, -0.4f to 0.25f)
    val step by produceState(0) {
        while (true) {
            delay(30_000)
            value = (value + 1) % places.size
        }
    }
    val x by animateFloatAsState(places[step].first, tween(4000), label = "x")
    val y by animateFloatAsState(places[step].second, tween(4000), label = "y")
    Box(Modifier.fillMaxSize()) {
        // Over the cover picture, if the file has one: a dark plate keeps the title readable.
        Row(
            Modifier
                .align(BiasAlignment(x, y))
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
            }
            Text(title, style = tvText(TvSize.title, FontWeight.Bold), color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
