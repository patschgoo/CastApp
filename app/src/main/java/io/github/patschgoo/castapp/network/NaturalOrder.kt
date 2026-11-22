package io.github.patschgoo.castapp.network

/**
 * Orders file names the way people count: "Ep 2" before "Ep 10", "S05E02" before "S05E017",
 * "02" before "028". Letters compare without case; a run of digits compares by its value.
 *
 * The names are walked character by character (not split into text and number chunks first),
 * so "Show - 06" stays before "Show 07" and "Season 4 Disc 1" before "Season4 Disc2", as in
 * plain text order.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int = compareLowercase(a.lowercase(), b.lowercase())

    private fun compareLowercase(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (isDigit(ca) && isDigit(cb)) {
                // Only ASCII digits: other scripts' digits stay plain characters.
                var si = i
                while (si < a.length && a[si] == '0') si++
                var sj = j
                while (sj < b.length && b[sj] == '0') sj++
                var ei = si
                while (ei < a.length && isDigit(a[ei])) ei++
                var ej = sj
                while (ej < b.length && isDigit(b[ej])) ej++
                // Without leading zeros, the longer run is the bigger number (no overflow for long runs).
                val lengths = (ei - si).compareTo(ej - sj)
                if (lengths != 0) return lengths
                for (k in 0 until ei - si) {
                    val d = a[si + k].compareTo(b[sj + k])
                    if (d != 0) return d
                }
                i = ei
                j = ej
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun isDigit(c: Char) = c in '0'..'9'

    /**
     * How share and cloud folders are listed: folders first, then names in natural order. Names
     * the same by that ("Ep 2" and "Ep 02", "a" and "A") get a fixed order too, whatever order the
     * server sent them in.
     */
    val entries: Comparator<RemoteEntry> = compareByDescending<RemoteEntry> { it.isFolder }
        .thenBy(NaturalOrder) { it.name }
        .thenBy { it.name.lowercase() }
        .thenBy { it.name }
}
