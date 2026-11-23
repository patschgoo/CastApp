package io.github.patschgoo.castapp.automation

import io.github.patschgoo.castapp.automation.AutomationApi as Api
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CAST_QUEUE: the JSON, its limits, content:// grants, the "Play on TV" sheet path and report_to. */
class CastQueueParserTest {
    private val open = AutomationConfig(enabled = true, secret = "")
    private val locked = AutomationConfig(enabled = true, secret = "s3cret")
    private val off = AutomationConfig(enabled = false, secret = "")

    private fun parse(
        extras: Map<String, Any?>,
        config: AutomationConfig = open,
        granted: Set<String> = emptySet(),
        caller: CallerEvidence = CallerEvidence.NONE,
    ) = AutomationParser.parse(Api.ACTION_CAST_QUEUE, MapExtras(extras, grantedUris = granted), config, caller)

    private fun queue(json: String, more: Map<String, Any?> = emptyMap(), granted: Set<String> = emptySet(), config: AutomationConfig = open, caller: CallerEvidence = CallerEvidence.NONE): AutomationCommand.CastQueue {
        val p = parse(mapOf(Api.EXTRA_QUEUE to json) + more, config, granted, caller)
        assertTrue("expected a queue, got $p", p is ParsedRequest.Accepted)
        return (p as ParsedRequest.Accepted).command as AutomationCommand.CastQueue
    }

    private fun refused(json: String?, more: Map<String, Any?> = emptyMap(), granted: Set<String> = emptySet(), config: AutomationConfig = open, caller: CallerEvidence = CallerEvidence.NONE): AutomationResult {
        val p = parse((if (json != null) mapOf(Api.EXTRA_QUEUE to json) else emptyMap()) + more, config, granted, caller)
        assertTrue("expected a refusal, got $p", p is ParsedRequest.Rejected)
        return (p as ParsedRequest.Rejected).result
    }

    private val episodes = """
        [
          {"id": "ep-1", "url": "https://media.example/show/01.mkv", "title": "Episode 1", "mime": "video/x-matroska",
           "subtitles": [{"url": "https://media.example/show/01.de.ass", "language": "de", "label": "Signs"}, {"url": "https://media.example/show/01.en.srt", "language": "en"}],
           "art": "https://media.example/show/poster.jpg"},
          {"id": "ep-2", "url": "https://media.example/show/02.mkv", "title": "Episode 2"},
          {"id": "ep-3", "url": "https://media.example/show/03.mp4"}
        ]
    """.trimIndent()

    // ---- good requests -----------------------------------------------------------------------------

    @Test
    fun aFullQueueKeepsItsOrderAndEveryField() {
        val c = queue(episodes, mapOf(Api.EXTRA_DEVICE to "Living room", Api.EXTRA_START_INDEX to 1, Api.EXTRA_START_POSITION to 754_000L, Api.EXTRA_TITLE to "My show"))
        assertEquals(DeviceRef(either = "Living room"), c.device)
        val q = c.queue
        assertEquals(listOf("ep-1", "ep-2", "ep-3"), q.items.map { it.id })
        assertEquals(listOf("https://media.example/show/01.mkv", "https://media.example/show/02.mkv", "https://media.example/show/03.mp4"), q.items.map { it.url })
        assertEquals(1, q.startIndex)
        assertEquals(754_000L, q.startPositionMs)
        assertEquals("My show", q.title)
        val first = q.items[0]
        assertEquals("Episode 1", first.title)
        assertEquals("video/x-matroska", first.mime)
        assertEquals("https://media.example/show/poster.jpg", first.art)
        assertEquals(
            listOf(QueueSubtitle("https://media.example/show/01.de.ass", "de", "Signs"), QueueSubtitle("https://media.example/show/01.en.srt", "en", null)),
            first.subtitles,
        )
        assertNull(q.items[2].title)
        assertNull(q.items[2].mime)
        assertNull(q.reportTo)
    }

