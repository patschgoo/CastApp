# CastApp

Android app that casts to a **Chromecast**, **NVIDIA Shield**, **Google TV**, **Google/Nest speakers** and **DLNA smart TVs** (Samsung, LG, Sony…). It can:

- mirror your screen or a single app
- play the phone's sound
- play videos, music and photos from the phone
- play videos it finds on websites

No ads, no tracking, no account. It doesn't need Google Play Services or the phone's built-in Cast menu; it talks to your devices directly over your Wi-Fi.

<p>
  <img src="docs/video_mode.png" width="260" alt="TVs, including a DLNA TV">
  <img src="docs/audio_mode.png" width="260" alt="Audio mode with speakers, dark theme">
  <img src="docs/file_queue.png" width="260" alt="Playing a play list with subtitles">
</p>

## Why it finds devices the built-in Cast menu misses

The system Cast menu relies on a single discovery method (multicast DNS). That method often breaks with mesh Wi-Fi, some routers, or a phone that is aggressively saving power. CastApp combines five methods:

1. **Android's own discovery** (NsdManager)
2. **Its own mDNS browser.** Devices answer it directly by unicast, so this works even when multicast replies get dropped.
3. **DLNA/UPnP search (SSDP).** This finds smart TVs without Chromecast.
4. **Network scan.** It checks every address on your Wi-Fi for a Cast device and asks each one for its name. If nothing turns up within 4 seconds, the scan starts automatically.
5. **Add by IP.** You type the address yourself.

Any device you've cast to once is **remembered**. If its IP address changes, CastApp finds it again.

## Features

**Video mode** (TVs):

- **Mirror the entire screen** with one tap on **Cast**.
- **Cast a single app.**
  - Android 14 and newer: Android's own picker; only that app is shown on the TV.
  - Android 13 and older: the screen is mirrored and the app you pick opens.
- **Videos, music and photos from the phone**, with the phone's screen off.
  - Pick several files to get a **play list**.
  - **Photos** become a slideshow, shrunk to TV size; HEIC and huge photos work too.
  - **Subtitles**: pick an `.srt`/`.vtt` file with the same name as the video (or pick just one video and one subtitle file). They can be switched on and off.
  - Also available from any gallery or file app via **Share → Cast to TV**.
- **Videos from websites**: the built-in browser (🌐 at the top) spots the videos a page plays and sends them to the TV. The TV then plays them by itself, so you can leave the app or lock the phone. A link shared from Firefox, Chrome and so on (**Share → Cast to TV**) opens in this browser.
- **Converts videos the TV can't play** (e.g. HEVC or MKV on an older Chromecast). The phone re-encodes them to H.264/AAC on the fly. This happens automatically; you can choose Automatic, Always or Never in Settings.
- **Dark screen** (Android 14+, single-app casting): the phone goes black at minimum brightness while the app keeps playing on the TV. Tap once for controls, double-tap to wake.

**Audio mode** (switch at the top):

- Lists **speakers and speaker groups** as well as TVs.
- **Cast the phone's sound** (Android 10+): whatever plays on the phone plays on the speaker. The microphone is never used.
- **Play music files** on a speaker.

**Picture and sound:**

- **Low delay**, under a second with Google's standard player.
- An optional own **low-latency TV player** gets it to about 0.3 s (see below).
- **Automatic quality**: when the Wi-Fi can't keep up, the bitrate drops; when the connection is good again, it rises.
- **Picture shape**:
  - *Fit*: black bars where the shapes differ.
  - *Fill the TV*: zooms in and cuts off the edges.
  - *Turn apps sideways*: rotates the phone's apps to landscape so they fill the TV.
- **Sound sync**: shift the sound ±500 ms. Changes apply instantly while casting.
- **Reconnects by itself** if the Wi-Fi drops or the TV restarts. It keeps trying for 2 minutes, and a file continues where it stopped.

**Controls and quick access:**

- TV volume and mute from the app, the notification and, when casting a file, the phone's volume buttons.
- Media controls on the lock screen when casting files.
- **Quick Settings tile "Cast screen"**: tap to mirror to the last TV, tap again to stop. Long-press opens the app.
- **Launcher shortcuts**: long-press the app icon for "Mirror screen to *your TV*".

**Looks:** light and dark mode that follow the system, with wallpaper colors (Material You) on Android 12+. You can force Light or Dark in Settings.

## Install

