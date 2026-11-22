# CastApp: notes for working on this repository

Android app (Kotlin, Jetpack Compose Material 3) that casts to Google Cast, DLNA, AirPlay, Roku and its own
receiver ("CastApp TV", the same APK on Fire TV / Android TV). Users read [README.md](README.md); the device
test checklist is [TESTING.md](TESTING.md); the intent API is [AUTOMATION.md](AUTOMATION.md).

## Build and test

- Needs the Android SDK in `/opt/android-sdk` with `platforms;android-37.1`, `build-tools;36.0.0` and
  `37.0.0`, `ndk;29.0.14206865` and `cmake;3.22.1` (the cloud environment "CastApp" installs them), and a
  `local.properties` with `sdk.dir=/opt/android-sdk` (untracked).
- Unit tests (JVM, no emulator):
  `./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest`
  plus `:wear-protocol:testDebugUnitTest`. A few tests are opt-in and skipped by default.
- Lint: `:app:lintDebug` must have no errors (it fails on missing German translations).
- Release APK: `:app:assembleRelease` with `-Dorg.gradle.jvmargs=-Xmx3g`. It builds whisper.cpp with the
  NDK for arm64-v8a and armeabi-v7a, so the first build takes a while. Watch app: `:wear:assembleRelease`.
- Add `--offline` once the Gradle cache is filled. Never run `./gradlew --stop` while other builds run.
- CI: `.github/workflows/build.yml` runs the tests, builds `CastApp.apk` and `CastApp-wear.apk`, and on a
  `v*` tag (which must equal `v<versionName>`) publishes a GitHub release that the in-app update check reads.

## Conventions

- Every user-visible text is a string resource in **English** (`res/values/strings_*.xml`) **and German**
  (`res/values-de/`, informal "du"). New features get their own `strings_<feature>.xml` pair. Code
  without a Context uses `Txt.s(...)` / `Txt.sOr(...)` (the latter keeps English for plain JVM tests).
  Texts sent to TVs, servers or other phones stay English.
- Check for duplicate string names after merges:
  `grep -ho 'name="[^"]*"' app/src/main/res/values/strings*.xml | sort | uniq -d`.
- minSdk 25 (Fire OS 6), targetSdk 36. Guard newer APIs.
- Casting to devices **without** CastApp must keep working unchanged; CastApp-to-CastApp features
  (low-latency player, swap, join, sync, hand-over) are extras with short timeouts and silent fallback.
- Secrets (passwords, server tokens) go through `network/Secrets` (Android Keystore); the watch history file
  is encrypted the same way. Peer and receiver endpoints only answer the local network.
- Protocols are tested against fakes over real sockets (see `app/src/test/`): Cast, DLNA, AirPlay (pyatv's
  pairing as reference), Roku, Samsung/LG/Android TV remotes, Plex/Jellyfin, IPTV/HLS recording, peers.

## Layout

`app/src/main/java/io/github/patschgoo/castapp/`: `cast/` Cast v2, `dlna/` UPnP, `airplay/`, `tv/` the
CastApp TV receiver, `discovery/`, `render/` one "play this URL" per device kind, `stream/` muxers and
servers, `capture/` encoders and converter, `service/` foreground service and sessions, `multi/` several
screens, join, sync and hand-over, `mediaservers/` Plex and Jellyfin, `iptv/`, `network/` SMB/WebDAV,
`subtitles/` Vosk and Whisper (`app/src/main/cpp/`), `opensubtitles/`, `remote/`, `youtube/`, `support/`,
`automation/`, `wear/` (phone side of the watch app), `ui/` (the main screen is `TileHome.kt`: "Cast to", big buttons,
source tiles, cast bar; `DevicesScreen.kt` wires it and holds the casting card). Modules `wear/` and `wear-protocol/` are the
Wear OS remote.

## State (version 4.0.0)

Everything is built and unit-tested, but most features since 3.1 have **not been tried on real devices yet**.
The redesigned main screen (violet theme, no Material You colors any more) hasn't been seen on a real phone at all.
Reports from the TESTING.md round are the next input: expect fixes in device handshakes (Cast, DLNA, AirPlay,
Roku), Plex/Jellyfin transcoding parameters, hand-over on Google Cast, recordings on Android 12+ and the
Wear OS Data Layer.
