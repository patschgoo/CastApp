package io.github.patschgoo.castapp.stream

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Hands converted samples to the HTTP writer in timestamp order across tracks,
 * and blocks a track's producer when it runs too far ahead (backpressure all the
 * way back to the decoder, so nothing is ever dropped).
 */
class InterleavingQueue(private val tracks: Set<Int>, private val maxPerTrack: Int = 90) : StreamSubscriber {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queues = tracks.associateWith { ArrayDeque<EncodedSample>() }
    private val ended = HashSet<Int>()
    @Volatile private var closed = false

    override fun onSample(sample: EncodedSample) {
        lock.withLock {
            val q = queues[sample.trackId] ?: return
            while (q.size >= maxPerTrack && !closed) changed.await(200, TimeUnit.MILLISECONDS)
            if (closed) return
            q.addLast(sample)
            changed.signalAll()
        }
    }

    /** No more samples will come for [trackId]. */
    fun endTrack(trackId: Int) {
        lock.withLock {
            ended.add(trackId)
            changed.signalAll()
        }
    }

    val isFinished: Boolean
        get() = lock.withLock { ended.containsAll(tracks) && queues.values.all { it.isEmpty() } }

    /** Next sample in timestamp order, or null on timeout / when everything is done. */
    fun take(timeoutMs: Long): EncodedSample? {
        lock.withLock {
            var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!closed) {
                // A sample can go out once every track that may still produce something has one waiting.
                val ready = tracks.all { (queues[it]?.isNotEmpty() == true) || it in ended }
                if (ready) {
                    val next = queues.values.filter { it.isNotEmpty() }.minByOrNull { it.first().ptsUs }
                    if (next == null) return null // all ended and empty
                    val s = next.removeFirst()
                    changed.signalAll()
                    return s
                }
                if (nanos <= 0) return null
                nanos = changed.awaitNanos(nanos)
            }
            return null
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            changed.signalAll()
        }
    }
}
