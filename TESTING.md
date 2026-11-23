# Real-device test checklist

CastApp talks to many kinds of devices that no unit test can reach. This list is for the first rounds on real hardware and for every release. Do not try everything: pick the devices you own, and write down the result (OK / not OK / not tried) next to each item.

## Start here: 30 minutes with a phone and an NVIDIA Shield

The full list below is long. These ten checks cover most of what can go wrong, with a phone, a Shield (Plex server and CastApp TV) and, if you have one, a Chromecast or a smart TV. Tick each one, and for every *not OK* send the debug log (see "If something fails").

| # | Check | OK when |
|---|---|---|
| 1 | Phone: **Settings → Help & support → Test this phone** | No *Problem* in the list |
| 2 | Install the same APK on the Shield, open **CastApp TV** once | The phone's "Cast to" list shows the Shield as a CastApp TV |
| 3 | **Mirror screen** to the Shield, open a video app on the phone | Picture and sound on the TV within a few seconds, under half a second of delay |
| 4 | **Network & cloud → +**, paste a Shield share address from Explorer (e.g. `\\<Shield IP>\External USB drive-XXXX\NVIDIA_SHIELD\Anime2`), sign in, play an episode on the Shield | The folder opens in the right place; episodes are in order; seeking works |
| 5 | Same episode to a Chromecast or a Samsung/LG TV (if you have one) | It plays, or you get a clear message (not "couldn't play") |
| 6 | **Plex** (sign in with the code), play an anime episode on the Shield's CastApp TV | Plays without converting; its subtitles show; "Continue watching" in Plex knows where you stopped |
| 7 | Pick another **subtitle** in the casting card (e.g. a 2nd language) | It switches; ASS/SRT files next to the video are listed by language |
| 8 | **Remote** (top right) on the Shield; then the small remote in the casting card | D-pad, volume, Back and Home work; both don't stop each other |
| 9 | Lock the phone during a file cast for 10 minutes | Keeps playing; lock-screen controls work |
| 10 | Large font (Android settings → display size and font) and German phone language | Main screen, casting card and settings still readable, nothing cut off |

## Screenshots without a device (layout check)

