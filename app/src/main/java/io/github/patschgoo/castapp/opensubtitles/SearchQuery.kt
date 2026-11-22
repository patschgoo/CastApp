package io.github.patschgoo.castapp.opensubtitles

/** What to search for when the hash finds nothing: a title, and year or episode if the name tells. */
data class SearchQuery(val text: String, val year: Int? = null, val season: Int? = null, val episode: Int? = null) {
    companion object {
        private val EXTENSION = Regex("""\.[A-Za-z][A-Za-z0-9]{1,3}$""")
        private val BRACKETS = Regex("""\[[^\]]*\]|\{[^}]*\}""")
        private val EPISODE = Regex("""(?i)\bs(\d{1,2})[ ._-]?e(\d{1,3})\b|\b(\d{1,2})x(\d{2,3})\b""")
        private val YEAR = Regex("""\(?\b(19[2-9]\d|20[0-9]\d)\b\)?""")
        private val JUNK = Regex(
            """(?i)\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd|hdr10?|bluray|blu-ray|bdrip|brrip|bdremux|remux|web-?dl|web-?rip|webrip|""" +
                """hdtv|hdrip|dvdrip|dvdscr|dvd|xvid|divx|x[ .]?26[45]|h[ .]?26[45]|hevc|avc|10bit|aac\d?|ac3|eac3|dts|ddp?\d|truehd|atmos|""" +
                """proper|repack|unrated|remastered|subbed|dubbed|amzn|dsnp|hmax)\b""",
        )

        /** Makes a search from a file name like "The.Matrix.1999.1080p.BluRay.x264-GRP.mkv" → "the matrix", 1999. */
        fun fromFileName(name: String): SearchQuery {
            var s = name.substringAfterLast('/').trim()
            s = s.replace(EXTENSION, "")
            s = s.replace(BRACKETS, " ")
            s = s.replace('_', ' ').replace('.', ' ').replace(Regex("\\s+"), " ").trim()

            var season: Int? = null
            var episode: Int? = null
            EPISODE.find(s)?.let { m ->
                season = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull()
                episode = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull()
                s = s.substring(0, m.range.first)
            }
            JUNK.find(s)?.let { m -> if (m.range.first > 0) s = s.substring(0, m.range.first) }
            var year: Int? = null
            // The last year ends the title; one at the very start is part of it ("2001 A Space Odyssey").
            YEAR.findAll(s).lastOrNull { it.range.first > 0 }?.let { m ->
                year = m.groupValues[1].toInt()
                s = s.substring(0, m.range.first)
            }
            val text = s.replace(Regex("[()]"), " ").trim(' ', '-', ',').replace(Regex("\\s+"), " ").lowercase()
            return SearchQuery(text.ifEmpty { name.lowercase() }, year, season, episode)
        }
    }
}
