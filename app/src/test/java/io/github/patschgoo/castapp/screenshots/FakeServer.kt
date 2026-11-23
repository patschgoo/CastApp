package io.github.patschgoo.castapp.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.patschgoo.castapp.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/**
 * A local stand-in for a Jellyfin server and an IPTV provider (playlist, TV guide, logos), with
 * made-up films, shows and channels and posters painted on the spot. Nothing leaves the machine.
 */
class FakeServer(context: Context, private val de: Boolean = false) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    /** The server's libraries, named as a German server would name them when [de]. */
    val libraries = listOf(
        Library("lib-movies", if (de) "Filme" else "Movies", "movies"),
        Library("lib-shows", if (de) "Serien" else "Shows", "tvshows"),
        Library("lib-music", if (de) "Musik" else "Music", "music"),
        Library("lib-home", if (de) "Heimvideos" else "Home videos", "homevideos"),
    )

    /** How many pictures were sent, to wait until the posters are on screen. */
    val picturesServed = AtomicInteger()

    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans_extrabold) ?: Typeface.DEFAULT_BOLD
    private val pictures: Map<String, ByteArray>

    init {
        val p = HashMap<String, ByteArray>()
        (MOVIES + SHOWS).forEachIndexed { i, m -> p["/Items/${m.id}/Images/Primary"] = Painter.poster(m.title, i, bold) }
        EPISODES.forEachIndexed { i, e -> p["/Items/${e.id}/Images/Primary"] = Painter.still(e.title, i + 3, bold) }
        CHANNELS.forEachIndexed { i, c -> p["/logo/${c.id}.png"] = Painter.logo(c.short, i, bold) }
        pictures = p
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val query = ex.requestURI.rawQuery.orEmpty()
        try {
            val picture = pictures[path]
            when {
                picture != null -> {
                    picturesServed.incrementAndGet()
                    send(ex, 200, picture, "image/png")
                }
                path == "/Users/u1/Views" -> json(ex, items(libraries.map { it.json() }))
                path == "/Users/u1/Items/Resume" -> json(ex, items(MOVIES.filter { it.resume > 0 }.map { it.json() }))
                path == "/Shows/NextUp" -> json(ex, items(EPISODES.map { it.json() }))
                path == "/Users/u1/Items" && query.contains("ParentId=lib-movies") -> json(ex, items(MOVIES.map { it.json() }))
                path == "/Users/u1/Items" && query.contains("ParentId=lib-shows") -> json(ex, items(SHOWS.map { it.json() }))
                path == "/Users/u1/Items" -> json(ex, items(emptyList()))
                path == "/iptv/list.m3u" -> send(ex, 200, playlist().toByteArray(), "audio/x-mpegurl")
                path == "/iptv/guide.xml" -> send(ex, 200, guide().toByteArray(), "application/xml")
                else -> send(ex, 404, "{}".toByteArray(), "application/json")
            }
        } catch (e: Exception) {
            send(ex, 500, e.toString().toByteArray(), "text/plain")
        }
    }

    private fun items(list: List<JSONObject>) = JSONObject().put("Items", JSONArray(list)).put("TotalRecordCount", list.size)

    private fun json(ex: HttpExchange, o: JSONObject) = send(ex, 200, o.toString().toByteArray(), "application/json")

    private fun send(ex: HttpExchange, code: Int, body: ByteArray, type: String) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
        ex.close()
    }

    // ---- IPTV ---------------------------------------------------------------------------------

    private fun playlist(): String = buildString {
        append("#EXTM3U url-tvg=\"$url/iptv/guide.xml\"\n")
        for (c in CHANNELS) {
            append("#EXTINF:-1 tvg-id=\"${c.id}\" tvg-logo=\"$url/logo/${c.id}.png\" group-title=\"${c.group}\",${c.name}\n")
            append("$url/live/${c.id}.m3u8\n")
        }
    }

    /** Eight hours of programmes around now, on every channel. */
    private fun guide(): String {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val slot = 30 * 60_000L
        val start = System.currentTimeMillis() / slot * slot - 4 * slot
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"castapp-screenshots\">\n")
            for (c in CHANNELS) append("<channel id=\"${c.id}\"><display-name>${c.name}</display-name></channel>\n")
            CHANNELS.forEachIndexed { ci, c ->
                var t = start - (ci % 3) * 15 * 60_000L
                var n = 0
                while (t < start + 16 * slot) {
                    val title = c.shows[(n + ci) % c.shows.size]
                    val length = LENGTHS[(n * 3 + ci) % LENGTHS.size] * 60_000L
                    append("<programme start=\"${fmt.format(t)}\" stop=\"${fmt.format(t + length)}\" channel=\"${c.id}\">")
                    append("<title>$title</title><desc>A made-up programme for the screenshots.</desc></programme>\n")
                    t += length
                    n++
                }
            }
            append("</tv>\n")
        }
    }

    override fun close() = server.stop(0)

    // ---- made-up content -------------------------------------------------------------------------

    class Library(val id: String, val name: String, val type: String) {
        fun json(): JSONObject = JSONObject().put("Id", id).put("Name", name).put("Type", "CollectionFolder").put("CollectionType", type)
    }

    class Film(val id: String, val title: String, val year: Int, val minutes: Int, val resume: Int = 0, val played: Boolean = false, val series: Boolean = false, val unplayed: Int = 0) {
        fun json(): JSONObject {
            val user = JSONObject().put("Played", played).put("PlaybackPositionTicks", resume * 60L * 10_000_000L)
            if (series) user.put("UnplayedItemCount", unplayed)
            return JSONObject().put("Id", id).put("Name", title).put("Type", if (series) "Series" else "Movie")
                .put("ProductionYear", year).put("RunTimeTicks", minutes * 60L * 10_000_000L)
                .put("ImageTags", JSONObject().put("Primary", "tag-$id")).put("UserData", user)
        }
    }

    class Episode(val id: String, val title: String, val series: String, val season: Int, val number: Int) {
        fun json(): JSONObject = JSONObject().put("Id", id).put("Name", title).put("Type", "Episode")
            .put("SeriesName", series).put("SeriesId", "s-" + series.hashCode()).put("ParentIndexNumber", season).put("IndexNumber", number)
            .put("RunTimeTicks", 42 * 60L * 10_000_000L).put("ImageTags", JSONObject().put("Primary", "tag-$id"))
            .put("UserData", JSONObject().put("Played", false).put("PlaybackPositionTicks", 0))
    }

    class Station(val id: String, val name: String, val short: String, val group: String, val shows: List<String>)

    companion object {
        const val ACCOUNT_NAME = "Media Room"

        val MOVIES = listOf(
            Film("m1", "Saltwater Summer", 2023, 104, resume = 38),
            Film("m2", "The Clockmaker's Garden", 2021, 118, played = true),
            Film("m3", "Neon Harbor", 2024, 97),
            Film("m4", "Ember & Frost", 2022, 126, resume = 71),
            Film("m5", "Quiet Orbit", 2020, 92),
            Film("m6", "Wildflower Road", 2023, 109, played = true),
            Film("m7", "The Long Tide", 2019, 131),
            Film("m8", "Copper Canyon Run", 2024, 101),
            Film("m9", "Glass Mountain", 2022, 113),
            Film("m10", "Moonlit Market", 2021, 88),
            Film("m11", "Atlas of Small Things", 2024, 95),
            Film("m12", "Harbor Lights Bakery", 2020, 99),
        )

        val SHOWS = listOf(
            Film("s1", "Sunday Kitchen", 2023, 0, series = true, unplayed = 4),
            Film("s2", "Northbound", 2022, 0, series = true, unplayed = 0),
            Film("s3", "The Lantern Society", 2024, 0, series = true, unplayed = 8),
        )

        val EPISODES = listOf(
            Episode("e1", "The Night Ferry", "Northbound", 2, 3),
            Episode("e2", "Sourdough Season", "Sunday Kitchen", 1, 6),
            Episode("e3", "A Light in the Archive", "The Lantern Society", 1, 2),
        )

        private val LENGTHS = listOf(30, 60, 45, 90, 30, 60, 120, 30)

        val CHANNELS = listOf(
            Station("ch1", "Harbor One", "H1", "Entertainment", listOf("Morning Harbor", "Quiz Night", "The Big Bake-Off", "Late Talk", "Garden Rescue")),
            Station("ch2", "Metro News 24", "M24", "News", listOf("News at the Hour", "Business Today", "Weather Watch", "World Report")),
            Station("ch3", "Alpine HD", "AHD", "Documentary", listOf("Peaks and Valleys", "Glacier Diaries", "Mountain Rescue", "Wild Alps")),
            Station("ch4", "Kids Planet", "KP", "Kids", listOf("Robo Pals", "Story Time", "Dino Detectives", "Paint with Pip")),
            Station("ch5", "Arena Sport", "AS", "Sports", listOf("Matchday Live", "Goals & Glory", "Tennis Highlights", "Cycling: Stage 9")),
            Station("ch6", "Cine Classics", "CC", "Movies", listOf("Saltwater Summer", "The Long Tide", "Moonlit Market", "Glass Mountain")),
            Station("ch7", "Nature World", "NW", "Documentary", listOf("Ocean Giants", "The Secret Forest", "Birds of Paradise", "Desert Nights")),
            Station("ch8", "Music Box", "MB", "Music", listOf("Top 40 Countdown", "Acoustic Sessions", "Throwback Hits", "Night Grooves")),
        )
    }
}

