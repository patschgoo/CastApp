package io.github.patschgoo.castapp.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class GuideGridMathTest {
    private val hour = GridSpec.HOUR_MS
    private val slot = GridSpec.SLOT_MS

    /** 2026-11-22 18:00 UTC. */
    private val t0 = Xmltv.parseTime("20261122180000 +0000")!!

    /** 6 hours from 18:00, 200 dp per hour. */
    private val spec = GridSpec(t0, t0 + 6 * hour, dpPerHour = 200f)

    private fun p(startOffsetMin: Long, stopOffsetMin: Long, title: String = "P") =
        Programme(t0 + startOffsetMin * 60_000L, t0 + stopOffsetMin * 60_000L, title)

    @Test
    fun gridSizeFollowsDuration() {
        assertEquals(12, spec.slotCount)
        assertEquals(1200f, spec.totalWidthDp, 0.01f)
        assertEquals(100f, spec.slotDp, 0.01f)
        assertEquals(t0 + 3 * slot, spec.slotStart(3))
    }

    @Test
    fun cellsAreSizedByDuration() {
        val cells = spec.cellsFor(listOf(p(0, 30), p(30, 120), p(120, 135)))
        assertEquals(3, cells.size)
        assertEquals(0f, cells[0].left, 0.01f)
        assertEquals(100f, cells[0].width, 0.01f)
        assertEquals(100f, cells[1].left, 0.01f)
        assertEquals(300f, cells[1].width, 0.01f)
        assertEquals(400f, cells[2].left, 0.01f)
        assertEquals(50f, cells[2].width, 0.01f)
        assertTrue(cells.none { it.clippedStart || it.clippedEnd })
        assertEquals(cells[0].right, cells[1].left, 0.01f)
    }

    @Test
    fun cellsAreClippedToTheGrid() {
        val cells = spec.cellsFor(listOf(p(-60, 30, "before"), p(330, 420, "after")))
        assertEquals(2, cells.size)
        assertEquals(0f, cells[0].left, 0.01f)
        assertEquals(100f, cells[0].width, 0.01f)
        assertTrue(cells[0].clippedStart)
        assertFalse(cells[0].clippedEnd)
        assertEquals(1100f, cells[1].left, 0.01f)
        assertEquals(100f, cells[1].width, 0.01f)
        assertTrue(cells[1].clippedEnd)
        // The programme itself keeps its real times.
        assertEquals(t0 - hour, cells[0].programme.start)
    }

    @Test
    fun programmesOutsideTheGridAndBrokenOnesAreLeftOut() {
        val cells = spec.cellsFor(listOf(p(-120, -60), p(400, 460), p(60, 60), p(90, 80), p(30, 60, "ok")))
        assertEquals(listOf("ok"), cells.map { it.programme.title })
    }

    @Test
    fun overlappingProgrammesNeverOverlapAsCells() {
        // Unsorted, and the second starts before the first ends.
        val cells = spec.cellsFor(listOf(p(60, 120, "b"), p(0, 70, "a"), p(65, 90, "inside")))
        assertEquals(listOf("a", "b"), cells.map { it.programme.title })
        assertEquals(cells[0].right, cells[1].left, 0.01f)
        assertTrue(cells[1].clippedStart)
        for (i in 1 until cells.size) assertTrue(cells[i].left >= cells[i - 1].right - 0.001f)
    }

    @Test
    fun nowLinePosition() {
        assertEquals(500f, spec.nowX(t0 + 150 * 60_000L)!!, 0.01f)
        assertEquals(0f, spec.nowX(t0)!!, 0.01f)
        assertNull(spec.nowX(t0 - 1))
        assertNull(spec.nowX(t0 + 6 * hour + 1))
        assertEquals(t0 + 90 * 60_000L, spec.timeAt(300f))
        assertEquals(300f, spec.xOf(t0 + 90 * 60_000L), 0.01f)
    }

    @Test
    fun visibleCellsAreFoundByPosition() {
        val schedule = (0 until 12).map { p(it * 30L, it * 30L + 30, "p$it") }
        val cells = spec.cellsFor(schedule)
        // From 250 to 420 dp: columns 2 (200..300), 3 and 4 (400..500).
        assertEquals(listOf("p2", "p3", "p4"), spec.visible(cells, 250f, 420f).map { it.programme.title })
        // A cell that ends exactly at the left edge is not visible, one that starts exactly at the right edge is not either.
        assertEquals(listOf("p3"), spec.visible(cells, 300f, 400f).map { it.programme.title })
        assertEquals(12, spec.visible(cells, -100f, 5000f).size)
        assertTrue(spec.visible(cells, 5000f, 6000f).isEmpty())
        assertTrue(spec.visible(emptyList(), 0f, 100f).isEmpty())
    }

    @Test
    fun scrollTargetsStayInsideTheGrid() {
        // The viewport is 400 dp: it can scroll at most 800 dp.
        assertEquals(0f, spec.scrollFor(t0 - hour, 400f), 0.01f)
        assertEquals(300f, spec.scrollFor(t0 + 90 * 60_000L, 400f, leadDp = 0f), 0.01f)
        assertEquals(200f, spec.scrollFor(t0 + 90 * 60_000L, 400f, leadDp = 100f), 0.01f)
        assertEquals(800f, spec.scrollFor(t0 + 10 * hour, 400f), 0.01f)
        // A viewport wider than the grid does not scroll.
        assertEquals(0f, spec.scrollFor(t0 + hour, 5000f), 0.01f)
    }

    @Test
    fun dayStartsAreLocalMidnights() {
        val utc = TimeZone.getTimeZone("UTC")
        val grid = GridSpec(t0, t0 + 40 * hour, 200f) // 18:00 on the 22nd to 10:00 on the 24th
        val days = grid.dayStarts(utc)
        assertEquals(listOf(t0, Xmltv.parseTime("20261123000000 +0000")!!, Xmltv.parseTime("20261124000000 +0000")!!), days)
        // In a zone one hour ahead midnight comes an hour earlier.
        val berlin = TimeZone.getTimeZone("Europe/Berlin")
        assertEquals(Xmltv.parseTime("20261122230000 +0000"), grid.dayStarts(berlin)[1])
        // A short grid inside one day has just its start.
        assertEquals(listOf(t0), spec.dayStarts(utc))
    }

    @Test
    fun gridCoversTheGuideInWholeColumns() {
        val schedule = listOf(Programme(t0 + 10 * 60_000L, t0 + 95 * 60_000L, "a"), Programme(t0 + 95 * 60_000L, t0 + 5 * hour + 10 * 60_000L, "b"))
        val now = t0 + 2 * hour
        val grid = GridSpec.covering(sequenceOf(schedule), now)
        assertEquals(0L, grid.startMs % slot)
        assertEquals(0L, grid.endMs % slot)
        // Starts at the column of the first programme (10 past six -> six), ends after the last programme.
        assertEquals(t0, grid.startMs)
        assertEquals(t0 + 5 * hour + 30 * 60_000L, grid.endMs)
    }

    @Test
    fun gridStartsAtTheEarliestProgrammeAndHasRoomAfterNow() {
        val now = t0 + 10 * hour
        // An old guide: everything ended long ago. The grid still reaches ahead of "now".
        val old = listOf(Programme(t0 - 3 * hour, t0 - 2 * hour, "old"))
        val grid = GridSpec.covering(sequenceOf(old), now)
        assertEquals(t0 - 3 * hour, grid.startMs)
        assertTrue(grid.endMs >= now + GridSpec.MIN_AHEAD_MS)
        // No programmes at all: a default window around "now".
        val empty = GridSpec.covering(emptySequence(), now)
        assertTrue(empty.startMs <= now && empty.endMs > now)
    }

    @Test
    fun hugeGuidesAreCapped() {
        val now = t0 + 100 * hour
        val huge = listOf(Programme(t0, t0 + 400 * hour, "long"))
        val grid = GridSpec.covering(sequenceOf(huge), now)
        assertTrue(grid.endMs - grid.startMs <= GridSpec.MAX_SPAN_MS)
        assertTrue(now in grid.startMs..grid.endMs)
    }

    @Test
    fun slotRounding() {
        assertEquals(t0, GridSpec.floorSlot(t0 + 29 * 60_000L))
        assertEquals(t0 + slot, GridSpec.ceilSlot(t0 + 1))
        assertEquals(t0, GridSpec.ceilSlot(t0))
        assertEquals(t0 - slot, GridSpec.floorSlot(t0 - 1))
    }
}