**Download a ready-made APK:** open the repository's **Actions** tab, select the latest **Build APK** run and download the **CastApp** artifact. It's a zip containing `CastApp.apk`. If you push a tag such as `v2.0.0`, the APK is also attached to a GitHub Release.

To install, open the APK on your phone and allow "Install unknown apps" for your browser or file manager when Android asks.

**Build it yourself:**

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

You need JDK 17+ and the Android SDK with platform `android-37.1`. Opening the project in Android Studio sets that up for you.

## How to use

1. Connect your phone and the TV or speaker to the **same Wi-Fi**. Guest networks usually block device-to-device traffic.
2. Open CastApp and choose **Video** or **Audio** at the top.
3. Tap **Cast** next to a device:
   - in Video mode, this mirrors the screen;
   - in Audio mode, it casts the phone's sound.

   Tap the device's name for more options: a single app, files, web videos, or sound only.
4. Confirm Android's "Start casting?" dialog. File and web-video casting doesn't need this dialog.
5. Stop from the app, the notification, the Quick Settings tile, or the TV remote.

To add the tile, pull down Quick Settings, tap the pencil (edit) icon and drag **Cast screen** into your tiles.

## Casting with the phone's screen off

| What you cast | Screen off / locked? |
|---|---|
| **Files, photos, web videos** | **Yes.** The TV fetches them itself; lock the phone and pocket it. |
| **The phone's sound** (Audio mode) | **Yes**, on most phones: music apps keep playing in the background. |
| **A single app** (Android 14+) | **Use Dark screen.** The display stays technically on but black, so the app keeps playing. The TV doesn't see the dark overlay. |
| **The entire screen** | **No.** If the screen is off there is nothing to mirror, and Android pauses most apps. |

