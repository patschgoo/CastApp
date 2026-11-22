package io.github.patschgoo.castapp.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {
    private fun sorted(vararg names: String) = names.toList().shuffled(java.util.Random(7)).sortedWith(NaturalOrder)

    @Test
    fun numbersCountInsteadOfSpelling() {
        assertEquals(
            listOf("Show 01.mkv", "Show 02.mkv", "Show 03.mkv", "Show 028.mkv", "Show 029.mkv"),
            sorted("Show 02.mkv", "Show 028.mkv", "Show 029.mkv", "Show 03.mkv", "Show 01.mkv"),
        )
        assertEquals(
            listOf("Part 9.mp4", "Part 10.mp4", "Part 11.mp4", "Part 100.mp4"),
            sorted("Part 10.mp4", "Part 100.mp4", "Part 11.mp4", "Part 9.mp4"),
        )
        assertEquals(
            listOf("S05E01.ts", "S05E02.ts", "S05E017.ts"),
            sorted("S05E01.ts", "S05E017.ts", "S05E02.ts"),
        )
        assertEquals(
            listOf("Box 4 Disc 1", "Box 4 Disc 2", "Box 4 Disc 9", "Box 4 Disc 10"),
            sorted("Box 4 Disc 1", "Box 4 Disc 10", "Box 4 Disc 2", "Box 4 Disc 9"),
        )
    }

    @Test
    fun leadingZerosAndHugeNumbers() {
        // Same value: equal here, the listing's tie-breaks decide.
        assertEquals(0, NaturalOrder.compare("Ep 007", "Ep 7"))
        assertEquals(0, NaturalOrder.compare("Ep 0", "Ep 000"))
        assertTrue(NaturalOrder.compare("Ep 007", "Ep 8") < 0)
        assertTrue(NaturalOrder.compare("Ep 0010", "Ep 9") > 0)
        // Far beyond a Long: compared by length, then digit by digit.
        assertTrue(NaturalOrder.compare("Clip 99999999999999999999999", "Clip 100000000000000000000000") < 0)
        assertTrue(NaturalOrder.compare("Clip 123456789012345678901235", "Clip 123456789012345678901234") > 0)
        assertTrue(NaturalOrder.compare("a", "a1") < 0) // shorter rest first
        assertTrue(NaturalOrder.compare("Ep 2b", "Ep 2a") > 0)
        // Only ASCII digits count as numbers (Arabic-Indic digits stay characters).
        assertTrue(NaturalOrder.compare("x ١٠", "x 2") > 0)
    }

    @Test
    fun caseDoesNotMatter() {
        assertEquals(listOf("alpha 2", "Beta 1", "beta 3"), sorted("beta 3", "Beta 1", "alpha 2"))
        assertEquals(0, NaturalOrder.compare("ÄRGER 1", "ärger 1"))
    }

    @Test
    fun noChunkSplitTraps() {
        // Walked character by character: "-" (before digits) sorts before "0", as in plain text order.
        assertEquals(
            listOf("Mu Kit - 01.mkv", "Mu Kit - 02.mkv", "Mu Kit - 06.mkv", "Mu Kit 07.mkv", "Mu Kit 08.mkv"),
            sorted("Mu Kit 07.mkv", "Mu Kit - 06.mkv", "Mu Kit - 01.mkv", "Mu Kit 08.mkv", "Mu Kit - 02.mkv"),
        )
        // " " sorts before "4": the spaced spelling first.
        assertEquals(
            listOf("Saga Season 4 Disc 1", "Saga Season 4 Disc 2", "Saga Season4 Disc3"),
            sorted("Saga Season4 Disc3", "Saga Season 4 Disc 2", "Saga Season 4 Disc 1"),
        )
    }

    @Test
    fun listingsPutFoldersFirstAndBreakTies() {
        fun e(name: String, folder: Boolean = false) = RemoteEntry(name, "D/$name", folder)
        val listed = listOf(e("Ep 10.mkv"), e("Season 10", true), e("ep 2.mkv"), e("Ep 2.mkv"), e("Ep 02.mkv"), e("Season 2", true), e("Ep 1.mkv"))
        val expected = listOf("Season 2", "Season 10", "Ep 1.mkv", "Ep 02.mkv", "Ep 2.mkv", "ep 2.mkv", "Ep 10.mkv")
        assertEquals(expected, listed.sortedWith(NaturalOrder.entries).map { it.name })
        // The same whatever order the server sent.
        assertEquals(expected, listed.reversed().sortedWith(NaturalOrder.entries).map { it.name })
    }

    @Test
    fun webDavListingIsInNaturalOrder() {
        fun file(name: String) = "<d:response><d:href>/dav/Show/${name.replace(" ", "%20")}</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>"
        val xml = "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/dav/Show/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>" +
            listOf("Show 100.mkv", "Show 11.mkv", "Show 10.mkv", "Show 028.mkv", "Show 03.mkv", "Show 02.mkv").joinToString("") { file(it) } +
            "<d:response><d:href>/dav/Show/Extras/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response></d:multistatus>"
        val list = WebDav.parse(xml, "https://cloud.example/dav/Show/")
        assertEquals(listOf("Extras", "Show 02.mkv", "Show 03.mkv", "Show 10.mkv", "Show 11.mkv", "Show 028.mkv", "Show 100.mkv"), list.map { it.name })
    }
}
