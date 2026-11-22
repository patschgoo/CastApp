package io.github.patschgoo.castapp.iptv

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.Role

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.ui.RemoteImage
import kotlinx.coroutines.delay
import java.util.Date

/** A channel with its programmes (empty when the guide knows nothing about it). */
internal class ChannelEntry(val channel: Channel, val schedule: List<Programme>) {
    fun currentAt(now: Long): Programme? = schedule.firstOrNull { it.isOn(now) }

    /** The first programme whose title contains [query]: still running or later, else the first one. */
    fun matching(query: String, now: Long): Programme? =
        schedule.firstOrNull { it.stop > now && it.title.contains(query, ignoreCase = true) }
            ?: schedule.firstOrNull { it.title.contains(query, ignoreCase = true) }
}

/** The current time, refreshed every half minute so progress bars move. */
@Composable
internal fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(30_000)
        value = System.currentTimeMillis()
    }
}

/** Formats a time of day the way the phone is set (12 / 24 hours). */
@Composable
internal fun rememberTimeFormat(): (Long) -> String {
    val context = LocalContext.current
    return remember(context) {
        val fmt = android.text.format.DateFormat.getTimeFormat(context)
        val f: (Long) -> String = { ms -> fmt.format(Date(ms)) }
        f
    }
}

/** What is on now with a thin progress bar, and what comes next. */
@Composable
internal fun NowNext(schedule: List<Programme>, now: Long, time: (Long) -> String) {
    val current = schedule.firstOrNull { it.isOn(now) }
    val next = schedule.firstOrNull { it.start > now && it.start >= (current?.stop ?: now) }
    if (current == null && next == null) return
    Column {
        if (current != null) {
            Text(
                "${time(current.start)} ${current.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { current.progress(now) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).height(3.dp),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        if (next != null) {
            Text(
                stringResource(R.string.iptvg_next, time(next.start), next.title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The "Guide" view: for each channel that has programme information, what is on now
 * and the next ones. Tapping the channel plays it; tapping a programme opens its schedule.
 */
@Composable
internal fun GuideList(
    entries: List<ChannelEntry>,
    now: Long,
    onPlay: (ChannelEntry) -> Unit,
    onInfo: (ChannelEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val time = rememberTimeFormat()
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = modifier) {
        itemsIndexed(entries, key = { i, e -> "$i:${e.channel.url}" }) { _, entry ->
            val upcoming = remember(entry, now / 60_000) { entry.schedule.filter { it.stop > now }.take(5) }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = stringResource(R.string.polish_play), role = Role.Button) { onPlay(entry) }.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelLogo(entry.channel, 32)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        entry.channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.polish_schedule), role = Role.Button) { onInfo(entry) }.padding(start = 60.dp, end = 16.dp)) {
                    for (p in upcoming) {
                        val current = p.isOn(now)
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                time(p.start),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(min = 52.dp).padding(end = 4.dp),
                            )
                            Text(
                                p.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (current) {
                            LinearProgressIndicator(
                                progress = { p.progress(now) },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp).height(3.dp),
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
internal fun ChannelLogo(channel: Channel, size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        RemoteImage(channel.logo, Modifier.size((size - 4).dp), sizePx = 96) {
            Icon(Icons.Rounded.LiveTv, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size((size / 2).dp))
        }
    }
}

/**
 * The programme of one channel over the days the guide covers. Past programmes can be
 * played again when the provider keeps them ([catchUpDays] > 0): [onReplay] gets the programme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSheet(
    entry: ChannelEntry,
    now: Long,
    catchUpDays: Int,
    onPlayLive: () -> Unit,
    onReplay: (Programme) -> Unit,
    onDismiss: () -> Unit,
) {
    val time = rememberTimeFormat()
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    // Day headers between the programmes.
    val rows = remember(entry, context) {
        buildList<Any> {
            var day = ""
            for (p in entry.schedule) {
                val d = DateUtils.formatDateTime(context, p.start, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
                if (d != day) {
                    day = d
                    add(d)
                }
                add(p)
            }
        }
    }
    val firstCurrent = remember(rows, now / 60_000) {
        rows.indexOfFirst { it is Programme && it.stop > now }.coerceAtLeast(0)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(entry.channel, 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (catchUpDays > 0) stringResource(R.string.iptvg_replay_hint) else stringResource(R.string.iptvg_schedule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onPlayLive) { Text(stringResource(R.string.iptvg_watch_live)) }
        }
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.iptvg_no_schedule),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (firstCurrent - 1).coerceAtLeast(0))
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.navigationBarsPadding()) {
                itemsIndexed(rows, key = { i, r -> if (r is Programme) "p${r.start}:$i" else "d$i" }) { _, r ->
                    if (r is String) {
                        Text(
                            r,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    } else if (r is Programme) {
                        val past = r.stop <= now
                        val replayable = past && catchUpDays > 0 && r.start >= now - catchUpDays * DAY_MS
                        val current = r.isOn(now)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    if (replayable) onReplay(r) else if (r.desc != null) {
                                        expanded = if (r.start in expanded) expanded - r.start else expanded + r.start
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                time(r.start),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(56.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = when {
                                        current -> MaterialTheme.colorScheme.primary
                                        past && !replayable -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                if (current) {
                                    LinearProgressIndicator(
                                        progress = { r.progress(now) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(3.dp),
                                        gapSize = 0.dp,
                                        drawStopIndicator = {},
                                    )
                                }
                                r.desc?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = if (r.start in expanded) Int.MAX_VALUE else 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (replayable) {
                                Icon(
                                    Icons.Rounded.History,
                                    contentDescription = stringResource(R.string.iptvg_replay),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp).size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val DAY_MS = 24 * 3600_000L
