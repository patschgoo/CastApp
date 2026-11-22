package io.github.patschgoo.castapp.service

import kotlin.random.Random

/** What happens when an item ends. */
enum class RepeatMode {
    OFF, ALL, ONE;

    /** Off → all → one → off (the button cycles through them). */
    fun cycle(): RepeatMode = entries[(ordinal + 1) % entries.size]
}

/**
 * The play list of a file cast, editable while it plays: jump, move, remove, add,
 * shuffle and repeat. Every entry has an id that never changes, so addresses the
 * TV already fetches (file/<id>/…) keep pointing at the same file when the order
 * changes. Thread-safe; all reads see a consistent snapshot.
 */
class PlayQueue(initial: List<MediaItem>, startIndex: Int = 0) {
    data class Entry(val id: Long, val item: MediaItem)

    /** Result of removing an entry. */
    sealed interface Removal {
        /** No such entry. */
        data object None : Removal
        /** Another entry was removed; the current one keeps playing. */
        data object Other : Removal
        /** The playing entry was removed: play [index] next, or stop when it is -1 (the list is empty). */
        data class Current(val index: Int) : Removal
    }

    private val lock = Any()
    private var nextId = 1L
    private var list: List<Entry> = emptyList()
        set(value) {
            field = value
            itemsSnapshot = value.map { it.item }
        }
    @Volatile private var itemsSnapshot: List<MediaItem> = emptyList()
    private var currentId = -1L
    /** Position of each entry before shuffling, to restore the order. */
    private var originalOrder: Map<Long, Int> = emptyMap()

    @Volatile var repeat: RepeatMode = RepeatMode.OFF
    @Volatile var shuffled: Boolean = false
        private set

    init {
        replaceAll(initial, startIndex)
    }

    val entries: List<Entry> get() = synchronized(lock) { list }
    val items: List<MediaItem> get() = itemsSnapshot
    val size: Int get() = synchronized(lock) { list.size }
    val currentEntryId: Long get() = synchronized(lock) { currentId }

    /** Position of the playing entry (0 when the list is empty). */
    var index: Int
        get() = synchronized(lock) { list.indexOfFirst { it.id == currentId }.coerceAtLeast(0) }
        set(value) = synchronized(lock) {
            list.getOrNull(value)?.let { currentId = it.id }
            Unit
        }

    fun idAt(i: Int): Long = synchronized(lock) { list.getOrNull(i)?.id ?: -1 }

    fun indexOf(id: Long): Int = synchronized(lock) { list.indexOfFirst { it.id == id } }

    fun itemById(id: Long): MediaItem? = synchronized(lock) { list.firstOrNull { it.id == id }?.item }

    fun replaceAll(items: List<MediaItem>, startIndex: Int) = synchronized(lock) {
        list = items.map { Entry(nextId++, it) }
        currentId = list.getOrNull(startIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0)))?.id ?: -1
        shuffled = false
        originalOrder = emptyMap()
    }

    /** Swaps in a corrected item (e.g. a TV channel found to be HLS), keeping its id. */
    fun replaceItem(i: Int, expected: MediaItem, item: MediaItem): Boolean = synchronized(lock) {
        val e = list.getOrNull(i) ?: return false
        if (e.item != expected) return false
        list = list.toMutableList().also { it[i] = e.copy(item = item) }
        true
    }

    /** Moves an entry to position [to]; false if nothing changed. */
    fun move(id: Long, to: Int): Boolean = synchronized(lock) {
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return false
        val target = to.coerceIn(0, list.size - 1)
        if (target == from) return false
        list = list.toMutableList().also { it.add(target, it.removeAt(from)) }
        true
    }

    /** Moves an entry [delta] places up (negative) or down (positive). */
    fun moveBy(id: Long, delta: Int): Boolean {
        val from = indexOf(id)
        return from >= 0 && move(id, from + delta)
    }

    fun remove(id: Long): Removal = synchronized(lock) {
        val pos = list.indexOfFirst { it.id == id }
        if (pos < 0) return Removal.None
        val wasCurrent = id == currentId
        list = list.toMutableList().also { it.removeAt(pos) }
        originalOrder = originalOrder - id
        if (!wasCurrent) return Removal.Other
        if (list.isEmpty()) {
            currentId = -1
            return Removal.Current(-1)
        }
        // The entry that moved up into its place plays next; after the last one, the new last.
        val next = if (pos < list.size) pos else if (repeat == RepeatMode.ALL) 0 else list.size - 1
        currentId = list[next].id
        Removal.Current(next)
    }

    /** Adds items at the end; returns their ids. */
    fun add(items: List<MediaItem>): List<Long> = synchronized(lock) {
        val added = items.map { Entry(nextId++, it) }
        if (shuffled) {
            val base = (originalOrder.values.maxOrNull() ?: -1) + 1
            originalOrder = originalOrder + added.mapIndexed { i, e -> e.id to base + i }
        }
        list = list + added
        if (currentId < 0) currentId = added.firstOrNull()?.id ?: -1
        added.map { it.id }
    }

    /**
     * Shuffle on: the entries after the playing one are put in random order (what
     * was already played stays where it is). Off: the original order comes back,
     * with entries added meanwhile at the end.
     */
    fun setShuffle(on: Boolean, random: Random = Random.Default) = synchronized(lock) {
        if (on == shuffled) return
        if (on) {
            originalOrder = list.mapIndexed { i, e -> e.id to i }.toMap()
            val pos = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            val head = list.take(pos + 1)
            val tail = list.drop(pos + 1).shuffled(random)
            list = head + tail
        } else {
            val order = originalOrder
            list = list.sortedBy { order[it.id] ?: Int.MAX_VALUE }
            originalOrder = emptyMap()
        }
        shuffled = on
    }

    /**
     * The entry to play after the current one. [auto]: the current one ended by
     * itself, so "repeat one" plays it again; Next pressed by hand always moves on.
     * Null at the end of the list without "repeat all".
     */
    fun nextIndex(auto: Boolean): Int? = synchronized(lock) {
        if (list.isEmpty()) return null
        val pos = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        when {
            auto && repeat == RepeatMode.ONE -> pos
            pos + 1 < list.size -> pos + 1
            repeat != RepeatMode.OFF -> 0
            else -> null
        }
    }

    fun previousIndex(): Int? = synchronized(lock) {
        if (list.isEmpty()) return null
        val pos = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        when {
            pos > 0 -> pos - 1
            repeat != RepeatMode.OFF && list.size > 1 -> list.size - 1
            else -> null
        }
    }

    val hasNext: Boolean get() = nextIndex(auto = false) != null
    val hasPrevious: Boolean get() = previousIndex() != null

    /** Up to [count] entries that will play after the current one, in order (wrapping with "repeat all"). */
    fun upcoming(count: Int): List<Entry> = synchronized(lock) {
        if (list.size < 2 || count <= 0) return emptyList()
        val pos = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val after = list.drop(pos + 1) + if (repeat == RepeatMode.ALL) list.take(pos) else emptyList()
        after.take(count)
    }
}