**Can I cast from Firefox and leave it playing?** Not by mirroring Firefox: as soon as you leave it or lock the phone, the video pauses. Instead, share the page from Firefox to **Cast to TV** (or open it in CastApp's browser) and cast the video it finds. The TV then plays it on its own, like YouTube's Cast button.

## DLNA TVs (Samsung, LG, Sony, …)

TVs without Chromecast appear with a **DLNA** label.

- Files, photos, music and web videos work well.
- Samsung TVs also show `.srt` subtitles.
- Screen mirroring to a DLNA TV is **experimental**. It works on many TVs, but with a few seconds of delay, because these TVs buffer heavily. A Samsung TV's own *Smart View* may be faster for mirroring.

A TV that supports both Chromecast and DLNA is shown once, as a Chromecast.

## Low-latency TV player (optional, advanced)

Google's standard player buffers about a second. CastApp includes its own TV player (`receiver/index.html`) that keeps the picture about 0.3 s behind the phone. Google only lets registered players run on Cast devices, so it needs a one-time setup:

1. **Host the player.**
   1. In your GitHub repository, go to **Settings → Pages** and set the source to **GitHub Actions**.
   2. Under **Actions**, run **Publish TV player**.

   It is published at `https://<your-user>.github.io/<repo>/`. GitHub Pages on a private repository needs a paid GitHub plan; any other HTTPS web host works too.
2. **Register it** at the [Google Cast SDK Developer Console](https://cast.google.com/publish) (one-time US$5 fee):
   1. Add a **Custom Receiver** with the address from step 1.
   2. Either publish it, or register your Chromecast/Shield's serial number as a test device.
3. **Use it.** In CastApp, go to **Settings → Low-latency TV player** and enter the 8-character **App ID**.

If the player doesn't start, CastApp falls back to Google's standard player automatically. You can also try the player in a desktop browser: `receiver/index.html?test=<stream URL>&codecs=avc1.64001F,mp4a.40.2`.

## How it works

```
 Phone                                                      TV / speaker
 ─────                                                      ────────────
 MediaProjection ─► H.264 encoder ─┐                         Chromecast / Shield / Google TV:
                                    ├─► stream server :8090 ◄─ fetches live MP4 / HLS / AAC
 AudioPlaybackCapture ─► AAC ──────┘   (token in every URL)    DLNA TV: fetches live MPEG-TS
 Files / photos / subtitles ──────────►  byte-range file server ◄─ seeks
 Web videos ──► (direct, or via the phone's proxy for cookies/CORS)
 Unplayable video ─► decode ─► GL scale ─► H.264/AAC ─► live MP4

 Control: Cast v2 (TLS :8009) to the Default Media Receiver or our own player,
          UPnP AVTransport (SOAP) for DLNA TVs
```

- **Cast protocol.** CastApp speaks Cast v2 itself (`cast/`): protobuf messages over TLS on port 8009.
- **Stream.** The screen is encoded with the phone's hardware encoder and sent as live fragmented MP4, one frame per fragment. Other formats are used when a device needs them:
  - HLS for compatibility;
  - plain AAC for speakers;
  - MPEG-TS for DLNA TVs.

  If the Wi-Fi falls behind, the bitrate is lowered, and if needed buffered frames are dropped so the delay stays short.
- **Files.** The phone serves files with byte-range support, so the TV can seek. Photos are shrunk on the phone. SRT subtitles are converted to WebVTT, including old Windows-1252 files with umlauts.
- **Web videos.** The browser watches the page's network traffic and `<video>` elements. If the TV can't fetch a video directly, the phone relays it: it adds the site's cookies and referrer and the CORS headers Cast devices need, and rewrites HLS playlists so every segment also goes through the phone.
- **Converting.** `MediaExtractor` → hardware decoder → OpenGL scaling (max 1920×1080) → H.264 encoder. Audio is decoded, mixed down to stereo and re-encoded to AAC. The TV pulls the result as a stream, and seeking restarts the conversion at the new position.
- **Privacy.** Every URL contains a random token, so other devices on your network can't watch. The servers only run while you're casting.

## Limitations

- **Protected video shows black.** Netflix, Disney+, Prime Video and banking apps block screen capture (an Android rule). Their websites use DRM, so the browser can't cast them either. Use their own Cast button.
- **YouTube** uses streams that only its own apps can play. Use YouTube's Cast button, or the YouTube app on the Shield.
- **Some apps block audio capture** (often streaming or music apps). For music, Spotify Connect and similar built-in features work better.
- **Converting** uses battery and only works while the phone is on Wi-Fi. Seeking in a converted video takes a moment.
- **Untested on real hardware.** Everything is built and unit-tested. The streams were also checked in a real browser player:
  - live MP4 in Chromium;
  - the low-latency player at about 0.35 s of buffer;
  - AAC and MPEG-TS in ffmpeg;
  - file seeking in Chromium.

  These couldn't be checked without real phones and TVs:
  - the Cast and DLNA handshakes with real devices;
  - screen capture and the permission flow on each Android version;
  - hardware encoders and decoders (also used for converting);
  - Dark screen and Turn apps sideways;
  - lock-screen controls.

## Troubleshooting

| Problem | Try this |
|---|---|
| TV not listed | Tap **Scan network**. Make sure it isn't a guest network. Or add it with **Add by IP**: the address is on the TV under *Settings → Network*. |
| Speaker not listed | Switch to **Audio** at the top. |
| "Can't reach … Is it on the same Wi-Fi?" | Your phone or TV moved to another network or band. Some routers isolate 2.4 GHz from 5 GHz, or wired from wireless. |
| TV shows a spinner, then nothing | Settings → **Stream mode → Compatible**. Also check that a VPN or firewall app on the phone isn't blocking incoming connections on port 8090. |
| A file won't play | Settings → **Convert videos the TV can't play → Always**. |
| A website's video won't play | Start the video on the page first, then tap **Cast video**. If several are listed, try the "Stream (HLS)" one. |
| Choppy picture | Keep **Automatic quality** on, lower the quality to 540p, or move closer to the router. |
| Lips and sound don't match | Settings → **Sound sync**. |
| Port conflict | Change **Stream port** in settings. |

## Project layout

```
app/src/main/java/io/github/patschgoo/castapp/
├── cast/          Cast v2 protocol (protobuf framing, TLS client)
├── dlna/          UPnP/DLNA discovery (SSDP) and SOAP control
├── discovery/     NSD, raw mDNS, network scan, remembered devices
├── render/        "play this URL" for Cast and DLNA devices
├── stream/        MP4/TS/ADTS muxers, stream & media servers, HLS, web proxy, subtitles
├── capture/       screen and audio encoders, OpenGL scaler, video converter
├── service/       foreground service, screen/sound and media sessions, overlays
├── browser/       video detection for the built-in browser
├── tile/          Quick Settings tile
├── ui/            Jetpack Compose screens
receiver/          optional low-latency TV player (web app for Cast devices)
```

## Signing key

`app/castapp.keystore` (password `castapp`) is committed on purpose. Every build, on your PC or in CI, is signed with the same key, so a new APK installs over the old one. This is fine for personal use. If you ever publish the app, replace the key with your own private one.

## License

No license has been chosen yet. Add one (for example MIT or GPL-3.0) if you want others to be able to reuse the code.
