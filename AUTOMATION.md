# Automating CastApp

Tasker, MacroDroid, Home Assistant, shell scripts and other apps can start and control casts with
plain Android intents.

Everything here is **off by default**. Turn it on in CastApp under **Settings > Automation > Let other
apps control CastApp**. Without that switch every call is answered with `disabled` and does nothing.

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

## Two ways in

| | Component | Use it for |
|---|---|---|
| Activity | `io.github.patschgoo.castapp/io.github.patschgoo.castapp.automation.AutomationActivity` | every action, including those that open a screen (mirror prompt, remote). Invisible. |
| Broadcast | `io.github.patschgoo.castapp/io.github.patschgoo.castapp.automation.AutomationReceiver` | controls and casts without opening anything |

The action names are the same for both: `io.github.patschgoo.castapp.automation.<NAME>`.

Android restricts what an app may do in the background. Because of that:

- Prefer the **activity** from Tasker/MacroDroid/Home Assistant. Starting an activity from the
  background needs the "Display over other apps" permission for that app on Android 10 and newer.
- Over a **broadcast**, controls for a running cast (stop, volume, next ...) always work. Starting a new
  cast (`CAST_URL`, `CAST_FILE`, `RECEIVE`) can be refused by Android 12+ unless CastApp is exempt from
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
| 4 | `unauthorized` | The secret is missing or wrong. |
| 5 | `invalid_request` | Unknown action, or a missing or invalid extra (the message says which). |
| 6 | `device_not_found` | No such TV, or no TV used yet. |
| 7 | `not_casting` | The action needs a running cast. |
| 8 | `needs_activity` | Android refuses this in the background; send it to the activity. |
| 9 | `failed` | Something else went wrong; see the message. |
| 10 | `unsupported` | Not possible for this cast (play/pause while mirroring, a quality it does not offer). |

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
