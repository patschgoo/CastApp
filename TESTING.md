# Real-device test checklist

CastApp talks to many kinds of devices that no unit test can reach. This list is for the first rounds on real hardware and for every release. Do not try everything: pick the devices you own, and write down the result (OK / not OK / not tried) next to each item.

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

## NAS, Plex, Jellyfin and network shares

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Windows / Samba share | Add the share in CastApp, browse, play a video | The list loads and the video plays | Debug log + server type (Windows, Synology, ...) and the SMB version if you know it |
| Media server | Add Plex or Jellyfin, browse, play | Library appears; playback starts; resume position is remembered | Debug log + server and version (no passwords: the log hides them, but check) |
| Cloud folder | Add a cloud folder | Files list and play | Debug log + provider |
| Away from home | Open a NAS over your VPN or a Tailscale address | Works as at home | Debug log + how you connect |
| Queue | Pick several files | Play list; reorder, remove, shuffle, repeat work | Debug log |
| Continue watching | Stop a video halfway, open it again | Resumes where you stopped | Debug log |

## IPTV and the programme guide

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| M3U list | Add an M3U address | Channels appear, grouped | Debug log + whether the list works in another player (never send the full address) |
| Play a channel | Cast a channel to each device type you own | Plays; channel switch works | Debug log + device + channel format if known |
| Radio | Cast a radio station | Plays | Debug log |
| Guide | Add an XMLTV address | Programme guide shows now and next | Debug log |
| Catch-up | Try it on a channel whose provider offers it | Plays an earlier programme | Debug log |

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
| Several screens | Cast to two devices (for example a TV and a speaker) | Both play; each can be stopped separately | Debug log + both devices |
| Widget and tiles | Add the home-screen widget; use the **Cast screen** tile | Quick cast and stop work; long-press opens the app | Debug log |

## Updates and install

| Feature | Steps | Expected result | If it fails |
|---|---|---|---|
| Check for updates | **Settings → Help & support → Check for updates** after a new release | Shows the new version with release notes; **Install** downloads and starts the installer | Debug log + the release tag |
| Update over the old app | Install a newer APK over an older one signed with the same key | Settings and remembered devices stay | Android says "App not installed": the signature differs; uninstall once (see "Signing key" in the README) |

## Reporting results

Please collect, for each device you tried: model, software version, the table row, **OK / not OK**, and the debug log for every *not OK*. One message with a list is perfect.