    @Test
    fun withoutStartValuesItStartsAtTheFirstItemWhereThePhoneLeftOff() {
        val q = queue(episodes).queue
        assertEquals(0, q.startIndex)
        assertNull(q.startPositionMs)
    }

    @Test
    fun startValuesMayComeAsText() {
        val q = queue(episodes, mapOf(Api.EXTRA_START_INDEX to "2", Api.EXTRA_START_POSITION to "60000")).queue
        assertEquals(2, q.startIndex)
        assertEquals(60_000L, q.startPositionMs)
        assertEquals(0L, queue(episodes, mapOf(Api.EXTRA_START_POSITION to 0)).queue.startPositionMs)
    }

    @Test
    fun theObjectFormCarriesItsOwnStartValuesButExtrasWin() {
        val json = """{"items": $episodes, "start_index": 2, "start_position_ms": 5000, "title": "Season 1"}"""
        val q = queue(json).queue
        assertEquals(2, q.startIndex)
        assertEquals(5000L, q.startPositionMs)
        assertEquals("Season 1", q.title)
        val overridden = queue(json, mapOf(Api.EXTRA_START_INDEX to 0, Api.EXTRA_START_POSITION to 1000, Api.EXTRA_TITLE to "Other")).queue
        assertEquals(0, overridden.startIndex)
        assertEquals(1000L, overridden.startPositionMs)
        assertEquals("Other", overridden.title)
    }

    @Test
    fun bareAddressesAreItemsToo() {
        val q = queue("""["https://a.example/1.mp4", "http://b.example/2.mp3"]""").queue
        assertEquals(listOf(QueueItem("https://a.example/1.mp4"), QueueItem("http://b.example/2.mp3")), q.items)
    }

    @Test
    fun theRequestIdAndReportToAreKept() {
        val q = queue(episodes, mapOf(Api.EXTRA_REQUEST_ID to "r-7", Api.EXTRA_REPORT_TO to "com.example.library"), caller = CallerEvidence(callingActivity = "com.example.library")).queue
        assertEquals("r-7", q.requestId)
        assertEquals("com.example.library", q.reportTo)
    }

    // ---- JSON compatibility ------------------------------------------------------------------------

    @Test
    fun jsonWrittenByAndroidsOrgJsonIsRead() {
        // Android's JSONArray writes "https:\/\/…"; numbers as ids; unknown fields from newer callers.
        val array = JSONArray()
            .put(JSONObject().put("url", "https://a.example/x/1.mp4").put("id", 42).put("title", "One").put("future_field", JSONObject().put("x", 1)))
            .put(JSONObject().put("url", "https://a.example/x/2.mp4").put("id", "42b").put("subtitles", JSONArray().put("https://a.example/x/2.srt")))
        val text = array.toString().replace("/", "\\/")
        val q = queue(text).queue
        assertEquals(listOf("https://a.example/x/1.mp4", "https://a.example/x/2.mp4"), q.items.map { it.url })
        assertEquals(listOf("42", "42b"), q.items.map { it.id })
        assertEquals(listOf(QueueSubtitle("https://a.example/x/2.srt")), q.items[1].subtitles)
    }

    @Test
    fun nullsAndEmptyTextsCountAsLeftOut() {
        val q = queue("""[{"url": "https://a.example/1.mp4", "id": null, "title": "", "mime": null, "subtitles": null, "art": null}]""").queue
        assertEquals(QueueItem("https://a.example/1.mp4"), q.items.single())
    }

    @Test
    fun whitespaceAndAByteOrderMarkAroundTheJsonAreFine() {
        assertEquals(1, queue("\uFEFF  [\"https://a.example/1.mp4\"]  \n").queue.items.size)
    }

    @Test
    fun aWholeNumberTitleOrIdLosesNoDigitsAndGetsNoDecimals() {
        val q = queue("""[{"url": "https://a.example/1.mp4", "id": 12345678901, "title": 1984}]""").queue
        assertEquals("12345678901", q.items[0].id)
        assertEquals("1984", q.items[0].title)
    }

