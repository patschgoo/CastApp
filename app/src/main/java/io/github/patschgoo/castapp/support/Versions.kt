package io.github.patschgoo.castapp.support

/** Compares version names such as "3.1.0", "v3.2" or "3.2.0-beta1". */
object Versions {
    /** Positive when [a] is newer than [b], negative when older, 0 when equal. */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        val n = maxOf(pa.numbers.size, pb.numbers.size)
        for (i in 0 until n) {
            val x = pa.numbers.getOrElse(i) { 0 }
            val y = pb.numbers.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        // 3.2.0 is newer than 3.2.0-beta1
        return when {
            pa.preRelease == pb.preRelease -> 0
            pa.preRelease.isEmpty() -> 1
            pb.preRelease.isEmpty() -> -1
            else -> pa.preRelease.compareTo(pb.preRelease)
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private class Parsed(val numbers: List<Int>, val preRelease: String)

    private fun parse(version: String): Parsed {
        val v = version.trim().removePrefix("v").removePrefix("V")
        val core = v.substringBefore('-').substringBefore('+')
        val pre = v.substringAfter('-', "").substringBefore('+')
        val numbers = core.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        return Parsed(numbers, pre)
    }
}
