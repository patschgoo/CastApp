package io.github.patschgoo.castapp.iptv

import java.util.Calendar
import java.util.TimeZone

/** One programme as a block in the guide grid: [left] and [width] are in dp from the start of the grid. */
data class GridCell(val programme: Programme, val left: Float, val width: Float, val clippedStart: Boolean, val clippedEnd: Boolean) {
    val right: Float get() = left + width
}

/**
 * The geometry of the guide grid: time runs from [startMs] to [endMs] across the screen in
 * columns of [SLOT_MS] (30 minutes), [dpPerHour] wide per hour. Plain maths, no Compose, so
 * it is easy to test. All positions are dp from the left edge of the time area.
 */
class GridSpec(val startMs: Long, val endMs: Long, val dpPerHour: Float = DEFAULT_DP_PER_HOUR) {
    init {
        require(endMs > startMs) { "The grid needs a positive length" }
        require(dpPerHour > 0f)
    }

    val dpPerMs: Float get() = dpPerHour / HOUR_MS
    val slotDp: Float get() = SLOT_MS * dpPerMs
    val slotCount: Int get() = ((endMs - startMs + SLOT_MS - 1) / SLOT_MS).toInt()
    val totalWidthDp: Float get() = (endMs - startMs) * dpPerMs

    /** Distance of [timeMs] from the left edge (negative before the grid, beyond [totalWidthDp] after it). */
    fun xOf(timeMs: Long): Float = (timeMs - startMs) * dpPerMs

    fun timeAt(x: Float): Long = startMs + (x / dpPerMs).toLong()

    /** Where the "now" line is, or null when [now] is outside the grid. */
    fun nowX(now: Long): Float? = if (now in startMs..endMs) xOf(now) else null

    /** Start time of column [index]. */
    fun slotStart(index: Int): Long = startMs + index * SLOT_MS

    /**
     * The programmes of one channel as cells: clipped to the grid, sorted, and never overlapping
     * (when a guide lists overlapping programmes the later one starts where the earlier ends).
     */
    fun cellsFor(schedule: List<Programme>): List<GridCell> {
        val out = ArrayList<GridCell>(schedule.size)
        var cursor = startMs
        for (p in schedule.sortedBy { it.start }) {
            if (p.stop <= p.start || p.stop <= cursor || p.start >= endMs) continue
            val from = maxOf(p.start, cursor, startMs)
            val to = minOf(p.stop, endMs)
            if (to <= from) continue
            out += GridCell(p, xOf(from), (to - from) * dpPerMs, clippedStart = p.start < from, clippedEnd = p.stop > to)
            cursor = to
        }
        return out
    }

    /** Cells of a sorted [cells] list that touch the horizontal range [fromDp, toDp] (binary search, so long rows stay cheap). */
    fun visible(cells: List<GridCell>, fromDp: Float, toDp: Float): List<GridCell> {
        if (cells.isEmpty()) return cells
        // First cell whose right edge is after fromDp.
        var lo = 0
        var hi = cells.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cells[mid].right <= fromDp) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < cells.size && cells[end].left < toDp) end++
        return cells.subList(lo, end)
    }

    /** The horizontal scroll (dp) that puts [timeMs] [leadDp] from the left edge of a [viewportDp] wide view, within the grid. */
    fun scrollFor(timeMs: Long, viewportDp: Float, leadDp: Float = slotDp): Float {
        val max = (totalWidthDp - viewportDp).coerceAtLeast(0f)
        return (xOf(timeMs) - leadDp).coerceIn(0f, max)
    }

    /**
     * Local midnights inside the grid, for the "pick a day" menu. The first entry is the start of
     * the grid when it begins part-way through a day.
     */
    fun dayStarts(zone: TimeZone = TimeZone.getDefault()): List<Long> {
        val out = ArrayList<Long>()
        out += startMs
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = startMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (true) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            if (cal.timeInMillis >= endMs) break
            out += cal.timeInMillis
        }
        return out
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val SLOT_MS = 30 * 60_000L
        const val DEFAULT_DP_PER_HOUR = 220f

        /** A grid is never longer than this, however much a guide lists. */
        const val MAX_SPAN_MS = 72 * HOUR_MS

        /** Never shorter than this after "now" (a guide that is almost used up still gets a usable grid). */
        const val MIN_AHEAD_MS = 3 * HOUR_MS

        fun floorSlot(t: Long): Long = Math.floorDiv(t, SLOT_MS) * SLOT_MS
        fun ceilSlot(t: Long): Long = -Math.floorDiv(-t, SLOT_MS) * SLOT_MS

        /** The grid for the programmes of [schedules]: from the earliest start to the latest end, in whole columns. */
        fun covering(schedules: Sequence<List<Programme>>, now: Long, dpPerHour: Float = DEFAULT_DP_PER_HOUR): GridSpec {
            var min = Long.MAX_VALUE
            var max = Long.MIN_VALUE
            for (s in schedules) {
                if (s.isEmpty()) continue
                min = minOf(min, s.minOf { it.start })
                max = maxOf(max, s.maxOf { it.stop })
            }
            if (min == Long.MAX_VALUE) {
                min = now - 2 * HOUR_MS
                max = now + 6 * HOUR_MS
            }
            var start = floorSlot(minOf(min, now - HOUR_MS))
            var end = ceilSlot(maxOf(max, now + MIN_AHEAD_MS))
            if (end - start > MAX_SPAN_MS) {
                // Too long: keep the time around "now".
                start = floorSlot(maxOf(start, now - 6 * HOUR_MS))
                end = minOf(end, start + MAX_SPAN_MS)
            }
            return GridSpec(start, end, dpPerHour)
        }
    }
}