Layouts can be checked before anyone holds a phone: Robolectric renders the Compose screens on the PC (real pixels, the app's own font) with made-up TVs, films and channels, and nothing goes to the network.

```
./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest -Pscreenshots
```

- The pictures land in `app/build/outputs/roborazzi/<screen>/<look>.png`: the main screen (nothing casting, mirroring, a file with the cast bar, the player sheet with the remote, **Cast to**), Settings and its search, the remote, Network & cloud, Jellyfin (home and posters), the TV guide grid and CastApp TV's home. Each in light and dark, English and German, font size 100 % and 200 %, phone (411 × 891 dp) and tablet (1280 × 800 dp); the TV home at 1920 × 1080.
- Next to a picture, a `.txt` lists what a person would trip over: text cut off with "…", words broken in the middle, tap targets smaller than 48 dp. Some cuts are on purpose (long device details in the **Cast to** list); the rest are bugs.
- `-Pscreenshots=store` also writes the store pictures (`fastlane/metadata/android/en-US` and `de-DE`, 1080 × 2400) and the README pictures in `docs/`.
- Without `-Pscreenshots` these tests don't run, so the normal unit tests (and CI) stay fast. The test code is in `app/src/test/java/.../screenshots/` (`FakeData`, `FakeServer` for the Jellyfin and IPTV content).

What the JVM can't show: system bars, the keyboard, real thumbnails from the phone's gallery, animations and anything a device does. Use it to find layout problems, not to replace the checks below.

## Before you start

1. Install the APK (see the README) on the phone, and open it once.
2. On every test phone: **Settings → Help & support → Test this phone**. Look at the result and note anything marked *Problem*. The result is also stored in the debug log.
3. Phone and devices must be on the **same Wi-Fi** (no guest network, no VPN, no "AP isolation" on the router). If a device isn't found, tap **Why can't I find my TV?** in the same settings section.
4. Have a short MP4 (H.264/AAC), an MKV or HEVC video, a music file, a few photos and an `.srt` file with the same name as the video ready on the phone.

### If something fails (always the same)

1. **Settings → Help & support → Test this phone**, then **Send debug log** right in that sheet (or **Settings → Help & support → Send debug log**). The log contains the self-test result.
2. Send the log together with: the **phone model and Android version**, the **device model and its software version**, **what you did** step by step, and **what you expected and what happened**. A photo of the TV helps.

Every item below ends with "if it fails" for this reason.

## Phone self-test

| Step | Expected | If it fails |
|---|---|---|
| Open **Test this phone** on each phone | Finishes in a few seconds; H.264 and AAC encoders, screen capture and network are OK | Send the debug log and the phone model; a *Problem* here usually explains the failures below |
| Run it on mobile data only (Wi-Fi off) | Network shows *Problem*: only mobile data | Debug log + self-test |
| Run it with a VPN on | Network shows a note about the VPN | Debug log + self-test |
| Run it on a phone with an IR blaster, and one without | Infrared OK on the first, a note on the second | Debug log + self-test |
| Run it while **Receive on this phone** is on | Receiver port OK ("used by CastApp itself") | Debug log + self-test |

## The main screen (redesigned)

The main screen is new: **Cast to** on top, three big buttons, tiles for everything else, and a bar at the bottom while something is cast. None of it was tried on a real phone yet.

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Cast to | Open the app with one TV on, then with two | The TV used last (or the first one found) is shown with **Ready**; tapping the row lists all TVs, the one in use has a check mark | Screenshot + debug log |
| Pick another TV | Tap **Cast to**, tap another TV | The sheet closes, the row shows the new TV; **Mirror screen** now goes there. The Quick Settings tile uses it too | Screenshot |
| TV not found | Turn the TV used last off, reopen the app | The row keeps that TV and says **Not found**; **Scan network**, **Add by IP** and the network check are in the **Cast to** sheet | Screenshot + debug log |
| Video / Audio | In the **Cast to** sheet switch to **Audio** and pick a speaker | Speakers and groups appear; the buttons become **Phone sound** and **Music files** | Screenshot |
| Big buttons | **Mirror screen**, **One app**, **Phone sound**, one after the other | Each starts on the TV in **Cast to**; while mirroring the first button is **Stop mirroring** | Debug log |
| Tiles | **Phone videos**, **Photos**, **Music** | The file picker offers only that kind; what you pick plays on the TV in **Cast to** | Debug log |
| Plex / Jellyfin tiles | Tap **Plex**, press Back | Plex opens directly; Back returns to the main screen (not to the network list) | Screenshot |
| Cast bar | Cast a video | A dark bar at the bottom shows title, TV, progress and pause; it stays above the navigation bar | Screenshot |
| Casting card | Tap the bar | A sheet with everything the old casting card had (seek, quality, subtitles, queue, sleep timer, more screens, volume, Stop, Dark screen) | Screenshot |
| Remote in the card | In the sheet tap **Use this phone as the remote** | It connects (the TV may ask for permission or show a code once); D-pad, volume, channels, Back, Home, Menu and Mute work; **All buttons** opens the full remote | Debug log + TV model |
| ⋮ per TV | In the **Cast to** sheet tap ⋮ next to a TV | Mirror, single app, files, web video, sound, remote, forget and Wake-on-LAN for that TV | Screenshot |
| Recent on this phone | Tap **Show videos** under the tiles and allow access; record a new video and come back | The newest videos appear with pictures and length, the new one first; a tap plays it on the TV in **Cast to** | Debug log + Android version |
| Recent: only some videos | Android 14+: answer the permission dialog with **Select photos and videos** and pick two | Exactly those two are listed | Android version |
| Recent: no access | Refuse the permission twice, tap **Show videos** again | The app's page in the system settings opens (Android no longer asks) | Android version |
| Recent: turned off | Tap **Not now** | The row disappears with a note; **Settings → Appearance → Recent videos on the main screen** brings it back | Screenshot |
| Light and dark | Switch the phone between light and dark mode | Violet look and the app's own font (Plus Jakarta Sans) in both; tiles and the bar stay readable | Screenshot |
| Tablet / landscape | Open the main screen 600 dp wide or wider | Tiles on the left, casting card and TV list on the right; no bar at the bottom | Screenshot + screen size |
| Large text and German | Largest font size; phone language German | Button labels wrap to two lines, nothing is cut off | Screenshot |

## Chromecast, Google TV, NVIDIA Shield (Google Cast)

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Discovery | Open CastApp | The device shows up within about 10 s | Debug log + self-test + **Why can't I find my TV?** result |
| Mirror screen | Tap the device, **Cast** | Picture on the TV, delay under a second, sound follows | Debug log + self-test |
| Change quality | While mirroring, **Quality**, switch resolution and frame rate | The picture changes without the cast ending | Debug log + self-test |
| Phone sound | Audio mode, play music in another app | Music plays on the Chromecast Audio / speaker / TV | Debug log + self-test (check "Sound of other apps") |
| Local video | Pick the MP4, then the MKV/HEVC one | Both play (the second is converted); seeking works | Debug log + self-test + which file type |
| Screen off | Cast a file, turn the phone screen off for 5 minutes | Playback continues | Debug log + self-test (check "Battery optimization") |
| Subtitles | Play the video with its `.srt` | Subtitles appear; **Subtitle look** and timing change them | Debug log + the subtitle file |
| Reconnect | Mirror, switch the phone's Wi-Fi off and on | Reconnects within 2 minutes | Debug log |
| YouTube | Share a YouTube link to CastApp | Plays in the TV's YouTube app | Debug log + the link |
| Low-latency player (optional) | Settings → Low-latency TV player, enter an App ID, mirror | Delay of about 0.3 s, or an automatic fallback to the standard player | Debug log + the App ID setup |

## CastApp TV on Fire TV, Android TV and Shield

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Install | Install the APK on the TV and open it once | **CastApp TV** home screen with the TV's name and address | Photo of the TV + debug log (from a phone connected to it, if possible) |
| Discovery from the phone | Open CastApp on the phone | The TV appears with the **CastApp TV** label | Debug log + self-test of the phone |
| Mirror | Cast the phone's screen | Picture and sound, low delay | Debug log + self-test |
| Files | Play video with subtitles, music and photos | All three play; photos advance as a slideshow | Debug log + which type |
| Start with the TV | Turn it on in the TV app, restart the TV | The TV is found by the phone without opening the app | Debug log + the TV model and Fire OS / Android version |
| Open over other apps | Cast while another TV app is in front (Android 10+ / Fire OS 8) | The player opens by itself, or the app tells you what to allow | Photo + debug log |
| Receiver protection | Set **Ask first** / **Only paired phones**, cast from a second phone | The TV asks / needs the 4-digit code; a wrong code is refused | Debug log |
| Rename | Rename the TV in the TV app | The phone shows the new name | Debug log |

## Samsung and LG DLNA TVs

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Discovery | Open CastApp | The TV appears with a DLNA label (turn it on first; Wake-on-LAN may be needed from standby) | Debug log + self-test + TV model and year |
| Video | Cast the MP4 | Plays; pause, seek and volume work | Debug log + TV model |
| MKV / HEVC | Cast the other video | Plays (converted if needed) | Debug log + file details |
| Music and photos | Cast a song and photos | Both work | Debug log |
| Subtitles | Cast the video with its `.srt` | Subtitles appear, or the app says that this TV can't show them | Debug log |
| Wake-on-LAN | Switch the TV to standby, use **Wake up** in the device menu | The TV turns on (Samsung: the MAC address was filled in automatically) | Debug log + TV model |
| Remote | Open the TV remote | Keys work (Samsung: **Allow** on the TV the first time; LG: accept on the TV) | Debug log + TV model and year |

## Apple TV (AirPlay)

CastApp's AirPlay has only been tested against a reference implementation, so expect surprises here.

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Discovery | Open CastApp | The Apple TV appears with the **AirPlay** label | Debug log + self-test + tvOS version |
| Pairing | Tap it, enter the 4-digit code shown on the TV | Paired; the next time no code is asked | Debug log + tvOS version (this is the most important one to report) |
| Video | Cast the MP4 | Plays; pause and seek work | Debug log |
| Web video / HLS channel | Play one from the browser or an IPTV list | Plays | Debug log + the address |
| Mirror | Mirror the screen | Works with a delay of a few seconds | Debug log |

## Roku (experimental)

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Discovery | Open CastApp | The Roku appears with the **Roku** label | Debug log + self-test + Roku model |
| Video and music | Cast an MP4 and a song | Both play (Roku offers no seeking) | Debug log + Roku model and software version; check Settings → System → Advanced → Control by mobile apps |
| Remote | Open the TV remote | Keys work with no setup | Debug log |

## Speakers (Google/Nest, groups)

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Discovery | Switch to **Audio mode** | Speakers and speaker groups are listed | Debug log + self-test |
| Phone sound | Cast the phone's sound (Android 10+) | Sound plays on the speaker; the microphone is not used | Debug log + self-test |
| Music file | Play a song from the phone | Plays; skipping tracks works | Debug log |
| Group | Cast to a speaker group | All speakers play in sync | Debug log + group setup |

## Remotes

| Remote | Steps | Expected result | If it fails |
|---|---|---|---|
| Network, Google TV / Shield | Open the remote, enter the pairing code from the TV | Navigation, volume and voice search work | Debug log + TV model |
| Network, Roku / Samsung / LG | See the sections above | Keys work | Debug log + TV model |
| Bluetooth | Pair the phone as a keyboard (Fire TV: Settings → Controllers & Bluetooth Devices → Other Bluetooth Devices; Shield: Add accessory) | The TV accepts it; keys work | Debug log + self-test ("Bluetooth keyboard mode") + both device models |
| Infrared | Point the phone at the TV, try power and volume | The TV reacts | Debug log + TV brand and model; say that the phone has an IR blaster |
| Both remotes | Use the remote in the casting card, tap **All buttons**, go back, turn the phone | The TV stays listed and the keys keep working in both; no new search or reconnect after turning | Debug log |

## NAS, Plex, Jellyfin and network shares

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Windows / Samba share | Add the share in CastApp, browse, play a video | The list loads and the video plays | Debug log + server type (Windows, Synology, ...) and the SMB version if you know it |
| Media server (DLNA) | Open Plex/Jellyfin/another server under *Media servers on this Wi-Fi*, browse, play | Library appears; playback starts; resume position is remembered | Debug log + server and version (no passwords: the log hides them, but check) |
| Jellyfin / Emby sign-in | **Plex & Jellyfin → Jellyfin & Emby**, pick the found server or type its address, sign in | Libraries with posters, Continue watching and Next up appear | Debug log + server version; say if the address was found or typed |
| Plex sign-in | **Plex & Jellyfin → Plex**, enter the code at plex.tv/link | Your servers appear; the local one opens | Debug log + whether the server is local, remote or relayed |
| Server playback | Play a movie the TV can play and one it can't (for example MKV/HEVC on an older Chromecast) | First plays directly, second through the server's conversion; server subtitles can be switched on | Debug log + file details (codec, container) + TV |
| Resume and watched sync | Stop an episode halfway, open the same server in its own app | The server's app shows the same position; finishing marks it watched; the next episodes are queued | Debug log + server version |
| Cloud folder | Add a cloud folder | Files list and play | Debug log + provider |
| Away from home | Open a NAS over your VPN or a Tailscale address | Works as at home | Debug log + how you connect |
| Queue | Pick several files | Play list; reorder, remove, shuffle, repeat work | Debug log |
| Continue watching | Stop a video halfway, open it again | Resumes where you stopped | Debug log |
| Continue watching pictures | Stop a Plex or Jellyfin episode, a phone video, a file on a share (on Wi-Fi) and a channel catch-up halfway, then go to the main screen | Each card shows its still, poster, logo or a frame of the video; an icon while it loads; scrolling the main screen stays smooth | Screenshot + where each video came from |

## NVIDIA Shield as media server and TV (anime library)

For a Shield that holds the drives (Plex Media Server and/or **Transfer files over local network**) and also runs CastApp TV. Only read from the drives; nothing here writes to them.

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| SMB version | On a Windows PC, check that **SMB 1.0/CIFS Client** is *off* (Windows Features), then open `\\<Shield IP>` in File Explorer | The shares open: the Shield speaks SMB 2/3, which CastApp needs | If it only opens with SMB 1.0 on, CastApp can't read the shares: report it (the Shield's Android version helps) |
| Paste a share address | In CastApp **Network & cloud → + → Windows / Samba share**, paste the address Explorer shows, e.g. `\\<Shield IP>\External USB drive-XXXX\NVIDIA_SHIELD\Anime`, with the user name and password the Shield shows | Saved as "Anime"; opening it starts in that folder | Debug log + the address shape (hide the IP if you like) |
| Every drive | Add each drive's share (and `internal`, a memory card) | All list their folders | Debug log + which one fails |
| Standby | Let the Shield go to standby, then open a share in CastApp | It answers (maybe after a few seconds) | Debug log + how long you waited |
| Two at once | Play one file on the phone and another on a TV from the same drive | Both play without stutter | Debug log |
| Anime file from the share | Play an MKV with HEVC 10-bit video and ASS subtitles on a Chromecast | The phone converts it; the subtitles can be picked | Debug log + the file's details (MediaInfo) |
| Episode order | Open a folder whose episodes mix 2- and 3-digit numbers (`02`, `028`, `10`, `100`) | Listed and played counted: 02, 10, 028, 100 | Folder listing (names only) |
| Subtitle files per language | Play a video that has many `Video.<Language>.srt` files next to it | Your language's file is shown (Subtitle languages setting, else the phone's language, else English); the subtitle picker lists every language by name | Debug log + the subtitle file names |
| `.ass` and `.divx` files | Play a video with only a `.ass` file next to it, and open a folder with `.divx` files | The ASS subtitles show (as plain text); "Write subtitles" is still offered, and with "Write subtitles automatically" on they are written in the background and appear as an extra choice while the ASS subtitles keep showing; the `.divx` files are listed and play | Debug log + file details |
| `.ass` on a cloud folder | Play a WebDAV folder (Nextcloud, Apache, rclone) with `Video.English.ass` files next to the videos | The `.ass` files are only offered as subtitles, never played as music in the queue | Debug log + the server's name |
| Plex on the Shield → CastApp TV on the Shield | Sign in to Plex in CastApp, play an anime episode (MKV/HEVC) with several subtitle tracks inside on the Shield's CastApp TV; pick another track in the subtitle picker, then **Off** | Plays the file itself (no converting on the Shield); the track in your **Subtitle languages** shows (a full one, not "Signs & Songs"), shown once, not twice; picking another track or Off changes it; seeking works | Debug log + file details (track names and languages); say if it fell back to the converted stream |
| Plex on the Shield → Chromecast | The same episode on a Chromecast | Plex converts it; after a short while (the phone reads the track out of the file: a bar shows how far) the subtitles appear in your language, in CastApp's **Subtitle look**; the picker lists every text track by language and name (no PGS ones); timing ±0.5 s works | Debug log + how long until they appeared + whether other converts were running |
| Plex queue with subtitles | Play episode 1 of a show on a Chromecast and let it run into episode 2 (or skip to it) | Episode 2 starts with its subtitles too (read while episode 1 played) | Debug log + the Plex server version |
| TV recording → Chromecast | Play an HD recording (`.ts`, H.264, e.g. ARD/ZDF HD) from the share on a Chromecast, seek twice | "Repackaging …" message; starts within a few seconds, sharp picture, sound (MP2/AC-3 converted to AAC), seeking works | Debug log + the recording's details (MediaInfo); say if the phone got warm |
| SD / MPEG-2 recording → Chromecast | The same with an SD recording (MPEG-2) | "Converting …" message; plays | Debug log + file details |
| XviD AVI on a Pixel → Chromecast | Play an XviD/DivX 5 `.avi` from the share on a Chromecast, seek | Converted; picture and sound in step, also after seeking | Debug log + phone model + file details |
| DivX 3 AVI → CastApp TV | Play a DivX 3 `.avi` (FourCC DIV3) from the share, then the same file through Plex, on CastApp TV | From the share: "no picture" on the TV, then the phone says it can't convert it (no decoder for DivX 3); through Plex: the server's stream plays with picture | Debug log + file details |

