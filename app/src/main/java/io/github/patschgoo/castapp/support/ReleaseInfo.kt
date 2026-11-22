package io.github.patschgoo.castapp.support

import org.json.JSONObject

/** The parts of a GitHub release that the update check needs. */
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSize: Long,
    val prerelease: Boolean,
) {
    /** The version without a leading "v". */
    val version: String get() = tag.removePrefix("v").removePrefix("V")

    companion object {
        const val APK_NAME = "CastApp.apk"

        /** Parses the answer of `GET /repos/{owner}/{repo}/releases/latest`; null if it is not a release. */
        fun parse(json: String): ReleaseInfo? = try {
            val o = JSONObject(json)
            val tag = o.optString("tag_name").ifEmpty { return null }
            var apk: String? = null
            var size = 0L
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").equals(APK_NAME, ignoreCase = true)) {
                        apk = a.optString("browser_download_url").ifEmpty { null }
                        size = a.optLong("size", 0L)
                        break
                    }
                }
            }
            ReleaseInfo(
                tag = tag,
                name = o.optString("name").ifEmpty { tag },
                notes = o.optString("body").trim(),
                pageUrl = o.optString("html_url").ifEmpty { Updates.RELEASES_PAGE },
                apkUrl = apk,
                apkSize = size,
                prerelease = o.optBoolean("prerelease", false),
            )
        } catch (_: Exception) {
            null
        }
    }
}
