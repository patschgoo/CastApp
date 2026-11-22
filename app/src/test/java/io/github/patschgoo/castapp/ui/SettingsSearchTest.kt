package io.github.patschgoo.castapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val german = listOf(
        SettingText("quality", "Bild", "Qualität", "Auflösung des Bildes auf dem Fernseher"),
        SettingText("subtitle_style", "Untertitel", "Aussehen der Untertitel", "Größe, Farbe und Hintergrund"),
        SettingText("receive", "Verbindung", "Auf diesem Handy empfangen", "Andere Geräte können an dieses Handy streamen"),
        SettingText("theme", "Darstellung", "Design", "Hell, dunkel oder wie das System"),
        SettingText("dnd", "Hilfe & Support", "Nicht stören beim Streamen", "Benachrichtigungen pausieren"),
    )
    private val english = listOf(
        SettingText("quality", "Picture", "Quality", "Resolution of the picture on the TV"),
        SettingText("network_check", "Help & support", "Why can't I find my TV?", "Checks the Wi-Fi network"),
        SettingText("theme", "Appearance", "Theme", "Light, dark or like the system"),
        SettingText("sync", "Sound", "Sound sync", "Picture and sound together"),
    )

    private fun anchors(list: List<SettingText>, q: String) = SettingsSearch.search(list, q).map { it.anchor }

    @Test
    fun ignoresCase() {
        assertEquals(listOf("quality"), anchors(english, "QUALITY"))
        assertEquals(listOf("quality"), anchors(english, "quaLity"))
    }

    @Test
    fun umlautsMatchWithAndWithoutDots() {
        assertEquals(listOf("quality"), anchors(german, "Qualität"))
        assertEquals(listOf("quality"), anchors(german, "qualitat"))
        assertEquals(listOf("quality"), anchors(german, "Qualitaet"))
        assertEquals(listOf("quality"), anchors(german, "QUALITÄT"))
    }

    @Test
    fun sharpSAndAeOeUeSpellings() {
        assertEquals(listOf("subtitle_style"), anchors(german, "größe"))
        assertEquals(listOf("subtitle_style"), anchors(german, "grosse"))
        assertEquals(listOf("subtitle_style"), anchors(german, "groesse"))
        assertEquals(listOf("dnd"), anchors(german, "storen"))
        assertEquals(listOf("dnd"), anchors(german, "stoeren"))
    }

    @Test
    fun accentsInTheQueryAreIgnoredToo() {
        assertEquals(listOf("theme"), anchors(english, "thème"))
    }

    @Test
    fun allWordsMustMatchInTitleOrDescription() {
        assertEquals(listOf("receive"), anchors(german, "handy streamen"))
        assertTrue(anchors(german, "handy farbe").isEmpty())
    }

    @Test
    fun punctuationDoesNotMatter() {
        assertEquals(listOf("network_check"), anchors(english, "wifi"))
        assertEquals(listOf("network_check"), anchors(english, "cant find"))
    }

    @Test
    fun titleMatchesComeFirst() {
        // "picture" is in the title of Quality's section only, in Sound sync's description.
        val list = listOf(
            SettingText("sync", "Sound", "Sound sync", "Picture and sound together"),
            SettingText("shape", "Picture", "Picture shape", "Fit or fill"),
        )
        assertEquals(listOf("shape", "sync"), anchors(list, "picture"))
    }

    @Test
    fun blankQueryFindsNothing() {
        assertTrue(anchors(english, "").isEmpty())
        assertTrue(anchors(english, "   ").isEmpty())
        assertTrue(anchors(english, " - ").isEmpty())
    }

    @Test
    fun normalizeStripsDiacritics() {
        assertEquals("grosse ubel cafe", SettingsSearch.normalize("Größe Übel Café"))
    }
}