## IPTV and the programme guide

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| M3U list | Add an M3U address | Channels appear, grouped | Debug log + whether the list works in another player (never send the full address) |
| Play a channel | Cast a channel to each device type you own | Plays; channel switch works | Debug log + device + channel format if known |
| Radio | Cast a radio station | Plays | Debug log |
| Guide | Add an XMLTV address | Programme guide shows now and next | Debug log |
| Catch-up | Try it on a channel whose provider offers it | Plays an earlier programme | Debug log |
| Guide grid | Open the **Grid** tab, scroll through channels and hours, use **Now** and the day picker | Smooth scrolling; the *now* line is at the right time | Debug log + number of channels |
| Reminder | Tap a programme starting in 10–15 minutes → **Remind me**; lock the phone | Notification about 5 minutes before; **Watch on …** casts the channel. Also after a reboot | Debug log + Android version; say if battery optimization is on for CastApp |
| Record now | Long-press a channel → **Record now**, 30 minutes, to the phone | A recording notification; the file appears in **Recordings and reminders** and plays on the TV | Debug log + stream type (HLS or plain) if known |
| Planned recording | Record a programme from the grid, to a network folder | Starts by itself (or after a tap on the notification on Android 12+), uploads to the folder | Debug log + folder type (SMB/WebDAV) |
| One-connection warning | Watch a channel on the TV and record another from the same provider | CastApp warns before starting | Debug log |
| Parental lock | Set a PIN, lock one group and hide another | Locked group asks for the PIN; hidden group is gone from lists, grid and TV queue; open for 15 minutes after the PIN | Debug log |

