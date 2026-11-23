package io.github.patschgoo.castapp.iptv

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.patschgoo.castapp.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val CHANNEL_WIDTH_BASE = 84.dp
private val ROW_HEIGHT_BASE = 58.dp
private val HEADER_HEIGHT_BASE = 34.dp

// The rows grow with the font size (up to twice as tall), so titles are not cut off with large text.
private val CHANNEL_WIDTH: Dp @Composable get() = CHANNEL_WIDTH_BASE * gridScale()
private val ROW_HEIGHT: Dp @Composable get() = ROW_HEIGHT_BASE * gridScale()
private val HEADER_HEIGHT: Dp @Composable get() = HEADER_HEIGHT_BASE * gridScale()

@Composable
private fun gridScale(): Float = LocalDensity.current.fontScale.coerceIn(1f, 2f)

/** How far the "earlier / later" buttons scroll. */
private const val SCROLL_STEP_MS = 2 * GridSpec.HOUR_MS

/**
 * The guide as a grid: channels down the side (they stay while the programmes scroll), time across in
 * 30-minute columns, a line at "now". All rows share one horizontal scroll; only the programmes
 * that are on screen are composed, so hundreds of channels stay smooth.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GuideGrid(
    entries: List<ChannelEntry>,
    now: Long,
    reminders: List<Reminder>,
    recordings: List<Recording>,
    onPlay: (ChannelEntry) -> Unit,
    onProgramme: (ChannelEntry, Programme) -> Unit,
    onChannelMenu: (ChannelEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val time = rememberTimeFormat()
    // The grid's time frame is fixed while the list stays the same, so it does not jump under the finger.
    val spec = remember(entries) { GridSpec.covering(entries.asSequence().map { it.schedule }, System.currentTimeMillis()) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val slotPx = with(density) { spec.slotDp.dp.toPx() }
    val dpPx = with(density) { 1.dp.toPx() }

    val reminded = remember(reminders) { reminders.mapTo(HashSet()) { "${it.channelUrl}|${it.startMs}" } }
    val recorded = remember(recordings) {
        recordings.filter { it.isActive && it.programmeStartMs > 0 }.mapTo(HashSet()) { "${it.channelUrl}|${it.programmeStartMs}" }
    }

    fun scrollTarget(timeMs: Long, viewportPx: Float): Int {
        val dp = spec.scrollFor(timeMs, viewportPx / dpPx, 0f)
        return (dp * dpPx).toInt()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportDp = maxWidth - CHANNEL_WIDTH
        val viewportPx = with(density) { viewportDp.toPx() }
        val viewportSlots = kotlin.math.ceil(viewportDp.value / spec.slotDp).toInt() + 1

        // Start a little before "now".
        LaunchedEffect(spec) {
            snapshotFlow { scroll.maxValue }.first { it > 0 }
            scroll.scrollTo(scrollTarget(now - GridSpec.SLOT_MS, viewportPx))
        }

        Column(Modifier.fillMaxSize()) {
            // The day at the left edge of the grid, and the buttons.
            val leftTime by remember(scroll, spec, dpPx) { derivedStateOf { spec.timeAt(scroll.value / dpPx) } }
            var dayMenu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The day takes what the buttons leave (cut short with a large font, never the buttons).
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { dayMenu = true }) {
                        Text(
                            DateUtils.formatDateTime(context, leftTime, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }) {
                        for (day in remember(spec) { spec.dayStarts() }) {
                            DropdownMenuItem(
                                text = {
                                    Text(DateUtils.formatDateTime(context, day, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL))
                                },
                                onClick = {
                                    dayMenu = false
                                    // The day that has "now" in it starts at "now".
                                    val target = if (DateUtils.isToday(day) && now > day) now - GridSpec.SLOT_MS else day
                                    scope.launch { scroll.animateScrollTo(scrollTarget(target, viewportPx)) }
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = { scope.launch { scroll.animateScrollTo((scroll.value - SCROLL_STEP_MS * spec.dpPerMs * dpPx).toInt().coerceAtLeast(0)) } }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = stringResource(R.string.iptvx_grid_earlier))
                }
                TextButton(onClick = { scope.launch { scroll.animateScrollTo(scrollTarget(now - GridSpec.SLOT_MS, viewportPx)) } }) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.iptvx_grid_now), maxLines = 1, softWrap = false)
                }
                IconButton(onClick = { scope.launch { scroll.animateScrollTo((scroll.value + SCROLL_STEP_MS * spec.dpPerMs * dpPx).toInt().coerceAtMost(scroll.maxValue)) } }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.iptvx_grid_later))
                }
            }

            // Time header, scrolls with the rows.
            Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
                Spacer(Modifier.width(CHANNEL_WIDTH))
                Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                    spec.nowX(now)?.let { x ->
                        Text(
                            stringResource(R.string.polish_now_marker),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            maxLines = 1,
                            modifier = Modifier.offset(x = x.dp).align(Alignment.BottomStart)
                                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                                .background(MaterialTheme.colorScheme.error)
                                .padding(horizontal = 4.dp),
                        )
                    }
                    Row {
                        for (i in 0 until spec.slotCount) {
                            val t = spec.slotStart(i)
                            Box(Modifier.width(spec.slotDp.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                                Text(
                                    time(t),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 6.dp),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(entries, key = { i, e -> "$i:${e.channel.url}" }) { _, entry ->
                        GridRow(
                            entry = entry,
                            spec = spec,
                            scroll = scroll,
                            slotPx = slotPx,
                            viewportSlots = viewportSlots,
                            now = now,
                            time = time,
                            reminded = reminded,
                            recorded = recorded,
                            onPlay = { onPlay(entry) },
                            onMenu = { onChannelMenu(entry) },
                            onProgramme = { onProgramme(entry, it) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
                // The line at "now" over all rows (drawn, not composed, so scrolling does not recompose it).
                val lineColor = MaterialTheme.colorScheme.error
                val nowDp = spec.nowX(now)
                if (nowDp != null) {
                    Box(
                        Modifier.matchParentSize().padding(start = CHANNEL_WIDTH).clipToBounds().drawBehind {
                            val x = nowDp * dpPx - scroll.value
                            if (x in 0f..size.width) drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridRow(
    entry: ChannelEntry,
    spec: GridSpec,
    scroll: androidx.compose.foundation.ScrollState,
    slotPx: Float,
    viewportSlots: Int,
    now: Long,
    time: (Long) -> String,
    reminded: Set<String>,
    recorded: Set<String>,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    onProgramme: (Programme) -> Unit,
) {
    val cells = remember(entry, spec) { spec.cellsFor(entry.schedule) }
    // Only this changes while scrolling, once per column: the programmes to compose.
    val firstSlot by remember(scroll, slotPx) { derivedStateOf { (scroll.value / slotPx).toInt() } }
    val visible = remember(cells, firstSlot, viewportSlots) {
        spec.visible(cells, (firstSlot - 1) * spec.slotDp, (firstSlot + viewportSlots + 1) * spec.slotDp)
    }
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        Column(
            Modifier.width(CHANNEL_WIDTH).fillMaxHeight()
                .combinedClickable(
                    onClickLabel = stringResource(R.string.polish_play),
                    onLongClickLabel = stringResource(R.string.polish_options),
                    role = Role.Button,
                    onClick = onPlay,
                    onLongClick = onMenu,
                )
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ChannelLogo(entry.channel, 28)
            Text(
                entry.channel.name,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
            Box(Modifier.width(spec.totalWidthDp.dp).fillMaxHeight()) {
                for (cell in visible) {
                    val key = "${entry.channel.url}|${cell.programme.start}"
                    ProgrammeCell(
                        cell = cell,
                        now = now,
                        time = time,
                        hasReminder = key in reminded,
                        recording = key in recorded,
                        onClick = { onProgramme(cell.programme) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCell(cell: GridCell, now: Long, time: (Long) -> String, hasReminder: Boolean, recording: Boolean, onClick: () -> Unit) {
    val p = cell.programme
    val live = p.isOn(now)
    val past = p.stop <= now
    val colors = MaterialTheme.colorScheme
    // TalkBack: "Title, 20:15 – 21:00, On now, Reminder set".
    val description = listOfNotNull(
        p.title,
        time(p.start) + " – " + time(p.stop),
        if (live) stringResource(R.string.polish_on_now) else null,
        if (hasReminder) stringResource(R.string.polish_reminder_set) else null,
        if (recording) stringResource(R.string.polish_recording_planned) else null,
    ).joinToString(", ")
    Box(
        Modifier
            .offset(x = cell.left.dp)
            .width((cell.width - 2f).coerceAtLeast(1f).dp)
            .fillMaxHeight()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (live) colors.primaryContainer else colors.surfaceContainerHigh)
            .combinedClickable(onClick = onClick, role = Role.Button)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onClick(); true }
            },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(
                p.title,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    live -> colors.onPrimaryContainer
                    past -> colors.onSurfaceVariant
                    else -> colors.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    time(p.start),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (live) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    maxLines = 1,
                )
                if (hasReminder) Icon(Icons.Rounded.Notifications, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(12.dp), tint = colors.primary)
                if (recording) Icon(Icons.Rounded.FiberManualRecord, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(12.dp), tint = colors.error)
            }
        }
        if (live) {
            LinearProgressIndicator(
                progress = { p.progress(now) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = colors.onPrimaryContainer,
                trackColor = Color.Transparent,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}