/** Paints posters, episode stills and channel logos: a gradient, a few shapes and the title. */
object Painter {
    private val palettes = listOf(
        intArrayOf(0xFF1E3A8A.toInt(), 0xFFF59E0B.toInt(), 0xFF0F172A.toInt()),
        intArrayOf(0xFF065F46.toInt(), 0xFFFDE68A.toInt(), 0xFF022C22.toInt()),
        intArrayOf(0xFF7C2D12.toInt(), 0xFFFDBA74.toInt(), 0xFF2A0F06.toInt()),
        intArrayOf(0xFF4C1D95.toInt(), 0xFFF0ABFC.toInt(), 0xFF1E0B3D.toInt()),
        intArrayOf(0xFF0E7490.toInt(), 0xFFE0F2FE.toInt(), 0xFF082F49.toInt()),
        intArrayOf(0xFF9F1239.toInt(), 0xFFFECDD3.toInt(), 0xFF3F0617.toInt()),
        intArrayOf(0xFF3F6212.toInt(), 0xFFFEF08A.toInt(), 0xFF1A2E05.toInt()),
        intArrayOf(0xFF1F2937.toInt(), 0xFF93C5FD.toInt(), 0xFF030712.toInt()),
    )

    fun poster(title: String, seed: Int, font: Typeface): ByteArray = paint(400, 600, title, seed, font, titleSize = 46f)