## Subtitles

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Subtitle file | Video + `.srt` with the same name | Shown on the TV; on/off works | Debug log + the subtitle file |
| Own tracks | Play an MKV with built-in text subtitles | They are listed in the picker | Debug log + the file's details |
| Written by the phone (Vosk) | Download a language, play a video without subtitles | Subtitles appear after a short delay; the self-test lists the model | Debug log + self-test + language |
| Written by the phone (Whisper) | Download a model, pick the Whisper engine | Subtitles appear; **Test this phone** lists the model | Debug log + self-test + phone model (speed on weak phones is expected to be low) |
| Found online | Search for subtitles | Results listed and usable | Debug log |
| Look and timing | **Subtitle look**, timing ±0.5 s | Changes show immediately on Google Cast and CastApp TV | Debug log |

## Receivers, swap and multi-screen

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Receive on a phone | Turn on **Receive on this phone** on phone B, cast from phone A | Phone A sees phone B; picture and sound appear on B | Debug log from both phones + self-test of B (check "Receiver port") |
| Protection on a phone | Leave **Ask first** on | B asks before accepting A | Debug log from B |
| Swap | Mirror phone → CastApp TV, tap **Swap: TV → phone** | The TV mirrors to the phone (confirm on the TV); **Swap back** reverses it | Debug log from both + the TV model |
| Several screens | Cast to one TV, then **Add a screen** (another TV or a speaker) | Both play; each has its own volume and can be removed; the cast ends with the last one | Debug log + both devices |
| Mirror to several screens | Mirror to a Chromecast, add a DLNA TV or a CastApp TV | Both show the screen; quality changes reach both | Debug log + both devices |
| Synced playback | Play a video on two CastApp TVs (or a TV and a phone that joined) in hearing distance | No audible echo after a few seconds | Debug log from the phone + both TV models |
| Join a cast | Phone A casts; open CastApp on phone B | B shows "A is casting … · Join"; after A allows it, B plays the same picture | Debug log from both phones |
| Hand over (mirroring) | A mirrors to a TV, taps **Hand over…** → B; B accepts | B asks for screen capture, then the TV shows B's screen; A stops | Debug log from both + TV |
| Hand over (web/NAS/channel) | A plays a web video or channel, hands over to B | B continues at the same position | Debug log from both |
| Ask to cast | B taps **Ask to cast** while A casts | A sees "B wants to cast"; after **yes**, B takes over | Debug log from both |
| Plain TVs stay plain | Cast to a Chromecast/DLNA TV without CastApp on any other phone | Everything works exactly as before | Debug log |
| Widget and tiles | Add the home-screen widget; use the **Cast screen** tile | Quick cast and stop work; long-press opens the app | Debug log |

