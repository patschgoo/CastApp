package io.github.patschgoo.castapp.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlayQueueTest {
    private fun item(n: Int) = MediaItem("v$n.mp4", "content://media/$n", "video/mp4")
    private fun queue(size: Int = 5, start: Int = 0) = PlayQueue((1..size).map(::item), start)
    private fun PlayQueue.titles() = items.map { it.title.removeSuffix(".mp4") }

    @Test
    fun startsAtTheGivenItem() {
        val q = queue(start = 2)
        assertEquals(2, q.index)
        assertEquals("v3.mp4", q.items[q.index].title)
        assertEquals(4, queue(start = 99).index) // clamped to the end
    }

    @Test
    fun idsStayWithTheirItemsWhenMoving() {
        val q = queue()
        val id3 = q.idAt(2)
        assertTrue(q.move(id3, 0))
        assertEquals(listOf("v3", "v1", "v2", "v4", "v5"), q.titles())
        assertEquals("v3.mp4", q.itemById(id3)?.title)
        assertEquals(0, q.indexOf(id3))
        assertFalse(q.move(id3, 0))
        assertTrue(q.moveBy(id3, 10)) // clamped to the end
        assertEquals("v3", q.titles().last())
    }

    @Test
    fun currentFollowsItsEntryWhenOthersMove() {
        val q = queue(start = 1) // playing v2
        val playing = q.currentEntryId
        q.move(q.idAt(4), 0) // v5 to the top
        assertEquals(2, q.index)
        assertEquals(playing, q.currentEntryId)
        assertEquals("v2.mp4", q.items[q.index].title)
    }

    @Test
    fun removingOthersKeepsPlaying() {
        val q = queue(start = 2)
        assertEquals(PlayQueue.Removal.Other, q.remove(q.idAt(0)))
        assertEquals("v3.mp4", q.items[q.index].title)
        assertEquals(PlayQueue.Removal.None, q.remove(12345))
    }

    @Test
    fun removingTheCurrentPlaysTheNextOne() {
        val q = queue(start = 1)
        val r = q.remove(q.currentEntryId)
        assertEquals(PlayQueue.Removal.Current(1), r)
        assertEquals("v3.mp4", q.items[q.index].title)
        // The last one: the new last plays.
        val last = queue(start = 4)
        assertEquals(PlayQueue.Removal.Current(3), last.remove(last.currentEntryId))
        assertEquals("v4.mp4", last.items[last.index].title)
        // Nothing left.
        val one = queue(size = 1)
        assertEquals(PlayQueue.Removal.Current(-1), one.remove(one.currentEntryId))
        assertEquals(0, one.size)
    }

    @Test
    fun removingTheLastWithRepeatAllWrapsAround() {
        val q = queue(start = 4)
        q.repeat = RepeatMode.ALL
        assertEquals(PlayQueue.Removal.Current(0), q.remove(q.currentEntryId))
    }

    @Test
    fun nextAndPreviousFollowRepeat() {
        val q = queue(size = 3, start = 2)
        assertNull(q.nextIndex(auto = true))
        assertFalse(q.hasNext)
        q.repeat = RepeatMode.ALL
        assertEquals(0, q.nextIndex(auto = true))
        q.repeat = RepeatMode.ONE
        assertEquals(2, q.nextIndex(auto = true)) // ended by itself: again
        assertEquals(0, q.nextIndex(auto = false)) // Next by hand: moves on (wraps like "all")
        q.index = 0
        q.repeat = RepeatMode.OFF
        assertNull(q.previousIndex())
        q.repeat = RepeatMode.ALL
        assertEquals(2, q.previousIndex())
    }

    @Test
    fun repeatCycles() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.cycle())
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.cycle())
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.cycle())
    }

    @Test
    fun shuffleKeepsPlayedItemsAndRestoresTheOrder() {
        val q = queue(size = 8, start = 2)
        val playing = q.currentEntryId
        q.setShuffle(true, Random(42))
        assertTrue(q.shuffled)
        assertEquals(listOf("v1", "v2", "v3"), q.titles().take(3))
        assertEquals(playing, q.currentEntryId)
        assertEquals(2, q.index)
        assertEquals((4..8).map { "v$it" }.toSet(), q.titles().drop(3).toSet())
        assertNotEquals((4..8).map { "v$it" }, q.titles().drop(3))
        // Added while shuffled: at the end, and stays after the others when unshuffled.
        q.add(listOf(item(9)))
        assertEquals("v9", q.titles().last())
        q.setShuffle(false)
        assertEquals((1..9).map { "v$it" }, q.titles())
        assertEquals(playing, q.currentEntryId)
    }

    @Test
    fun addAppendsAndGivesNewIds() {
        val q = queue(size = 2)
        val ids = q.add(listOf(item(3), item(4)))
        assertEquals(2, ids.size)
        assertEquals(listOf("v1", "v2", "v3", "v4"), q.titles())
        assertEquals(4, ids.plus(q.idAt(0)).plus(q.idAt(1)).toSet().size)
        val empty = PlayQueue(emptyList())
        empty.add(listOf(item(1)))
        assertEquals(empty.idAt(0), empty.currentEntryId)
    }

    @Test
    fun upcomingListsWhatPlaysNext() {
        val q = queue(size = 5, start = 3)
        assertEquals(listOf("v5"), q.upcoming(2).map { it.item.title.removeSuffix(".mp4") })
        q.repeat = RepeatMode.ALL
        assertEquals(listOf("v5", "v1"), q.upcoming(2).map { it.item.title.removeSuffix(".mp4") })
        assertTrue(queue(size = 1).upcoming(3).isEmpty())
    }

    @Test
    fun replaceItemKeepsTheId() {
        val q = queue(size = 2)
        val id = q.idAt(1)
        val old = q.items[1]
        assertTrue(q.replaceItem(1, old, old.copy(mimeType = "application/x-mpegurl")))
        assertEquals(id, q.idAt(1))
        assertTrue(q.items[1].isHls)
        assertFalse(q.replaceItem(1, old, old)) // no longer what was expected
    }
}