    fun still(title: String, seed: Int, font: Typeface): ByteArray = paint(640, 360, title, seed, font, titleSize = 40f)

    private fun paint(w: Int, h: Int, title: String, seed: Int, font: Typeface, titleSize: Float): ByteArray {
        val (top, accent, ground) = palettes[seed % palettes.size].let { Triple(it[0], it[1], it[2]) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, ground, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        // A sun or moon, and two ranges of hills in front of it.
        p.color = accent
        val sunX = w * (0.25f + 0.5f * ((seed * 37) % 10) / 10f)
        c.drawCircle(sunX, h * 0.34f, w * 0.17f, p)
        p.color = (ground and 0x00FFFFFF) or (0xB0 shl 24)
        c.drawPath(hills(w, h, 0.58f, seed), p)
        p.color = ground
        c.drawPath(hills(w, h, 0.70f, seed + 3), p)
        // The title, wrapped, at the bottom.
        p.color = 0xFFFFFFFF.toInt()
        p.typeface = font
        p.textSize = titleSize
        val lines = wrap(title, p, w - 48f)
        var y = h - 32f - (lines.size - 1) * titleSize * 1.1f
        for (line in lines) {
            c.drawText(line, 24f, y, p)
            y += titleSize * 1.1f
        }
        return png(bmp)
    }

    private fun hills(w: Int, h: Int, level: Float, seed: Int): Path = Path().apply {
        moveTo(0f, h * level)
        val steps = 5
        for (i in 1..steps) {
            val x = w * i / steps.toFloat()
            val bump = if ((i + seed) % 2 == 0) -0.08f else 0.04f
            lineTo(x, h * (level + bump))
        }
        lineTo(w.toFloat(), h.toFloat())
        lineTo(0f, h.toFloat())
        close()
    }

    private fun wrap(text: String, p: Paint, max: Float): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(next) <= max || line.isEmpty()) line = next else {
                lines += line
                line = word
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    fun logo(text: String, seed: Int, font: Typeface): ByteArray {
        val size = 160
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = palettes[seed % palettes.size][0]
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 36f, 36f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.typeface = font
        p.textSize = if (text.length > 2) 52f else 68f
        p.textAlign = Paint.Align.CENTER
        c.drawText(text, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
        return png(bmp)
    }

    private fun png(bmp: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}
