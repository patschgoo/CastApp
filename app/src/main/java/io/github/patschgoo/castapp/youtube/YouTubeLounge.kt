package io.github.patschgoo.castapp.youtube

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * YouTube's "lounge" (MDX) remote-control protocol, the way the YouTube app on a
 * phone tells a TV what to play. Follows the open-source `casttube` library that
 * pychromecast's YouTube controller uses:
 *
 *  1. The YouTube receiver on the TV tells us its screen id (Cast namespace
 *     `urn:x-cast:com.google.youtube.mdx`, message `getMdxSessionStatus`).
 *  2. `get_lounge_token_batch` turns the screen id into a lounge token.
 *  3. `bc/bind` opens a session (SID and gsessionid).
 *  4. A `setPlaylist` request through `bc/bind` starts the video.
 *
 * The requests go to youtube.com over the internet (the TV fetches the video itself).
 */
class YouTubeLounge(private val http: Http = UrlConnectionHttp) {
    /** One HTTP POST: url, form body, extra headers -> status code and body. */
    fun interface Http {
        fun post(url: String, form: List<Pair<String, String>>, headers: Map<String, String>): Pair<Int, String>
    }

    class LoungeException(message: String, val httpCode: Int = -1) : IOException(message)

    /** A bound lounge session. */
    class Session(val loungeToken: String, val sid: String, val gsessionId: String) {
        internal var rid = 1 // the bind itself was RID 0
        internal var requestCount = 0
    }

    /** Starts [link] on the TV with [screenId]. Blocking; call from a background thread. */
    fun play(screenId: String, link: YouTubeLink) {
        val token = loungeToken(screenId)
        val session = bind(token)
        try {
            setPlaylist(session, link)
        } catch (e: LoungeException) {
            // casttube: 400/404 mean the session went stale; binding again restores it.
            if (e.httpCode != 400 && e.httpCode != 404) throw e
            setPlaylist(bind(token), link)
        }
    }

    fun loungeToken(screenId: String): String {
        val (code, body) = postChecked(LOUNGE_TOKEN_URL, Requests.loungeTokenForm(screenId), emptyMap())
        return Requests.parseLoungeToken(body) ?: throw LoungeException("No lounge token in the answer", code)
    }

    fun bind(loungeToken: String): Session {
        val (code, body) = postChecked(
            Requests.bindUrl(rid = 0), Requests.bindForm(), mapOf(LOUNGE_ID_HEADER to loungeToken),
        )
        val (sid, gsession) = Requests.parseBind(body) ?: throw LoungeException("YouTube didn't open a session", code)
        return Session(loungeToken, sid, gsession)
    }

    fun setPlaylist(session: Session, link: YouTubeLink) {
        val url = Requests.sessionUrl(session.sid, session.gsessionId, session.rid)
        postChecked(url, Requests.setPlaylistForm(session.requestCount, link), mapOf(LOUNGE_ID_HEADER to session.loungeToken))
        session.rid++
        session.requestCount++
    }

    private fun postChecked(url: String, form: List<Pair<String, String>>, headers: Map<String, String>): Pair<Int, String> {
        val result = http.post(url, form, BASE_HEADERS + headers)
        if (result.first !in 200..299) throw LoungeException("YouTube answered HTTP ${result.first}", result.first)
        return result
    }

    /** The request formats (pure functions, unit-tested against casttube's). */
    object Requests {
        /** Who we are to the TV ("CastApp connected" may show there). Same fields as casttube's BIND_DATA. */
        fun bindForm(deviceId: String = DEVICE_ID): List<Pair<String, String>> = listOf(
            "device" to "REMOTE_CONTROL",
            "id" to deviceId,
            "name" to "CastApp",
            "mdx-version" to "3",
            "pairing_type" to "cast",
            "app" to "android-phone-13.14.55",
        )

        fun loungeTokenForm(screenId: String): List<Pair<String, String>> = listOf("screen_ids" to screenId)

        fun bindUrl(rid: Int): String = "$BIND_URL?RID=$rid&VER=8&CVER=1"

        fun sessionUrl(sid: String, gsessionId: String, rid: Int): String =
            "$BIND_URL?SID=${enc(sid)}&gsessionid=${enc(gsessionId)}&RID=$rid&VER=8&CVER=1"

        /**
         * casttube's `_initialize_queue`: keys starting with "_" get the "req<N>" prefix,
         * so "_videoId" becomes "req0_videoId" and "__sc" "req0__sc".
         */
        fun setPlaylistForm(requestCount: Int, link: YouTubeLink): List<Pair<String, String>> {
            val p = "req$requestCount"
            return listOf(
                "${p}_listId" to (link.listId ?: ""),
                "${p}__sc" to "setPlaylist",
                "${p}_currentTime" to link.startSeconds.toString(),
                "${p}_currentIndex" to "-1",
                "${p}_audioOnly" to "false",
                "${p}_videoId" to link.videoId,
                "count" to "1",
            )
        }

        fun parseLoungeToken(body: String): String? = try {
            JSONObject(body).optJSONArray("screens")?.optJSONObject(0)?.optString("loungeToken")?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }

        /** SID and gsessionid from the bind answer: `["c","<SID>","",8]` and `["S","<gsessionid>"]`. */
        fun parseBind(body: String): Pair<String, String>? {
            val sid = Regex("\\[\"c\",\"(.*?)\",\"").find(body)?.groupValues?.get(1)
            val gsession = Regex("\\[\"S\",\"(.*?)\"]").find(body)?.groupValues?.get(1)
            if (sid.isNullOrEmpty() || gsession.isNullOrEmpty()) return null
            return sid to gsession
        }

        fun encodeForm(form: List<Pair<String, String>>): String =
            form.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }

    private object UrlConnectionHttp : Http {
        override fun post(url: String, form: List<Pair<String, String>>, headers: Map<String, String>): Pair<Int, String> {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 10_000
                conn.doOutput = true
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val bytes = Requests.encodeForm(form).toByteArray()
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                val text = (if (code >= 400) conn.errorStream else conn.inputStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                return code to text
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        const val YOUTUBE_APP_ID = "233637DE"
        const val NAMESPACE = "urn:x-cast:com.google.youtube.mdx"
        const val TYPE_GET_STATUS = "getMdxSessionStatus"
        const val TYPE_STATUS = "mdxSessionStatus"
        private const val BASE_URL = "https://www.youtube.com/"
        const val BIND_URL = BASE_URL + "api/lounge/bc/bind"
        const val LOUNGE_TOKEN_URL = BASE_URL + "api/lounge/pairing/get_lounge_token_batch"
        const val LOUNGE_ID_HEADER = "X-YouTube-LoungeId-Token"
        private const val DEVICE_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
        val BASE_HEADERS = mapOf("Origin" to BASE_URL, "Content-Type" to "application/x-www-form-urlencoded")

        /** The screen id in the YouTube receiver's `mdxSessionStatus` message. */
        fun screenIdFrom(message: JSONObject): String? =
            message.optJSONObject("data")?.optString("screenId")?.takeIf { it.isNotEmpty() }
    }
}
