package io.github.patschgoo.castapp.remote

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Discovery shared by the main screen's remote and the full remote: counted, lingering, thread-safe. */
class SharedRunTest {
    /** A clock the test moves by hand. */
    private class FakeTimer : SharedRun.Timer {
        var now = 0L
        private val tasks = ArrayList<Pair<Long, () -> Unit>>()
        val pending: Int get() = tasks.size

        override fun schedule(delayMs: Long, task: () -> Unit): SharedRun.Cancel {
            val entry = (now + delayMs) to task
            tasks += entry
            return SharedRun.Cancel { tasks.remove(entry) }
        }

        fun advance(ms: Long) {
            now += ms
            val due = tasks.filter { it.first <= now }
            tasks.removeAll(due.toSet())
            due.forEach { it.second() }
        }
    }

    private val timer = FakeTimer()
    private var starts = 0
    private var stops = 0
    private val run = SharedRun(lingerMs = 5_000, timer = timer, start = { starts++ }, stop = { stops++ })

    @Test
    fun startsOnceForSeveralUsersAndStopsAfterTheLast() {
        val home = run.acquire()
        val full = run.acquire()
        assertEquals(1, starts)
        assertEquals(2, run.activeUsers)

        // The full remote closes: the main screen's remote keeps looking.
        full.close()
        timer.advance(60_000)
        assertEquals(0, stops)
        assertTrue(run.isRunning)

        home.close()
        timer.advance(4_999)
        assertEquals(0, stops) // still lingering
        timer.advance(1)
        assertEquals(1, stops)
        assertFalse(run.isRunning)
    }

    @Test
    fun closingALeaseTwiceCountsOnce() {
        val home = run.acquire()
        val full = run.acquire()
        full.close()
        full.close()
        full.close()
        timer.advance(60_000)
        assertEquals(0, stops)
        assertEquals(1, run.activeUsers)
        assertTrue(full.isClosed)
        home.close()
        timer.advance(5_000)
        assertEquals(1, stops)
    }

    @Test
    fun aQuickReacquireKeepsItRunning() {
        // A rotation: onStop, then onStart of the new activity a few milliseconds later.
        run.acquire().close()
        timer.advance(200)
        val again = run.acquire()
        timer.advance(60_000)
        assertEquals(1, starts)
        assertEquals(0, stops)
        assertEquals(0, timer.pending)
        again.close()
        timer.advance(5_000)
        assertEquals(1, stops)
    }

    @Test
    fun startsAgainAfterItStopped() {
        run.acquire().close()
        timer.advance(5_000)
        run.acquire()
        assertEquals(2, starts)
        assertEquals(1, stops)
    }

    @Test
    fun anOverTakenStopDoesNothing() {
        // Released, re-acquired and released again: only the last release's stop counts.
        val a = run.acquire()
        a.close()
        timer.advance(3_000)
        val b = run.acquire()
        b.close()
        timer.advance(3_000) // the first stop would be due now
        assertEquals(0, stops)
        timer.advance(2_000)
        assertEquals(1, stops)
    }

    @Test
    fun withoutLingerItStopsAtOnce() {
        val now = SharedRun(0, timer, start = { starts++ }, stop = { stops++ })
        now.acquire().close()
        assertEquals(1, stops)
        assertEquals(0, timer.pending)
    }

    @Test
    fun aFailingStartIsStillStoppedAndCounted() {
        var stopped = 0
        val failing = SharedRun(0, timer, start = { throw IllegalStateException("no Wi-Fi") }, stop = { stopped++ })
        val lease = failing.acquire()
        assertTrue(failing.isRunning)
        lease.close()
        assertEquals(1, stopped)
    }

    @Test
    fun manyThreadsNeverOverlapStartsAndEndStopped() {
        val active = AtomicInteger()
        val violations = AtomicInteger()
        val started = AtomicInteger()
        val stopped = AtomicInteger()
        val shared = SharedRun(
            lingerMs = 0,
            timer = timer,
            start = { if (active.incrementAndGet() != 1) violations.incrementAndGet(); started.incrementAndGet() },
            stop = { if (active.decrementAndGet() != 0) violations.incrementAndGet(); stopped.incrementAndGet() },
        )
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            thread {
                barrier.await()
                repeat(2_000) {
                    val lease = shared.acquire()
                    if (it % 3 == 0) lease.close() // closed twice on purpose
                    lease.close()
                }
                done.countDown()
            }
        }
        done.await()
        assertEquals(0, violations.get())
        assertEquals(0, shared.activeUsers)
        assertFalse(shared.isRunning)
        assertEquals(started.get(), stopped.get())
        assertEquals(0, active.get())
    }

    // ---- per provider ----------------------------------------------------------------------------------

    private class CountingProvider : RemoteProvider {
        var starts = 0
        var stops = 0
        override val title = "Test"
        override fun targets(context: Context): StateFlow<List<RemoteTarget>> = MutableStateFlow(emptyList())
        override fun startDiscovery(context: Context) { starts++ }
        override fun stopDiscovery() { stops++ }
    }

    @Test
    fun eachProviderIsCountedOnItsOwn() {
        val network = CountingProvider()
        val bluetooth = CountingProvider()
        val registry = DiscoveryRegistry(5_000, timer)
        val context = ContextWrapper(null)

        val home = registry.acquire(context, listOf(network)) // the main screen's remote: network TVs only
        val full = registry.acquire(context, listOf(network, bluetooth)) // the full remote: everything
        assertEquals(1, network.starts)
        assertEquals(1, bluetooth.starts)

        full.close()
        timer.advance(5_000)
        assertEquals(0, network.stops) // the main screen still needs it
        assertEquals(1, bluetooth.stops)

        home.close()
        home.close()
        timer.advance(5_000)
        assertEquals(1, network.stops)
        assertEquals(1, network.starts)
    }
}