## Automation and Wear OS

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Off by default | `adb shell am broadcast -a io.github.patschgoo.castapp.automation.STOP -n io.github.patschgoo.castapp/.automation.AutomationReceiver` with the switch off | Result code `3` (disabled), nothing happens | Debug log |
| Tasker / MacroDroid | Turn on **Settings → Automation**, set a secret, use an example from [AUTOMATION.md](AUTOMATION.md) | The command works; a wrong secret gives `4` (unauthorized) | Debug log + the exact action and extras (hide the secret) |
| Home Assistant | Send `command_activity` with a URL to the companion app | The link plays on the TV | Debug log + Home Assistant app version |
| Mirror from automation | `MIRROR` while the app is closed | A notification asks to mirror; tapping it opens Android's capture prompt | Debug log + Android version |
| Wear OS remote | Install `CastApp-wear.apk` on the watch, cast from the phone | The watch shows the title; play/pause, next and volume work; **Mirror to …** shows the prompt on the phone | Debug log + watch model; both apps must be the same build |
| Bezel / crown volume | While casting, turn the watch's bezel (Galaxy Watch Classic) or crown slowly, then fast, then back | One volume step per notch (crown: smooth steps), a tick you can feel, the level shown at the edge; fast turns don't flood the TV; the list scrolls by bezel again once the cast ends | Watch model + Wear OS version, TV type, and whether each notch was one step |

