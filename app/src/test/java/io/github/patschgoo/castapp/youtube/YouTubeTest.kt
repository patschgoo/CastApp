package io.github.patschgoo.castapp.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class YouTubeTest {
    @Test
    fun parsesTheUsualLinkShapes() {
        val id = "dQw4w9WgXcQ"
        for (url in listOf(
            "https://www.youtube.com/watch?v=$id",
            "http://youtube.com/watch?feature=share&v=$id",
            "https://m.youtube.com/watch?v=$id&pp=ygUJcmljayByb2xs",
            "https://music.youtube.com/watch?v=$id&si=abc",
            "https://youtu.be/$id",
            "https://youtu.be/$id?si=Xy12",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id?feature=share",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube-nocookie.com/embed/$id?rel=0",
            "www.youtube.com/watch?v=$id",
        )) {
            assertEquals(url, YouTubeLink(id), YouTubeLink.parse(url))
        }
    }

    @Test
    fun readsPlaylistAndStartTime() {
        assertEquals(
            YouTubeLink("dQw4w9WgXcQ", "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", 83),
            YouTubeLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI&index=3&t=1m23s"),
        )
        assertEquals(42, YouTubeLink.parse("https://youtu.be/dQw4w9WgXcQ?t=42")!!.startSeconds)
        assertEquals(30, YouTubeLink.parse("https://www.youtube.com/embed/dQw4w9WgXcQ?start=30")!!.startSeconds)
        assertEquals(3723, YouTubeLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ#t=1h2m3s")!!.startSeconds)
        assertEquals(0, YouTubeLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=nonsense")!!.startSeconds)
    }

    @Test
    fun parsesTimes() {
        assertEquals(90, YouTubeLink.parseTime("90"))
        assertEquals(90, YouTubeLink.parseTime("90s"))
        assertEquals(90, YouTubeLink.parseTime("1m30s"))
        assertEquals(120, YouTubeLink.parseTime("2m"))
        assertEquals(3600, YouTubeLink.parseTime("1h"))
        assertEquals(3723, YouTubeLink.parseTime("1h2m3s"))
        assertEquals(90, YouTubeLink.parseTime("01:30"))
        assertEquals(3723, YouTubeLink.parseTime("1:02:03"))
        assertNull(YouTubeLink.parseTime(""))
        assertNull(YouTubeLink.parseTime("abc"))
        assertNull(YouTubeLink.parseTime("-5"))
        assertEquals("1:23", YouTubeLink.formatTime(83))
        assertEquals("1:02:03", YouTubeLink.formatTime(3723))
    }

    @Test
    fun rejectsWhatIsNotAVideo() {
        for (url in listOf(
            null, "", "https://www.youtube.com/", "https://www.youtube.com/@somechannel",
            "https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", // no video to start with
            "https://www.youtube.com/watch?v=short", "https://evil.example/watch?v=dQw4w9WgXcQ",
            "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ", "intent://youtu.be/dQw4w9WgXcQ",
            "javascript:alert(1)", "https://youtu.be/dQw4w9WgXcQ\"><script>",
        )) {
            assertNull(url, YouTubeLink.parse(url))
        }
        assertTrue(YouTubeLink.isYouTubeUrl("https://m.youtube.com/@x"))
        assertFalse(YouTubeLink.isYouTubeUrl("https://example.com/youtube.com"))
    }

    @Test
    fun canonicalUrlIsCleanAndRoundTrips() {
        val link = YouTubeLink("dQw4w9WgXcQ", "PL123abc", 61)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123abc&t=61s", link.canonicalUrl)
        assertEquals(link, YouTubeLink.parse(link.canonicalUrl))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", YouTubeLink("dQw4w9WgXcQ").canonicalUrl)
    }

    // ---- lounge requests, compared with what casttube 0.2.1 sends (encoded with Python's urlencode) ----

    @Test
    fun setPlaylistMatchesCasttube() {
        val form = YouTubeLounge.Requests.setPlaylistForm(0, YouTubeLink("dQw4w9WgXcQ", "PLx", 83))
        assertEquals(
            "req0_listId=PLx&req0__sc=setPlaylist&req0_currentTime=83&req0_currentIndex=-1&req0_audioOnly=false&req0_videoId=dQw4w9WgXcQ&count=1",
            YouTubeLounge.Requests.encodeForm(form),
        )
        // Without a playlist casttube sends an empty list id.
        assertEquals("req2_listId" to "", YouTubeLounge.Requests.setPlaylistForm(2, YouTubeLink("dQw4w9WgXcQ"))[0])
    }

    @Test
    fun bindAndTokenRequestsMatchCasttube() {
        assertEquals(
            "device=REMOTE_CONTROL&id=aaaaaaaaaaaaaaaaaaaaaaaaaa&name=CastApp&mdx-version=3&pairing_type=cast&app=android-phone-13.14.55",
            YouTubeLounge.Requests.encodeForm(YouTubeLounge.Requests.bindForm()),
        )
        assertEquals("https://www.youtube.com/api/lounge/bc/bind?RID=0&VER=8&CVER=1", YouTubeLounge.Requests.bindUrl(0))
        assertEquals(
            "https://www.youtube.com/api/lounge/bc/bind?SID=abc&gsessionid=def&RID=1&VER=8&CVER=1",
            YouTubeLounge.Requests.sessionUrl("abc", "def", 1),
        )
        assertEquals("screen_ids=scr%2B1", YouTubeLounge.Requests.encodeForm(YouTubeLounge.Requests.loungeTokenForm("scr+1")))
    }

    @Test
    fun parsesAnswers() {
        assertEquals(
            "TOKEN-1",
            YouTubeLounge.Requests.parseLoungeToken("""{"screens":[{"screenId":"s1","loungeToken":"TOKEN-1","expiration":1700000000000}]}"""),
        )
        assertNull(YouTubeLounge.Requests.parseLoungeToken("""{"screens":[]}"""))
        assertNull(YouTubeLounge.Requests.parseLoungeToken("<html>"))
        val bind = "245\n[[0,[\"c\",\"SID-XYZ\",\"\",8]\n]\n,[1,[\"S\",\"GSESSION-1\"]]\n,[2,[\"loungeStatus\",{}]]]"
        assertEquals("SID-XYZ" to "GSESSION-1", YouTubeLounge.Requests.parseBind(bind))
        assertNull(YouTubeLounge.Requests.parseBind("[[0,[\"noop\"]]]"))
        assertEquals("abc", YouTubeLounge.screenIdFrom(JSONObject("""{"type":"mdxSessionStatus","data":{"screenId":"abc"}}""")))
        assertNull(YouTubeLounge.screenIdFrom(JSONObject("""{"type":"mdxSessionStatus","data":{}}""")))
    }

    private class Call(val url: String, val form: List<Pair<String, String>>, val headers: Map<String, String>)

    private fun fakeYouTube(calls: MutableList<Call>, firstSetPlaylistCode: Int = 200) = YouTubeLounge.Http { url, form, headers ->
        calls += Call(url, form, headers)
        when {
            url.endsWith("get_lounge_token_batch") -> 200 to """{"screens":[{"screenId":"scr","loungeToken":"LT"}]}"""
            url.contains("RID=0") -> 200 to "[[0,[\"c\",\"SID${calls.size}\",\"\",8]],[1,[\"S\",\"GS\"]]]"
            calls.count { it.url.contains("SID=") } == 1 && firstSetPlaylistCode != 200 -> firstSetPlaylistCode to ""
            else -> 200 to "ok"
        }
    }

    @Test
    fun playRunsTheCasttubeSequence() {
        val calls = ArrayList<Call>()
        YouTubeLounge(fakeYouTube(calls)).play("scr", YouTubeLink("dQw4w9WgXcQ", startSeconds = 10))
        assertEquals(3, calls.size)
        assertEquals(YouTubeLounge.LOUNGE_TOKEN_URL, calls[0].url)
        assertEquals(listOf("screen_ids" to "scr"), calls[0].form)
        assertEquals(YouTubeLounge.Requests.bindUrl(0), calls[1].url)
        assertEquals("LT", calls[1].headers[YouTubeLounge.LOUNGE_ID_HEADER])
        assertEquals(YouTubeLounge.Requests.sessionUrl("SID2", "GS", 1), calls[2].url)
        assertTrue(calls[2].form.contains("req0_videoId" to "dQw4w9WgXcQ"))
        assertTrue(calls[2].form.contains("req0_currentTime" to "10"))
        for (c in calls) {
            assertEquals("https://www.youtube.com/", c.headers["Origin"])
            assertEquals("application/x-www-form-urlencoded", c.headers["Content-Type"])
        }
    }

    @Test
    fun staleSessionIsBoundAgain() {
        val calls = ArrayList<Call>()
        YouTubeLounge(fakeYouTube(calls, firstSetPlaylistCode = 400)).play("scr", YouTubeLink("dQw4w9WgXcQ"))
        assertEquals(5, calls.size) // token, bind, setPlaylist (400), bind, setPlaylist
        assertTrue(calls[3].url.contains("RID=0"))
        assertTrue(calls[4].url.contains("SID=SID4"))
    }

    @Test
    fun refusalsBecomeErrorsWithTheStatus() {
        val lounge = YouTubeLounge { _, _, _ -> 403 to "denied" }
        try {
            lounge.play("scr", YouTubeLink("dQw4w9WgXcQ"))
            fail("should throw")
        } catch (e: YouTubeLounge.LoungeException) {
            assertEquals(403, e.httpCode)
        }
    }
}