    // ---- bad requests ------------------------------------------------------------------------------

    @Test
    fun theQueueExtraIsRequiredAndMustBeJsonText() {
        assertEquals(AutomationResult.CODE_INVALID, refused(null).code)
        assertTrue(refused(null).message.contains(Api.EXTRA_QUEUE))
        assertEquals(AutomationResult.CODE_INVALID, refused("not json").code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""{"url": "https://a.example/1.mp4"}""").code) // an object without "items"
        assertEquals(AutomationResult.CODE_INVALID, refused("""["https://a.example/1.mp4"] trailing""").code)
        assertEquals(AutomationResult.CODE_INVALID, refused("[]").code)
        val p = parse(mapOf(Api.EXTRA_QUEUE to listOf("https://a.example/1.mp4")))
        assertEquals(AutomationResult.CODE_INVALID, (p as ParsedRequest.Rejected).result.code)
    }

    @Test
    fun onlyWebAndGrantedContentAddressesAreAllowed() {
        for (bad in listOf("ftp://a.example/1.mp4", "file:///sdcard/Movies/1.mp4", "javascript:alert(1)", "smb://nas/share/1.mkv", "/sdcard/1.mp4", "https://", "https:// a.example/1.mp4", "intent://x#Intent;end")) {
            val r = refused("""[{"url": ${JSONObject.quote(bad)}}]""")
            assertEquals(bad, AutomationResult.CODE_INVALID, r.code)
            assertTrue(r.message, r.message.startsWith("Item 0"))
        }
        assertTrue(refused("""["file:///sdcard/1.mp4"]""").message.contains("content://"))
    }

    @Test
    fun subtitlesAndArtAreCheckedToo() {
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": "https://a.example/1.mp4", "subtitles": ["file:///x.srt"]}]""").code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": "https://a.example/1.mp4", "subtitles": [{"language": "en"}]}]""").code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": "https://a.example/1.mp4", "subtitles": [{"url": "https://a.example/1.srt", "language": "English!"}]}]""").code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": "https://a.example/1.mp4", "subtitles": {"url": "https://a.example/1.srt"}}]""").code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": "https://a.example/1.mp4", "art": "content://images/1"}]""", granted = setOf("content://images/1")).code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": "https://a.example/1.mp4", "art": "https://a.example/${"p".repeat(QueueParser.MAX_ART)}"}]""").code)
        val many = JSONArray().also { a -> repeat(QueueParser.MAX_SUBTITLES + 1) { a.put("https://a.example/s$it.srt") } }
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": "https://a.example/1.mp4", "subtitles": $many}]""").code)
    }

    @Test
    fun soundAndSubtitleLanguagesAreRead() {
        val q = queue(
            """[{"url": "https://a.example/1.mkv", "audio_language": "ja", "subtitle_language": "de"},
                {"url": "https://a.example/2.mkv", "audio_language": " pt-BR ", "subtitle_language": "OFF"},
                {"url": "https://a.example/3.mkv", "audio_language": null, "subtitle_language": ""},
                "https://a.example/4.mkv"]""",
        ).queue
        assertEquals(listOf("ja", "pt-BR", null, null), q.items.map { it.audioLanguage })
        assertEquals(listOf("de", QueueParser.OFF, null, null), q.items.map { it.subtitleLanguage })
    }