## First start, settings search and accessibility

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| First-run tour | Clear the app's data, open CastApp from the launcher | Four short pages, **Skip** on each; not shown again after **Get started** or **Skip** | Screenshot + Android version |
| No tour elsewhere | With fresh data: start from the share sheet, a tile, the widget or an automation command | No tour; it waits for the first start from the launcher. Never on a TV | How it was started |
| Tour again | **Settings → Help & support → Show the tour** | The tour opens over the main screen | Screenshot |
| Settings search | Type "qualität", "qualitaet", "groesse" (German) or "wifi" (English) in the search field | Matching rows with their section; tapping one scrolls to it and highlights it | Screenshot + the word typed |
| TalkBack | Turn on TalkBack, cast a video, use the casting card, remote D-pad, guide grid and posters | Every button is named; the casting status is read out when it changes; switches say on/off | Screen recording with sound |
| Large text | Android font size and display size at the largest, then the main screen, settings, remote, guide, recordings | Nothing is cut off or overlaps; button rows wrap | Screenshot |
| Landscape and tablets | Turn the phone sideways on the remote; open settings, network, channels and recordings on a tablet | Remote: D-pad left, keys right, no scrolling for the D-pad. Lists stay at a readable width | Screenshot + screen size |
| D-pad focus on the TV | On CastApp TV, move with the remote through the home screen and the "Who may cast" dialog | The focused button always has a clear ring | Photo of the screen |

## Updates and install

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Check for updates | **Settings → Help & support → Check for updates** after a new release | Shows the new version with release notes; **Install** downloads and starts the installer | Debug log + the release tag |
| Update over the old app | Install a newer APK over an older one signed with the same key | Settings and remembered devices stay | Android says "App not installed": the signature differs; uninstall once (see "Signing key" in the README) |

## Reporting results

Please collect, for each device you tried: model, software version, the table row, **OK / not OK**, and the debug log for every *not OK*. One message with a list is perfect.
