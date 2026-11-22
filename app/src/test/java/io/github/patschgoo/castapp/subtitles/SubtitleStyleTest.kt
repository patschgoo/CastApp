package io.github.patschgoo.castapp.subtitles

import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.tv.UpnpRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStyleTest {
    @Test
    fun castTextTrackStyle() {
        val box = SubtitleStyle(SubtitleSize.LARGE, SubtitleColor.YELLOW, SubtitleBackground.BOX).toCastJson()
        assertEquals(1.35, box.getDouble("fontScale"), 1e-6)
        assertEquals("#FFEB3BFF", box.getString("foregroundColor"))
        assertEquals("#000000B3", box.getString("backgroundColor"))
        assertEquals("NONE", box.getString("edgeType"))

        val shadow = SubtitleStyle(SubtitleSize.SMALL, SubtitleColor.WHITE, SubtitleBackground.SHADOW).toCastJson()
        assertEquals(0.75, shadow.getDouble("fontScale"), 1e-6)
        assertEquals("#FFFFFFFF", shadow.getString("foregroundColor"))
        assertEquals("#00000000", shadow.getString("backgroundColor"))
        assertEquals("DROP_SHADOW", shadow.getString("edgeType"))
        assertEquals("#000000FF", shadow.getString("edgeColor"))

        val none = SubtitleStyle(background = SubtitleBackground.NONE).toCastJson()
        assertEquals("#00000000", none.getString("backgroundColor"))
        assertEquals("NONE", none.getString("edgeType"))
    }

    @Test
    fun androidColours() {
        val s = SubtitleStyle(color = SubtitleColor.CYAN, background = SubtitleBackground.BOX)
        assertEquals(0xFF4DD0E1.toInt(), s.foregroundArgb)
        assertEquals(0xB3000000.toInt(), s.backgroundArgb)
        assertEquals(0, s.copy(background = SubtitleBackground.SHADOW).backgroundArgb)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        for (size in SubtitleSize.entries) for (color in SubtitleColor.entries) for (bg in SubtitleBackground.entries) {
            val s = SubtitleStyle(size, color, bg)
            assertEquals(s, SubtitleStyle.decode(s.encode()))
        }
        assertEquals("size=1.35;fg=FFEB3B;bg=shadow", SubtitleStyle(SubtitleSize.LARGE, SubtitleColor.YELLOW, SubtitleBackground.SHADOW).encode())
        assertNull(SubtitleStyle.decode(null))
        assertNull(SubtitleStyle.decode("garbage"))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("size=x;fg=zz;bg=?")) // unknown values: defaults
    }

    @Test
    fun travelsToCastAppTvInTheDidl() {
        val style = SubtitleStyle(SubtitleSize.HUGE, SubtitleColor.GREEN, SubtitleBackground.NONE).encode()
        val didl = Dlna.didl("Film", "http://phone/file/1/film.mp4", "video/mp4", false, "http://phone/st/1/file/0/0.srt", subtitleStyle = style)
        assertTrue(didl.contains(Dlna.SUBTITLE_STYLE_PREFIX))
        assertEquals(style, Dlna.subtitleStyleIn(didl))
        val media = UpnpRenderer.parseMedia("http://phone/file/1/film.mp4", didl)
        assertEquals(style, media.subtitleStyle)
        assertEquals("http://phone/st/1/file/0/0.srt", media.subtitleUri)
        // No subtitles, or none asked for: nothing extra in the metadata.
        assertFalse(Dlna.didl("Film", "http://phone/f.mp4", "video/mp4", false, null, subtitleStyle = style).contains(Dlna.SUBTITLE_STYLE_PREFIX))
        assertNull(UpnpRenderer.parseMedia("http://x/f.mp4", Dlna.didl("F", "http://x/f.mp4", "video/mp4", false, "http://x/s.srt")).subtitleStyle)
    }
}
