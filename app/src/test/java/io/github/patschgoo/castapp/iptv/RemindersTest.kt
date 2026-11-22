package io.github.patschgoo.castapp.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {
    private class MemoryStore(var text: String? = null) : TextStore {
        override fun read() = text
        override fun write(text: String) {
            this.text = text
        }
    }

    private class FakeAlarm : AlarmPort {
        val calls = ArrayList<Long?>()
        override fun schedule(triggerAtMs: Long?) {
            calls += triggerAtMs
        }
        val last: Long? get() = calls.last()
    }

    private val minute = 60_000L
    private val now = 1_800_000_000_000L
    private val channel = Channel("Das Erste", "http://tv.example/live/u/p/1.ts", group = "News", userAgent = "UA", referrer = "http://ref/")

    private fun programme(startInMin: Long, lengthMin: Long = 30, title: String = "Tagesschau") =
        Programme(now + startInMin * minute, now + (startInMin + lengthMin) * minute, title)

    private fun scheduler(store: TextStore = MemoryStore(), alarm: FakeAlarm = FakeAlarm()) =
        ReminderScheduler(ReminderBook(store), alarm).also { it.reschedule(now) }

    @Test
    fun reminderIsDueFiveMinutesBeforeTheStart() {
        val alarm = FakeAlarm()
        val s = scheduler(alarm = alarm)
        val p = programme(60)
        s.add(Reminder.of(channel, p), now)
        assertEquals(p.start - 5 * minute, alarm.last)
    }

    @Test
    fun theEarliestReminderSetsTheAlarmAndCancellingMovesIt() {
        val alarm = FakeAlarm()
        val s = scheduler(alarm = alarm)
        val late = Reminder.of(channel, programme(120, title = "late"))
        val early = Reminder.of(channel, programme(30, title = "early"))
        s.add(late, now)
        assertEquals(late.triggerAt, alarm.last)
        s.add(early, now)
        assertEquals(early.triggerAt, alarm.last)
        s.cancel(early.id, now)
        assertEquals(late.triggerAt, alarm.last)
        s.cancel(late.id, now)
        assertNull("no reminder left: the alarm is cancelled", alarm.last)
    }

    @Test
    fun theSameProgrammeIsOneReminder() {
        val store = MemoryStore()
        val s = scheduler(store)
        val p = programme(60)
        s.add(Reminder.of(channel, p), now)
        s.add(Reminder.of(channel, p), now)
        assertEquals(1, ReminderBook(store).items.value.size)
        assertTrue(ReminderBook(store).has(channel.url, p.start))
        assertFalse(ReminderBook(store).has(channel.url, p.start + 1))
        assertFalse(ReminderBook(store).has("http://other", p.start))
    }

    @Test
    fun remindersSurviveARestart() {
        val store = MemoryStore()
        scheduler(store).also {
            it.add(Reminder.of(channel, programme(90, title = "B")), now)
            it.add(Reminder.of(channel, programme(40, title = "A")), now)
        }
        // A new process: the book is read from the store and the alarm is set again (as after a reboot).
        val alarm = FakeAlarm()
        val again = ReminderScheduler(ReminderBook(store), alarm)
        again.reschedule(now)
        val items = ReminderBook(store).items.value
        assertEquals(listOf("A", "B"), items.map { it.title })
        assertEquals(items[0].triggerAt, alarm.last)
        val r = items[0]
        assertEquals(channel.name, r.channelName)
        assertEquals(channel.url, r.channelUrl)
        assertEquals("UA", r.toChannel().userAgent)
        assertEquals("http://ref/", r.toChannel().referrer)
        assertEquals(programme(40).stop, r.stopMs)
    }

    @Test
    fun aBrokenStoreGivesNoReminders() {
        assertTrue(ReminderBook(MemoryStore("not json")).items.value.isEmpty())
        assertTrue(ReminderBook(MemoryStore("[{\"x\":1}]")).items.value.isEmpty())
    }

    @Test
    fun theAlarmHandsOutDueRemindersAndSetsTheNextOne() {
        val alarm = FakeAlarm()
        val store = MemoryStore()
        val s = scheduler(store, alarm)
        val a = Reminder.of(channel, programme(30, title = "A"))
        val b = Reminder.of(channel, programme(90, title = "B"))
        s.add(a, now)
        s.add(b, now)
        // Not yet.
        assertTrue(s.onAlarm(now + 10 * minute).isEmpty())
        assertEquals(a.triggerAt, alarm.last)
        // A is due (a little late, as inexact alarms are).
        val due = s.onAlarm(a.triggerAt + 3 * minute)
        assertEquals(listOf("A"), due.map { it.title })
        assertEquals(b.triggerAt, alarm.last)
        // It is gone: a second wake-up shows nothing and A is not remembered.
        assertTrue(s.onAlarm(a.triggerAt + 4 * minute).isEmpty())
        assertEquals(listOf("B"), ReminderBook(store).items.value.map { it.title })
        // B fires, nothing is left and the alarm is cancelled.
        assertEquals(listOf("B"), s.onAlarm(b.triggerAt).map { it.title })
        assertNull(alarm.last)
    }

    @Test
    fun remindersOfProgrammesThatAreOverAreDroppedQuietly() {
        val alarm = FakeAlarm()
        val store = MemoryStore()
        val s = ReminderScheduler(ReminderBook(store), alarm)
        val over = Reminder.of(channel, programme(30, 30, "over"))
        val running = Reminder.of(channel, programme(40, 60, "running"))
        s.add(over, now)
        s.add(running, now)
        // The phone was off until 70 minutes from now: "over" ended 60 minutes from now, "running" is still on.
        val due = s.onAlarm(now + 70 * minute)
        assertEquals(listOf("running"), due.map { it.title })
        assertTrue(ReminderBook(store).items.value.isEmpty())
    }

    @Test
    fun anOverdueReminderIsScheduledForRightAfterNow() {
        val alarm = FakeAlarm()
        val s = scheduler(alarm = alarm)
        val r = Reminder.of(channel, programme(1))
        s.add(r, now)
        assertTrue(r.triggerAt < now)
        assertEquals(now + ReminderScheduler.MIN_DELAY_MS, alarm.last)
    }

    @Test
    fun idsDependOnChannelAndStart() {
        assertEquals(Reminder.idFor("a", 5), Reminder.idFor("a", 5))
        assertFalse(Reminder.idFor("a", 5) == Reminder.idFor("b", 5))
        assertFalse(Reminder.idFor("a", 5) == Reminder.idFor("a", 6))
    }
}
