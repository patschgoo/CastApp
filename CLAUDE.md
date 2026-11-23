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
- Screenshots (JVM, Robolectric native graphics + Roborazzi, opt-in): add `-Pscreenshots` to the unit-test
  command with `-Xmx3g` (it then runs only `app/src/test/.../screenshots/`, ~6 min). PNGs of the main
  screens (light/dark, en/de, font 1.0/2.0, phone/tablet, TV home) go to `app/build/outputs/roborazzi/`,
  each with a `.txt` of cut-off text, mid-word breaks and tap targets < 48 dp. `-Pscreenshots=store`
  also rewrites `docs/*.png` and the fastlane `phoneScreenshots` (1080 x 2400, en-US and de-DE). Read the
  pictures after UI changes; without the property these tests are excluded (CI doesn't render).
- Lint: `:app:lintDebug` must have no errors (it fails on missing German translations).
- Release APK: `:app:assembleRelease` with `-Dorg.gradle.jvmargs=-Xmx3g`. It builds whisper.cpp with the
  NDK for arm64-v8a and armeabi-v7a, so the first build takes a while. Watch app: `:wear:assembleRelease`.
- Add `--offline` once the Gradle cache is filled. Never run `./gradlew --stop` while other builds run.
- CI: `.github/workflows/build.yml` runs the tests, builds `CastApp.apk` and `CastApp-wear.apk`, and on a
  `v*` tag (which must equal `v<versionName>`) publishes a GitHub release that the in-app update check reads.
  Releases (user, 2026-11-23): the user allowed Claude to publish them (`.claude/settings.json` allows
  `mcp__github__actions_run_trigger`). When a version is finished: bump versionName/versionCode, write the
  fastlane changelogs (en-US, de-DE), run tests and lint, wait for green CI on `main`, then run the workflow
  `build.yml` on `main` with the input `release: true` (it creates the tag `v<versionName>` and the release).
  Then check that `CastApp.apk` and `CastApp-wear.apk` are attached, mark the version released in the feature
  map, and tell the user. Cloud sessions can't push tags (the proxy answers 403); the user may still tag a
  release by hand on GitHub.
  Signing: since 2026-11-23 CI signs with the user's own key from the repository secrets (certificate SHA-256
  `50c0d1fe…`); 4.0.0 and 4.1.0 were signed with the committed test key (`b292cebc…`). 4.2.0 (2026-11-23) is
  the first release with the user's key (checked with apksigner); its notes say to uninstall CastApp once on every
  device (phone, Shield, watch app). Later releases update normally.

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
  and the IPTV playlist list (addresses can hold an Xtream log-in) are encrypted the same way (`SealedText`).
  `res/xml/backup_rules.xml` and `data_extraction_rules.xml` keep IPTV data and recordings out of Android's
  backups: add new files that hold log-ins there. Peer and receiver endpoints only answer the local network.
- Protocols are tested against fakes over real sockets (see `app/src/test/`): Cast, DLNA, AirPlay (pyatv's
  pairing as reference), Roku, Samsung/LG/Android TV remotes, Plex/Jellyfin, IPTV/HLS recording, peers.

- **Commits (user's rule, 2026-11-23): Claude never appears as a contributor.** Commit as the user,
  `git config user.name "patschgo"` and `git config user.email "88206373+patschgoo@users.noreply.github.com"`
  (set it in every new clone), and add no `Co-Authored-By` or `Claude-Session` lines to commits or PRs. The
  history was rewritten that day (all commits under the user's name), so commit IDs from before 2026-11-23
  14:40 UTC are gone; never push from an older clone or merge old history back in.

## Layout

`app/src/main/java/io/github/patschgoo/castapp/`: `cast/` Cast v2, `dlna/` UPnP, `airplay/`, `tv/` the
CastApp TV receiver, `discovery/`, `render/` one "play this URL" per device kind, `stream/` muxers and
servers, `capture/` encoders and converter, `service/` foreground service and sessions, `multi/` several
screens, join, sync and hand-over, `mediaservers/` Plex and Jellyfin, `iptv/`, `network/` SMB/WebDAV,
`subtitles/` Vosk and Whisper (`app/src/main/cpp/`), `opensubtitles/`, `remote/`, `youtube/`, `support/`,
`automation/`, `wear/` (phone side of the watch app), `ui/` (the main screen is `TileHome.kt`: "Cast to", big buttons,
source tiles, cast bar; `DevicesScreen.kt` wires it and holds the casting card). Modules `wear/` and `wear-protocol/` are the
Wear OS remote.

## Feature map

`docs/feature-map.html` is the CastApp Feature Map, published at https://claude.ai/artifact/1e4vRWqiCUkQNYAKQp5onb
(features with stable IDs and status, connections, walk-throughs, rules, what needs a device test). Whenever a
feature is added, changed, released or dropped, update that file (status, IDs, links, the "Last updated" line
with the commit) and republish it to the same link. AniBox has its own map (linked from its CastApp section).

## State (version 4.2.0)

Everything is built and unit-tested, but most features since 3.1 have **not been tried on real devices yet**.
First device test (2026-11-23, an Android 14+ phone with a MediaTek chip + the Shield): the self-test had no problems (notes:
notifications off, battery optimization, no speech model); the Shield shows up as CastApp TV; mirroring works but
the delay felt bigger than expected (unclear whether the Google Cast or the CastApp TV entry was used); sound was
missing once; "Send debug log" didn't work (cause unknown; 4.2.0 adds Save as file / Copy). The Shield's real
`/castapp/capabilities`: no decoder for Hi10P or AV1 (not even software), no DTS/TrueHD (no decoder, no passthrough
on the user's TV), AC-3/E-AC-3 decoded and passed through. 4.2.0 answered the test: switch whole screen <-> one app
in the running session, the live mode on the main screen, the debug log dialog, "Use CastApp TV" for Google Cast
entries of boxes that run it, a note when an app's sound can't be recorded.
The redesigned main screen (violet theme and bundled Plus Jakarta Sans font, no Material You colors any more;
"Recent on this phone" behind the optional video permission) has been seen on one real phone so far (the user's
screenshots, 2026-11-23: as designed).
Reports from the TESTING.md round are the next input: expect fixes in device handshakes (Cast, DLNA, AirPlay,
Roku), Plex/Jellyfin transcoding parameters, hand-over on Google Cast, recordings on Android 12+ and the
Wear OS Data Layer.
