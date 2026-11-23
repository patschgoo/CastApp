# Automating CastApp

Tasker, MacroDroid, Home Assistant, shell scripts and other apps can start and control casts with
plain Android intents.

Everything here is **off by default**. Turn it on in CastApp under **Settings > Automation > Let other
apps control CastApp**. Without that switch every call is answered with `disabled` and does nothing.
The one exception is [Play on TV](#play-on-tv-the-user-picks-the-tv): an app can always open CastApp's
"Cast to TV" sheet for a play list, and nothing is cast until you tap a TV.

## Security

- Set a **secret** (Settings > Automation > Secret) and pass it as the extra `secret` with every call.
  Without a secret, **every app on the phone** can start casts, change the volume and stop what you
  watch while the switch is on.
- Calls with a missing or wrong secret are refused (`unauthorized`) before anything else is looked at.
- The secret is stored in CastApp's private settings. Treat it like a password; anything that can read
  your Tasker or Home Assistant configuration can read it.
- CastApp never plays or shows anything from a caller without your TV being involved: casts go to a TV
  you already use or know (see [Choosing the TV](#choosing-the-tv)).
- Screen mirroring still needs **you**: Android always shows its own "Start casting?" prompt, and no
  app can answer it for you.
- Play-state reports (`report_to`) only go to the app that asked for them, and only when CastApp can
  confirm that it is that app (see [Hearing how it plays](#hearing-how-it-plays)).

## Ways in

| | Component | Use it for |
|---|---|---|
| Activity | `io.github.patschgoo.castapp/io.github.patschgoo.castapp.automation.AutomationActivity` | every action, including those that open a screen (mirror prompt, remote). Invisible. |
| Broadcast | `io.github.patschgoo.castapp/io.github.patschgoo.castapp.automation.AutomationReceiver` | controls and casts without opening anything |
| "Play on TV" sheet | `io.github.patschgoo.castapp/io.github.patschgoo.castapp.automation.PlayOnTvActivity` | `CAST_QUEUE` only: shows the "Cast to TV" sheet and casts when the user taps a TV. Works with the switch off. |

The action names are the same for all: `io.github.patschgoo.castapp.automation.<NAME>`. An Intent with
the action `CAST_QUEUE` and only the package (no class name) opens the "Play on TV" sheet;
`AutomationActivity` takes `CAST_QUEUE` only when you name its class.

Android restricts what an app may do in the background. Because of that:

- Prefer the **activity** from Tasker/MacroDroid/Home Assistant. Starting an activity from the
  background needs the "Display over other apps" permission for that app on Android 10 and newer.
- Over a **broadcast**, controls for a running cast (stop, volume, next ...) always work. Starting a new
  cast (`CAST_URL`, `CAST_FILE`, `CAST_QUEUE`, `RECEIVE`) can be refused by Android 12+ unless CastApp is exempt from
  battery optimization; the answer is then `needs_activity`. `MIRROR` by broadcast shows a
  notification on the phone ("Mirror your screen to ...?") that you tap to open Android's prompt.
  `OPEN_REMOTE` needs the activity.
- When you use the package name of a debug build, it is `io.github.patschgoo.castapp.debug`; the class
  names stay the same.

## Actions

All extras are optional unless marked **required**. Numbers may be sent as numbers or as text
(`"40"`, `"40%"`), booleans as `true`/`false`, `1`/`0`, `on`/`off`, `yes`/`no`.

| Action | Extras | What it does |
|---|---|---|
| `MIRROR` | `device`, `mode` | Mirror the screen. `mode`: `screen` (default), `app` (one app, Android 14+), `audio` (sound only). Android shows its capture prompt. |
| `CAST_URL` | **`url`** (or the intent data), `title`, `mime`, `device` | Cast a web video, stream or music (`http://` / `https://`). A YouTube link opens in the TV's YouTube app. |
| `CAST_FILE` | **`uri`** (or the intent data), `title`, `mime`, `device` | Cast a file on the phone, given as a `content://` URI that CastApp may read (see below). |
| `STOP` | | Stop casting. |
| `PLAY_PAUSE` | | Toggle play/pause (videos, music; not for mirroring). |
| `PLAY`, `PAUSE` | | Play or pause only if it is not already so. |
| `NEXT`, `PREVIOUS` | | Next / previous item of the play list. |
| `VOLUME_SET` | **`level`** 0 to 100 | Set the TV volume. |
| `VOLUME_UP`, `VOLUME_DOWN` | `amount` 1 to 100 | Change the volume by `amount` percentage points; one remote step (5) if left out. |
| `MUTE` | | Mute or unmute the TV. |
| `QUALITY` | **`quality`** | Picture quality, one the running cast offers: `480p`, `720p`, `1080p` for mirroring and converted files, `Original`, or the levels of an HLS stream (`Auto`, ...). |
| `RECEIVE` | `enabled` | Turn "Receive on this phone" on or off; switches if `enabled` is left out. |
| `OPEN_REMOTE` | `device` | Open the TV remote (activity only). |
| `CAST_QUEUE` | **`queue`**, `start_index`, `start_position_ms`, `title`, `device`, `report_to` | Cast a play list (videos, music, streams, with subtitles) in one go. See [Casting a play list](#casting-a-play-list). |

Extras on every call: `secret`, `request_id` (echoed back in the answer), `result_package` (see
[Answers](#answers)).

### Choosing the TV

`device` is a TV's **name or id**. `device_id` and `device_name` ask for one of those only. Names
match ignoring case, and a part of a name is enough if only one TV contains it ("living" finds
"Living room TV"); if two match, the call fails with `device_not_found` and lists them. If you leave the
device out, CastApp uses the TV you used last.

CastApp finds TVs from its list of remembered TVs and from what it has discovered; use each TV once
in the app (or open the TV list) so that it is remembered.

### Casting a file

`CAST_FILE` needs a `content://` URI and permission to read it. `file://` paths do not work: Android
does not let CastApp read other apps' files. From a shell you can grant access:

```sh
adb shell am start -n io.github.patschgoo.castapp/io.github.patschgoo.castapp.automation.AutomationActivity \
  -a io.github.patschgoo.castapp.automation.CAST_FILE \
  -d content://media/external/video/media/42 --grant-read-uri-permission \
  --es device "Living room TV" --es secret YOURSECRET
```

Apps that share files (a file manager, Tasker's "Share" ...) can send the URI with
`FLAG_GRANT_READ_URI_PERMISSION`. For files you cannot grant access to, use Android's own share
sheet ("Cast to TV") instead.

## Casting a play list

`CAST_QUEUE` casts many videos, songs or streams as one play list. CastApp plays them in order with
its usual controls (next, previous, the play list in the app, the notification). It is made for apps
that keep a media library of their own, and works from Tasker and adb too.

| Extra | Meaning |
|---|---|
| **`queue`** | The play list: JSON text, see below. At most 500 items and 512 KB of text. |
| `start_index` | The item to start with; 0, the first, if left out. |
| `start_position_ms` | Where to start that item, in milliseconds. Left out: where this phone stopped it last time, as for every cast. `0`: from the beginning. |
| `title` | What the "Cast to TV" sheet shows; the first item's title (and how many follow) if left out. |
| `device`, `device_id`, `device_name` | The TV, as for the other actions; the TV used last if left out. The sheet lists it first. |
| `report_to` | Your app's package name: it then gets [playback broadcasts](#hearing-how-it-plays). |
| `request_id` | Echoed in the answer and in every playback broadcast. |

The JSON is an array with one object per item:

| Field | Meaning |
|---|---|
| **`url`** | `http://`, `https://` or `content://` (see [Files of your app](#files-of-your-app-content)). Each address only once in a queue. |
| `id` | Your own id for the item: text up to 200 characters, or a number. CastApp does not look at it; it comes back in the playback broadcasts. |
| `title` | Shown on the phone and on TVs that show one. The file name if left out. |
| `mime` | `video/mp4`, `video/x-matroska`, `audio/mpeg`, `application/x-mpegurl` ... Guessed from the address (or asked from the app that owns a `content://` file) if left out. |
| `subtitles` | Subtitle files (SRT, WebVTT, ASS/SSA), up to 20: an array of objects `{"url": ..., "language": "en", "label": "Signs"}` or of plain addresses. The first one is shown; the others can be picked in CastApp. `language` is a language code (`en`, `de`, `pt-BR`), `label` (up to 100 characters) is added to the name in the subtitle picker ("English (Signs)"). Addresses as for `url`. |
| `art` | Poster or cover, `http://` or `https://` (up to 4096 characters): shown in "Continue watching" on the phone. |
| `audio_language` | The sound track to play in a file with several (a dubbed anime: `ja` or `de`), a language code. CastApp TV plays a track in that language (it picks the best one of them); other TVs play the file's default track, and the sound track can still be picked in CastApp (which converts the video on the phone). |
| `subtitle_language` | The subtitles to show first: a language code, or `off`. With `subtitles`, the first one in that language (by its `language`, else by its file name, `Film.de.srt`) is shown first; none in it: the first one as before. Without `subtitles`, CastApp TV shows a track in that language from inside the file. `off`: none at first (the subtitle files can still be picked in CastApp; CastApp TV also hides the file's own). |

A plain address instead of an object is an item with only a `url`. Instead of the array, `queue` can
also be an object `{"items": [...], "start_index": 2, "start_position_ms": 60000, "title": "Season 1"}`;
the extras win over these fields. Fields CastApp does not know are ignored, so newer apps may send
more. Everything else that is wrong refuses the whole request with `invalid_request`, and the message
says which item: another kind of address (`file://`, `smb://` ...), a field of the wrong type, a text
that is too long, a language that isn't a code (`English`; `off` for `audio_language`), more than 500
items, an address twice, a `start_index` outside the queue.

```json
[
  {
    "id": "s01e01",
    "url": "https://nas.local:8920/videos/s01e01.mkv",
    "title": "Episode 1",
    "subtitles": [
      { "url": "https://nas.local:8920/subs/s01e01.en.ass", "language": "en" },
      { "url": "https://nas.local:8920/subs/s01e01.de.srt", "language": "de", "label": "Forced" }
    ],
    "art": "https://nas.local:8920/art/season1.jpg",
    "audio_language": "ja",
    "subtitle_language": "de"
  },
  { "id": "s01e02", "url": "https://nas.local:8920/videos/s01e02.mkv", "title": "Episode 2" },
  "https://nas.local:8920/videos/s01e03.mkv"
]
```

From a computer (note the two levels of quotes: one for your shell, one for the phone's):

```sh
adb shell "am start -n $ACT -a $P.CAST_QUEUE --es secret YOURSECRET --es device 'Living room TV' \
  --ei start_index 1 --el start_position_ms 754000 \
  --es queue '[\"https://example.com/ep1.mp4\",\"https://example.com/ep2.mp4\"]'"
```

The answer is `ok` when the play list was handed to the casting service; whether the TV plays an
item is told by the notification, and to your app by the [playback broadcasts](#hearing-how-it-plays).
YouTube links are not opened in the TV's YouTube app here; use `CAST_URL` for them.

### Files of your app (content://)

Put every `content://` address of the queue (videos and subtitle files) into the Intent's
**ClipData** and add `FLAG_GRANT_READ_URI_PERMISSION`. CastApp refuses a `content://` address that is
not granted that way (the Intent's data counts too): it reads only what your app may read and hands
over. The grant then lasts as long as CastApp casts the play list.

## Play on TV: the user picks the TV

An app that only wants a "Play on TV" button does not need the automation switch. Send `CAST_QUEUE`
to `io.github.patschgoo.castapp/io.github.patschgoo.castapp.automation.PlayOnTvActivity`, or just set
the package `io.github.patschgoo.castapp`: CastApp shows its usual "Cast to TV" sheet with the
queue's title, and nothing is cast until the user taps a TV. The queue is checked as above; neither the
switch nor the secret is needed (the secret only counts for `report_to`).

The activity result (and the result broadcast) is `ok` when the user picked a TV, `cancelled` when
the sheet was closed, and `invalid_request` or `unauthorized` when the request was refused (the user
then sees a short note).

To cast without asking, send the same Intent to `AutomationActivity` (by class name) or to
`AutomationReceiver`: then the switch and the secret apply as for every other action, and Android
12+ may answer `needs_activity` from the background.

## Hearing how it plays

Send `report_to` with your app's package name, and CastApp tells your app what happens to the items of
the queue: broadcasts with the action `io.github.patschgoo.castapp.automation.PLAYBACK`, sent to that
package only (`Intent.setPackage`, never to anyone else), from the moment the TV starts the first item
until the play list ends or something else is cast instead. Without `report_to` nothing is sent.

CastApp must be sure that `report_to` is the app that asks; otherwise any app could send play states
into another one. It accepts `report_to` when

- you start the activity with `startActivityForResult` (`registerForActivityResult`): every Android version;
- on Android 11 and newer, also with a plain `startActivity` (Android records who started it);
- on Android 14 and newer, when you share your identity (`ActivityOptions.setShareIdentityEnabled(true)`,
  for a broadcast `BroadcastOptions.setShareIdentityEnabled(true)`);
- or when you send the right `secret` (a secret must be set in CastApp). A broadcast does not say who
  sent it, so before Android 14 it needs the secret.

Otherwise the request is refused with `unauthorized`, and nothing is cast.

| Extra | Meaning |
|---|---|
| `state` | What happened, see below. |
| `item_id` | The item's `id`; left out if it had none. |
| `item_index` | The item's place in the queue you sent (0 = the first), also after the user moved it. |
| `position_ms` | Where playback is, a Long. |
| `duration_ms` | How long the item is, a Long; 0 while the TV has not said. |
| `device` | The TV's name. |
| `request_id` | Your `request_id`, if you sent one. |
| `reason` | `failed` only: `cannot_play` (the TV could not play it in any way; CastApp goes on with the next item), `connection_lost` (the TV went away and did not come back) or `cast_failed` (CastApp could not connect to the TV). |
| `message` | `failed` only: a sentence for humans, in English. |

| `state` | When |
|---|---|
| `started` | The TV began playing the item (once; again only if it is played again). |
| `paused`, `resumed` | Paused, or playing again. CastApp asks the TV every 5 seconds, so it can be that late. |
| `progress` | About every 30 seconds while it plays. |
| `stopped` | It ended before the end: stopped, skipped, or something else was cast. |
| `finished` | It played to the end, or was stopped with at most 10 % or less than 30 seconds left: what Plex and Jellyfin count as played, and what CastApp's "Continue watching" counts as watched. |
| `failed` | It could not be played, or the cast broke off; see `reason`. |

Each item that started ends with exactly one of `stopped`, `finished` or `failed`; an item that never
started can still end with `failed`. Items the user adds to the play list in CastApp, and photos, are
not reported.

Anyone can send your app a broadcast with this action. To ignore forged ones, compare `request_id`
(make it unguessable) and, on Android 14 and newer, `getSentFromPackage()`: CastApp shares its identity
with these broadcasts.

### For app developers: a minimal example

In your app's manifest: see CastApp (Android 11+ hides other apps otherwise), and receive the reports
even when your app is not running:

```xml
<queries>
    <package android:name="io.github.patschgoo.castapp" />
</queries>

<application>
    <receiver android:name=".TvPlaybackReceiver" android:exported="true">
        <intent-filter>
            <action android:name="io.github.patschgoo.castapp.automation.PLAYBACK" />
        </intent-filter>
    </receiver>
</application>
```

Sending the queue (to the "Play on TV" sheet, with reports):

```kotlin
class EpisodeActivity : ComponentActivity() {
    private val playOnTv = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // "ok": a TV was picked; "cancelled": the sheet was closed; else see "message".
        val status = result.data?.getStringExtra("status")
    }

    fun castFrom(episodes: List<Episode>, start: Int, resumeAtMs: Long?) {
        val queue = JSONArray()
        val files = ArrayList<Uri>()
        for (e in episodes) {
            val item = JSONObject().put("id", e.id).put("url", e.uri.toString()).put("title", e.title)
            e.posterUrl?.let { item.put("art", it) }
            if (e.subtitles.isNotEmpty()) {
                item.put("subtitles", JSONArray(e.subtitles.map { JSONObject().put("url", it.uri.toString()).put("language", it.language) }))
            }
            queue.put(item)
            files += (listOf(e.uri) + e.subtitles.map { it.uri }).filter { it.scheme == "content" }
        }
        val requestId = UUID.randomUUID().toString().also { Reports.expect(it) }
        val intent = Intent("io.github.patschgoo.castapp.automation.CAST_QUEUE")
            .setPackage("io.github.patschgoo.castapp") // the "Play on TV" sheet
            .putExtra("queue", queue.toString())
            .putExtra("start_index", start)
            .putExtra("title", episodes[start].showTitle)
            .putExtra("report_to", packageName)
            .putExtra("request_id", requestId)
        if (resumeAtMs != null) intent.putExtra("start_position_ms", resumeAtMs)
        if (files.isNotEmpty()) {
            intent.clipData = ClipData.newRawUri("media", files[0]).apply { files.drop(1).forEach { addItem(ClipData.Item(it)) } }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            playOnTv.launch(intent) // startActivityForResult: CastApp can confirm report_to on every Android version
        } catch (e: ActivityNotFoundException) {
            // CastApp is not installed
        }
    }
}
```

Receiving the reports in the manifest receiver:

```kotlin
class TvPlaybackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sender = if (Build.VERSION.SDK_INT >= 34) sentFromPackage else null
        if (sender != null && sender !in setOf("io.github.patschgoo.castapp", "io.github.patschgoo.castapp.debug")) return
        if (!Reports.isOurs(intent.getStringExtra("request_id"))) return
        val id = intent.getStringExtra("item_id") ?: return
        val position = intent.getLongExtra("position_ms", 0)
        val duration = intent.getLongExtra("duration_ms", 0)
        when (intent.getStringExtra("state")) {
            "progress", "paused", "stopped" -> Library.saveResumePoint(context, id, position, duration)
            "finished" -> Library.markWatched(context, id)
            "failed" -> Library.showProblem(context, id, intent.getStringExtra("message"))
        }
    }
}
```

Or, only while your app runs, a receiver registered in code (Android 14+ needs `RECEIVER_EXPORTED`,
because the broadcasts come from another app):

```kotlin
val receiver = TvPlaybackReceiver()
ContextCompat.registerReceiver(context, receiver, IntentFilter("io.github.patschgoo.castapp.automation.PLAYBACK"), ContextCompat.RECEIVER_EXPORTED)
// ... and when done: context.unregisterReceiver(receiver)
```

`Reports` and `Library` stand for your app's own code. CastApp lists the `PLAYBACK` action in its
`<queries>`, so Android 11+ lets it reach a manifest receiver with that intent filter.

### Example: a media library app

A media library app (one that keeps series and films on a home server or on the phone, tracks what was
watched and fetches subtitles) wants a "Play on TV" button on an episode, with the following episodes
queued and the watched marks kept in the library:

1. It builds the queue from that episode to the end of the season: `id` = its own episode id, `url` =
   the server's stream address or a `content://` URI of its own FileProvider (in the ClipData, with the
   grant flag), `subtitles` in the user's languages, the season's poster as `art`.
2. It sends `start_position_ms` = where its own resume point is, or `0` for "from the beginning", and
   `start_index` = 0.
3. It starts the Intent with `startActivityForResult`, `report_to` = its package and an unguessable
   `request_id`; the user picks the TV in CastApp's sheet.
4. Its receiver saves `position_ms` on `progress`, `paused` and `stopped`, marks the episode watched on
   `finished`, and shows a note on `failed`. When the user later casts something else, the reports stop.

## CastApp TV for other sender apps

CastApp TV (CastApp on a Fire TV, Android TV or Google TV box) is a normal DLNA/UPnP media renderer:
any DLNA sender finds it with an SSDP search (`urn:schemas-upnp-org:device:MediaRenderer:1`; its
`modelName` is `CastApp TV`) and plays with `SetAVTransportURI` and `Play`. Its home screen shows its
address (`Address: 192.168.1.20:49595`), which senders that add a TV by hand can use. These extras are
optional; TVs without CastApp never need them.

**What it plays:** `GET http://<address>/castapp/capabilities` (devices on the local network only)
answers JSON with `true`/`false` values:

| Keys | Meaning |
|---|---|
| `version` | `3` (a number, not true/false; 3 added the file types). |
| `h264`, `h264_10bit` (also `hi10p`), `hevc_10bit`, `av1`, `vp9`, `vp8`, `mpeg2`, `mpeg4` | A hardware decoder for 1080p: plays smoothly. |
| the same with `_sw` (`av1_sw` ...) | A decoder, but no such hardware one (software, or hardware below 1080p): fine for SD, may stutter or fail in HD. |
| `hevc` | A hardware HEVC decoder for 1080p (the key older CastApp versions read). |
| `aac`, `mp3`, `ac3`, `eac3`, `dts`, `truehd`, `flac`, `opus`, `vorbis` | Plays this sound: a decoder, or passed through to the AV receiver. |
| `ac3_passthrough`, `eac3_passthrough`, `dts_passthrough`, `truehd_passthrough` | The HDMI output takes it as it is right now (follows what is plugged in). |
| `sub_srt`, `sub_vtt`, `sub_ass`, `sub_pgs`, `sub_vobsub` | Subtitle formats it shows: SRT, WebVTT and ASS/SSA as files and inside MKV, PGS and VobSub inside MKV only (see **Subtitle files** below). |
| `sub_ass_full` | `false`: ASS keeps position, colours, bold and italic, but not effects, karaoke or embedded fonts. |
| `youtube` | It opens YouTube links in the TV's YouTube app (CastApp's own use). |
| `container_mp4` (MP4, M4V, MOV), `container_mkv`, `container_webm`, `container_ts` (MPEG-TS with 188-byte packets, `.ts`), `container_avi`, `container_flv`, `container_mpeg_ps` (`.mpg`, `.mpeg`, `.vob`) | File types the player opens: `true`. The picture and sound inside still need the decoders above; AVI carries H.264, MPEG-4 ASP (XviD/DivX 4+) or MJPEG, FLV only H.264 with AAC or MP3. |
| `container_m2ts` (Blu-ray/AVCHD `.m2ts`, `.mts` with 192-byte packets), `container_ogg_video` (Ogg Theora), `container_asf` (ASF, WMV) | `false`: the player has no reader for them (remux to MKV/MP4 or convert first). |
| `hls`, `dash` | Streams it plays: HLS (`.m3u8`) and MPEG-DASH (`.mpd`). |

Keys may be added; read the ones you know and treat a missing key as unknown. The file types are the
same on every device (they come with the app); the decoders differ per device.

**When it can't play:** after a failure `GetTransportInfo` answers `CurrentTransportState` `STOPPED` and
`CurrentTransportStatus` `ERROR_OCCURRED` (otherwise `OK`) until the next `SetAVTransportURI` or `Play`.
`GET http://<address>/castapp/status` (devices on the local network; with "Only paired phones" also the
`X-CastApp-Token` header, like `GetPositionInfo`) tells why:

```json
{"version": 1, "state": "STOPPED", "uri": "http://192.168.1.5:8080/show/episode01.mkv", "title": "Episode 1",
 "position_ms": 0, "duration_ms": 0, "next_uri": null,
 "error": {"code": "no_video_decoder", "message": "No decoder for 10-bit H.264 (High 10) video on this TV",
           "format": "10-bit H.264 (High 10)"},
 "warning": null}
```

`state` is the UPnP transport state, `error` is `null` while nothing failed. `message` is an English
sentence for people; `format` (the format it is about) and `http_status` come when known. The codes:

| `code` | Meaning | What a sender can do |
|---|---|---|
| `no_video_decoder` | The picture's format has no decoder here (10-bit H.264 on a Shield, VC-1 ...). | Convert the video. |
| `no_audio_decoder` | The sound's format has no decoder (music). For a video it is only a `warning`: it plays without sound. | Convert the sound, or pick another sound track. |
| `no_picture` | Sound, but the picture in the file can't be read at all (DivX 3 in an AVI). | Convert the video. |
| `unsupported_format` | The file type can't be opened (see `container_*`), or the file is damaged. | Remux or convert. |
| `network` | The TV couldn't load the address: no connection, a time-out, an HTTP error (`http_status`). | Check the address, the server and its firewall. |
| `player_error` | Anything else the player stopped with (a decoder that broke off, the sound output, copy protection ...; the message says which, often with Media3's error name). | Try another way, or report it. |

`warning` (same fields) is set while a video plays with a problem, today only `no_audio_decoder`.

**Next item:** `SetNextAVTransportURI` (with its DIDL-Lite metadata, like `SetAVTransportURI`) queues
one item. `GetMediaInfo` shows it as `NextURI`/`NextURIMetaData`, `/castapp/status` as `next_uri`.
When the current item plays to its end, CastApp TV starts it by itself: `TRANSITIONING`, then
`PLAYING`, and `GetPositionInfo`/`GetMediaInfo` show the new address and metadata; the queue is empty
again. `Next` starts it at once. A new `SetAVTransportURI` or `Stop` drops it, an empty `NextURI` too.
It never moves on by itself after a live stream (`DLNA.ORG_OP=00`), a photo, CastApp's screen
mirroring or playback that follows another screen, and never to a photo. Senders that send the next
`SetAVTransportURI` themselves when an item ends (as CastApp on the phone does) need nothing of this.

**Hints in the DIDL-Lite metadata** of `SetAVTransportURI`, each in its own `<desc>` with
`nameSpace="urn:castapp"` inside the `<item>`:

- `castapp:subtrack:<hint>`: which subtitle track **inside the file** to show. It counts only when no
  subtitle file comes with the item (a second `<res>` or `sec:CaptionInfoEx` wins); with
  `castapp:subfiles:` its languages choose among those files instead. `<hint>` is `off`
  (none), or `&`-joined fields with URL-encoded values: `prefer=de,en` (languages, best first),
  `lang=jpn` (this language), `title=Full` (the track's name, with `lang`), `t=2` (the track's place
  among the file's subtitle tracks, counted from 0; used when its language agrees with `lang`). Only
  `prefer=` is fine too: the player then takes a track in the first language it has.
- `castapp:audiotrack:<hint>`: which sound track inside the file to play (a dual-audio file with Japanese
  and a German dub). The same fields as `castapp:subtrack:`, counted among the file's sound tracks
  (`off` means nothing here): `prefer=ja` lets the player take the best track in that language (the
  simplest); `t=1&lang=ger&title=German%20Dub` asks for exactly that track. Without it the player
  takes the file's default track, as before.
- `castapp:subfiles:<JSON>`: several subtitle files, which the TV's subtitle menu then offers. A JSON
  array of up to 20 objects `{"url": "http://…", "language": "de", "label": "Full", "mime": "text/x-ssa"}`;
  only `url` (`http://` or `https://`) is needed, the type is otherwise told by the address's
  extension. The text is XML-escaped like all DIDL text (`"` becomes `&quot;`, `&` becomes `&amp;`).
  Which one is shown: the first in the languages of `castapp:subtrack:` (its `lang=` first, then
  `prefer=`; `title=` matches a file's `label`), else the first file; `castapp:subtrack:off` shows none.
  Entries without a usable `url` are skipped. A subtitle file sent the plain DLNA way as well (below)
  is added at the end unless it is in the list.
- `castapp:substyle:size=1.35;fg=FFEB3B;bg=box`: how subtitles look. `size` is `0.75`, `1.0`, `1.35` or
  `1.75` (other numbers go to the nearest), `fg` one of `FFFFFF`, `FFEB3B`, `4DD0E1`, `8BC34A` (others
  become white), `bg` is `none`, `shadow` or `box` (half-transparent black, the default). Without it the
  TV's own caption settings apply.

```xml
<desc id="castapp-subtrack" nameSpace="urn:castapp">castapp:subtrack:lang=jpn&amp;title=Full&amp;prefer=de%2Cen</desc>
<desc id="castapp-subtitles" nameSpace="urn:castapp">castapp:substyle:size=1.35;fg=FFFFFF;bg=shadow</desc>
<desc id="castapp-audiotrack" nameSpace="urn:castapp">castapp:audiotrack:prefer=ja</desc>
<desc id="castapp-subfiles" nameSpace="urn:castapp">castapp:subfiles:[{&quot;url&quot;:&quot;http://192.168.1.10:8200/subs/ep01.de.ass&quot;,&quot;language&quot;:&quot;de&quot;,&quot;label&quot;:&quot;Full&quot;},{&quot;url&quot;:&quot;http://192.168.1.10:8200/subs/ep01.en.srt&quot;,&quot;language&quot;:&quot;en&quot;}]</desc>
```

**Subtitle files.** One file goes the usual DLNA way, as any DLNA TV takes it: a second `<res>` with a
subtitle type, `sec:CaptionInfoEx` (or `sec:CaptionInfo`) or the `pv:subtitleFileUri` attribute. As files
CastApp TV shows:

| Format | Told by the type (`<res>` protocolInfo, `sec:type`, `pv:subtitleFileType`, `mime`) | or the address ending in |
|---|---|---|
| SRT | `application/x-subrip`, `text/srt`, `text/x-srt`, `srt` | `.srt` |
| WebVTT | `text/vtt`, `text/webvtt`, `vtt` | `.vtt` |
| ASS/SSA | `text/x-ssa`, `text/x-ass`, `application/x-ssa`, `text/ssa`, `ass`, `ssa` | `.ass`, `.ssa` |

The type wins over the address; with neither, a file is read as SRT. ASS keeps position, colours, bold and
italic, but not effects, karaoke or embedded fonts, so there is no need to convert it to SRT. PGS and
VobSub are shown only inside the video file (MKV); as files (`.sup`, `.idx`/`.sub`), and SAMI (`.smi`),
they are left out.

## Answers

Every call is answered in up to three ways:

1. **Activity result**: `AutomationActivity` returns `setResult(code, data)` (use
   `startActivityForResult`).
2. **Ordered broadcast result**: `am broadcast` prints `result=<code>, data="<status>"`, and the extras
   are in the result extras.
3. **Result broadcast**: an intent with the action `io.github.patschgoo.castapp.automation.RESULT`, sent
   to the package in the `result_package` extra (for an activity started by an app, that app is used if
   you leave it out). A Tasker profile "Intent Received" or a MacroDroid "Intent Received" trigger can
   listen to it.

The answer has these values:

| Extra | Meaning |
|---|---|
| `code` | number, see below |
| `status` | name, see below |
| `message` | a sentence for humans |
| `action` | the action you sent |
| `request_id` | what you sent as `request_id`, if anything |

| `code` | `status` | Meaning |
|---|---|---|
| 1 | `ok` | Done, or handed to the casting service. |
| 2 | `pending_user` | Started, but you must confirm on the phone (screen capture). |
| 3 | `disabled` | The switch in Settings is off. |
| 4 | `unauthorized` | The secret is missing or wrong; or (`CAST_QUEUE`) CastApp cannot confirm that `report_to` is the calling app. |
| 5 | `invalid_request` | Unknown action, or a missing or invalid extra (the message says which). |
| 6 | `device_not_found` | No such TV, or no TV used yet. |
| 7 | `not_casting` | The action needs a running cast. |
| 8 | `needs_activity` | Android refuses this in the background; send it to the activity. |
| 9 | `failed` | Something else went wrong; see the message. |
| 10 | `unsupported` | Not possible for this cast (play/pause while mirroring, a quality it does not offer). |
| 11 | `cancelled` | "Play on TV" sheet only: the user closed it without picking a TV. |

An answer never contains the secret. "Ok" means CastApp accepted the request; whether the TV plays
the file is shown by the notification and in the app, as usual.

## adb examples

```sh
APP=io.github.patschgoo.castapp
ACT=$APP/$APP.automation.AutomationActivity
REC=$APP/$APP.automation.AutomationReceiver
P=$APP.automation

# mirror to a TV by name (you still confirm Android's prompt)
adb shell am start -n $ACT -a $P.MIRROR --es device "Living room TV" --es secret YOURSECRET

# mirror to the TV used last, sound only
adb shell am start -n $ACT -a $P.MIRROR --es mode audio --es secret YOURSECRET

# cast a web video
adb shell am start -n $ACT -a $P.CAST_URL --es url "https://example.com/film.mp4" \
  --es title "Film" --es device "Bedroom TV" --es secret YOURSECRET

# controls (as broadcasts; the answer is printed: result=1, data="ok")
adb shell am broadcast -n $REC -a $P.PLAY_PAUSE --es secret YOURSECRET
adb shell am broadcast -n $REC -a $P.NEXT --es secret YOURSECRET
adb shell am broadcast -n $REC -a $P.VOLUME_SET --ei level 35 --es secret YOURSECRET
adb shell am broadcast -n $REC -a $P.VOLUME_UP --ei amount 10 --es secret YOURSECRET
adb shell am broadcast -n $REC -a $P.QUALITY --es quality 720p --es secret YOURSECRET
adb shell am broadcast -n $REC -a $P.STOP --es secret YOURSECRET

# receive on this phone
adb shell am broadcast -n $REC -a $P.RECEIVE --ez enabled true --es secret YOURSECRET

# open the TV remote
adb shell am start -n $ACT -a $P.OPEN_REMOTE --es secret YOURSECRET
```

For a debug build use `APP=io.github.patschgoo.castapp.debug` for the package part only; the class
names are unchanged:
`ACT=$APP/io.github.patschgoo.castapp.automation.AutomationActivity`.

## Tasker

Action **Send Intent**:

| Field | Value |
|---|---|
| Action | `io.github.patschgoo.castapp.automation.CAST_URL` |
| Cat | Default |
| Extra | `url:https://example.com/film.mp4` |
| Extra | `device:Living room TV` |
| Extra | `secret:YOURSECRET` |
| Package | `io.github.patschgoo.castapp` |
| Class | `io.github.patschgoo.castapp.automation.AutomationActivity` |
| Target | Activity |

For a control (stop, volume ...) set **Class** to `io.github.patschgoo.castapp.automation.AutomationReceiver`
and **Target** to Broadcast. Volume: Action `...automation.VOLUME_SET`, Extra `level:40`.

To react to the answer, add a profile **Event > System > Intent Received** with Action
`io.github.patschgoo.castapp.automation.RESULT`, and send the extra `result_package:net.dinglisch.android.taskerm`
with your call. In the profile's task, `%status`, `%code` and `%message` hold the answer.

Tip: Tasker needs "Display over other apps" to start an activity from the background.

## MacroDroid

Action **Send Intent**:

- Target: Activity (or Broadcast for controls)
- Action: `io.github.patschgoo.castapp.automation.MIRROR`
- Package: `io.github.patschgoo.castapp`
- Class: `io.github.patschgoo.castapp.automation.AutomationActivity` (Broadcast:
  `...automation.AutomationReceiver`)
- Extras: `device = Living room TV`, `secret = YOURSECRET` (type: String)

MacroDroid can also trigger on the result: **Intent Received** with the action
`io.github.patschgoo.castapp.automation.RESULT` (send `result_package = com.arlosoft.macrodroid`).

## Home Assistant (Companion app notification commands)

The Home Assistant Companion app can send intents when it receives a notification message of the form
`command_activity` or `command_broadcast_intent`. On the phone, allow the Companion app to "Display over
other apps" (needed for `command_activity` on Android 10 and newer).

Mirror to a TV (the prompt appears on the phone; this is for a phone that is on or unlocked):

```yaml
service: notify.mobile_app_my_phone
data:
  message: command_activity
  data:
    intent_package_name: io.github.patschgoo.castapp
    intent_class_name: io.github.patschgoo.castapp.automation.AutomationActivity
    intent_action: io.github.patschgoo.castapp.automation.MIRROR
    intent_extras: "device:Living room TV,secret:YOURSECRET"
```

Cast a web video (the address goes in as the intent data, because the extras of a notification
command are split at colons and commas):

```yaml
service: notify.mobile_app_my_phone
data:
  message: command_activity
  data:
    intent_package_name: io.github.patschgoo.castapp
    intent_class_name: io.github.patschgoo.castapp.automation.AutomationActivity
    intent_action: io.github.patschgoo.castapp.automation.CAST_URL
    intent_uri: "https://example.com/film.mp4"
    intent_extras: "device:Living room TV,title:Film,secret:YOURSECRET"
```

Controls as a broadcast, for example in an automation that pauses the TV when the doorbell rings:

```yaml
automation:
  - alias: Pause the cast when the doorbell rings
    trigger:
      - platform: state
        entity_id: binary_sensor.doorbell
        to: "on"
    action:
      - service: notify.mobile_app_my_phone
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: io.github.patschgoo.castapp
            intent_action: io.github.patschgoo.castapp.automation.PAUSE
            intent_extras: "secret:YOURSECRET"
```

Volume to 30 %: `intent_action: io.github.patschgoo.castapp.automation.VOLUME_SET`, `intent_extras:
"level:30,secret:YOURSECRET"`. Stop: `...automation.STOP`. If the secret itself contains a comma or a
colon, choose another secret.

For `command_broadcast_intent` the Companion app sends the broadcast to the package you name; CastApp's
receiver answers it. Use the activity form for everything that has to start a cast from a phone that
is idle.

## Wear OS

The watch app (`CastApp-wear.apk`) does not use these intents: it talks to CastApp through the Wear
Data Layer. See the README.