    @Test
    fun soundAndSubtitleLanguagesAreChecked() {
        for (bad in listOf(""""English"""", """"e"""", """"de-"""", "42", """["de"]""", """{"lang": "de"}""", "true")) {
            val r = refused("""[{"url": "https://a.example/1.mkv", "audio_language": $bad}]""")
            assertEquals(bad, AutomationResult.CODE_INVALID, r.code)
            assertTrue(r.message, r.message.startsWith("Item 0: \"audio_language\""))
            val s = refused("""[{"url": "https://a.example/1.mkv", "subtitle_language": $bad}]""")
            assertEquals(bad, AutomationResult.CODE_INVALID, s.code)
            assertTrue(s.message, s.message.startsWith("Item 0: \"subtitle_language\""))
        }
        // "off" is for subtitles only.
        assertTrue(refused("""[{"url": "https://a.example/1.mkv", "audio_language": "off"}]""").message.contains("only for subtitles"))
        // The item's place is named.
        assertTrue(refused("""["https://a.example/1.mkv", {"url": "https://a.example/2.mkv", "subtitle_language": "none"}]""").message.startsWith("Item 1"))
    }

    @Test
    fun missingOrMistypedFieldsAreRefused() {
        assertTrue(refused("""[{"title": "No address"}]""").message.contains("no \"url\""))
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": 42}]""").code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": "https://a.example/1.mp4", "id": {"a": 1}}]""").code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": "https://a.example/1.mp4", "mime": "video"}]""").code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": "https://a.example/1.mp4", "title": ["a"]}]""").code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""[42]""").code)
        assertTrue(refused("""[{"url": "https://a.example/1.mp4", "id": "a\u0000b"}]""").message.contains("control characters"))
    }

    @Test
    fun theLimitsAreEnforced() {
        val max = JSONArray().also { a -> repeat(QueueParser.MAX_ITEMS) { a.put("https://a.example/$it.mp4") } }
        assertEquals(QueueParser.MAX_ITEMS, queue(max.toString()).queue.items.size)
        val tooMany = JSONArray(max.toString()).put("https://a.example/last.mp4")
        assertTrue(refused(tooMany.toString()).message.contains("at most ${QueueParser.MAX_ITEMS}"))

        val longTitle = "x".repeat(QueueParser.MAX_TITLE + 1)
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": "https://a.example/1.mp4", "title": "$longTitle"}]""").code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""[{"url": "https://a.example/1.mp4", "id": "${"i".repeat(QueueParser.MAX_ID + 1)}"}]""").code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""["https://a.example/${"p".repeat(AutomationParser.MAX_URL)}"]""").code)
        assertEquals(AutomationResult.CODE_INVALID, refused(" ".repeat(QueueParser.MAX_JSON) + "[\"https://a.example/1.mp4\"]").code)
        assertEquals(AutomationResult.CODE_INVALID, refused(episodes, mapOf(Api.EXTRA_TITLE to longTitle)).code)
    }

    @Test
    fun eachAddressOnlyOnce() {
        val r = refused("""["https://a.example/1.mp4", "https://a.example/2.mp4", "https://a.example/1.mp4"]""")
        assertTrue(r.message, r.message.contains("Item 2") && r.message.contains("item 0"))
    }

    @Test
    fun startValuesOutsideTheQueueAreRefused() {
        assertEquals(AutomationResult.CODE_INVALID, refused(episodes, mapOf(Api.EXTRA_START_INDEX to 3)).code)
        assertEquals(AutomationResult.CODE_INVALID, refused(episodes, mapOf(Api.EXTRA_START_INDEX to -1)).code)
        assertEquals(AutomationResult.CODE_INVALID, refused(episodes, mapOf(Api.EXTRA_START_INDEX to "first")).code)
        assertEquals(AutomationResult.CODE_INVALID, refused(episodes, mapOf(Api.EXTRA_START_POSITION to -5)).code)
        assertEquals(AutomationResult.CODE_INVALID, refused(episodes, mapOf(Api.EXTRA_START_POSITION to QueueParser.MAX_START_POSITION_MS + 1)).code)
        assertEquals(AutomationResult.CODE_INVALID, refused("""{"items": $episodes, "start_index": 1.5}""").code)
    }

    // ---- content:// and ClipData grants ---------------------------------------------------------------

    @Test
    fun contentAddressesMustBeGrantedThroughTheIntent() {
        val video = "content://com.example.library.files/media/42"
        val subs = "content://com.example.library.files/subs/42.srt"
        val json = """[{"url": "$video", "subtitles": [{"url": "$subs", "language": "en"}]}, "https://a.example/2.mp4"]"""
        val q = queue(json, granted = setOf(video, subs)).queue
        assertTrue(q.items[0].isContent)
        assertEquals(subs, q.items[0].subtitles.single().url)
        // Not in the ClipData (or without the grant flag, which IntentExtras turns into an empty set): refused, naming the item.
        val noGrant = refused(json)
        assertTrue(noGrant.message, noGrant.message.startsWith("Item 0") && noGrant.message.contains("ClipData"))
        val subtitleNotGranted = refused(json, granted = setOf(video))
        assertTrue(subtitleNotGranted.message, subtitleNotGranted.message.contains("subtitle 0"))
        // A grant for another URI of the same provider is not enough.
        assertEquals(AutomationResult.CODE_INVALID, refused("""["$video"]""", granted = setOf("content://com.example.library.files/media/43")).code)
    }

    // ---- the switch and the secret (direct casting) -------------------------------------------------------

    @Test
    fun castingDirectlyNeedsTheSwitchAndTheSecret() {
        assertEquals(AutomationResult.CODE_DISABLED, refused(episodes, config = off).code)
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, refused(episodes, config = locked).code)
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, refused(episodes, mapOf(Api.EXTRA_SECRET to "wrong"), config = locked).code)
        assertEquals(3, queue(episodes, mapOf(Api.EXTRA_SECRET to "s3cret"), config = locked).queue.items.size)
        // a closed door says nothing about the request
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, refused("broken", config = locked).code)
    }

    // ---- "Play on TV": the user confirms ---------------------------------------------------------------

    private fun confirm(extras: Map<String, Any?>, config: AutomationConfig = off, caller: CallerEvidence = CallerEvidence.NONE, granted: Set<String> = emptySet(), action: String? = Api.ACTION_CAST_QUEUE) =
        AutomationParser.parseForConfirmation(action, MapExtras(extras, grantedUris = granted), config, caller)

    @Test
    fun theSheetPathNeedsNeitherTheSwitchNorTheSecret() {
        for (config in listOf(off, AutomationConfig(enabled = false, secret = "s3cret"), locked, open)) {
            val p = confirm(mapOf(Api.EXTRA_QUEUE to episodes), config)
            assertTrue("$config: $p", p is ParsedRequest.Accepted)
            assertEquals(3, ((p as ParsedRequest.Accepted).command as AutomationCommand.CastQueue).queue.items.size)
        }
    }

    @Test
    fun theSheetPathStillChecksTheQueue() {
        assertEquals(AutomationResult.CODE_INVALID, (confirm(mapOf(Api.EXTRA_QUEUE to """["file:///x.mp4"]""")) as ParsedRequest.Rejected).result.code)
        assertEquals(AutomationResult.CODE_INVALID, (confirm(mapOf(Api.EXTRA_QUEUE to """["content://p/1"]""")) as ParsedRequest.Rejected).result.code)
        assertTrue(confirm(mapOf(Api.EXTRA_QUEUE to """["content://p/1"]"""), granted = setOf("content://p/1")) is ParsedRequest.Accepted)
    }

    @Test
    fun theSheetPathTakesOnlyQueues() {
        for (action in Api.ALL_ACTIONS - Api.ACTION_CAST_QUEUE + listOf(null, "nonsense")) {
            val p = confirm(mapOf(Api.EXTRA_QUEUE to episodes, Api.EXTRA_URL to "https://a.example/1.mp4"), action = action)
            assertEquals("$action", AutomationResult.CODE_INVALID, (p as ParsedRequest.Rejected).result.code)
        }
    }

    // ---- report_to --------------------------------------------------------------------------------

    private val app = "com.example.library"
    private fun reportTo(pkg: String = app) = mapOf(Api.EXTRA_QUEUE to episodes, Api.EXTRA_REPORT_TO to pkg)

    @Test
    fun reportToIsAcceptedWhenAndroidVouchesForTheCaller() {
        assertEquals(app, queue(episodes, reportTo(), caller = CallerEvidence(callingActivity = app, sdk = 26)).queue.reportTo)
        assertEquals(app, queue(episodes, reportTo(), caller = CallerEvidence(sharedIdentity = app, sdk = 34)).queue.reportTo)
        assertEquals(app, queue(episodes, reportTo(), caller = CallerEvidence(referrer = app, sdk = 30)).queue.reportTo)
    }

    @Test
    fun theRecordedReferrerOnlyCountsFromAndroid11() {
        // Before Android 11, Android did not check the package an app claimed when starting an activity.
        val r = refused(episodes, reportTo(), caller = CallerEvidence(referrer = app, sdk = 29))
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, r.code)
        assertTrue(r.message.contains("startActivityForResult"))
    }

    @Test
    fun reportToAnotherAppIsRefused() {
        val caller = CallerEvidence(callingActivity = "com.example.other", referrer = "com.example.other", sdk = 34)
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, refused(episodes, reportTo(), caller = caller).code)
        // ...and so on the sheet path.
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, (confirm(reportTo(), caller = caller) as ParsedRequest.Rejected).result.code)
    }

    @Test
    fun aBroadcastNeedsTheSecretForReportTo() {
        // A broadcast tells nothing about its sender (unless it shares its identity on Android 14+).
        val broadcast = CallerEvidence(sdk = 33)
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, refused(episodes, reportTo(), caller = broadcast).code)
        assertEquals(app, queue(episodes, reportTo() + (Api.EXTRA_SECRET to "s3cret"), config = locked, caller = broadcast).queue.reportTo)
        assertEquals(app, queue(episodes, reportTo(), caller = CallerEvidence(sharedIdentity = app, sdk = 34)).queue.reportTo)
    }

    @Test
    fun onTheSheetPathTheSecretCountsOnlyWhenOneIsSetAndRight() {
        val nobody = CallerEvidence(sdk = 29)
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, (confirm(reportTo(), off, nobody) as ParsedRequest.Rejected).result.code)
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, (confirm(reportTo() + (Api.EXTRA_SECRET to "anything"), off, nobody) as ParsedRequest.Rejected).result.code)
        val withSecret = AutomationConfig(enabled = false, secret = "s3cret")
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, (confirm(reportTo() + (Api.EXTRA_SECRET to "wrong"), withSecret, nobody) as ParsedRequest.Rejected).result.code)
        assertTrue(confirm(reportTo() + (Api.EXTRA_SECRET to "s3cret"), withSecret, nobody) is ParsedRequest.Accepted)
        assertTrue(confirm(reportTo(), off, CallerEvidence(callingActivity = app)) is ParsedRequest.Accepted)
    }

    @Test
    fun reportToMustBeAPackageName() {
        for (bad in listOf("library", "com.example.", "1com.example", "com.example.library/.Receiver", "com example")) {
            assertEquals(bad, AutomationResult.CODE_INVALID, refused(episodes, reportTo(bad), caller = CallerEvidence(callingActivity = bad)).code)
        }
        assertTrue(ReportRules.isPackageName("io.github.patschgoo.castapp.debug"))
    }

    @Test
    fun withoutReportToNothingIsChecked() {
        assertNull(queue(episodes, caller = CallerEvidence.NONE).queue.reportTo)
        assertFalse(CallerEvidence(referrer = app, sdk = 29).vouchedPackages.contains(app))
    }

    @Test
    fun theSecretNeverAppearsInAnAnswer() {
        val r = refused("""["file:///x"]""", mapOf(Api.EXTRA_SECRET to "s3cret"), config = locked)
        assertFalse(r.message.contains("s3cret"))
    }
}
