# Android HA Android Timer Bridge

An Android app for a Pixel Tablet that notices when a Google Assistant / Clock **timer**
finishes or an **alarm** goes off, and pushes it to Home Assistant — plus the Home Assistant
integration that discovers the tablet and receives those events.

App package: `com.camurphy.ha_android_timer_bridge`
Home Assistant integration domain: `ha_android_timer_bridge`

## How it works

Hub Mode timers have no API, but anything that rings posts a notification. The app runs a
`NotificationListenerService`, works out whether it was a timer or an alarm, recovers the
label, and POSTs it to a webhook.

Home Assistant finds the tablet itself: the app advertises `_hatimerbridge._tcp` over mDNS —
that service name keeps the project's original spelling, because DNS-SD allows only 15
characters and `haandroidtimerbridge` is 20 — the integration picks that up as a discovered
device, you confirm with the code shown on the tablet, and Home Assistant hands the tablet
the webhook to post to. Nothing is typed in twice, and the tablet never holds a Home
Assistant token.

```
Pixel Tablet                                Home Assistant
  |                                             |
  |-- mDNS _hatimerbridge._tcp ---------------->|  discovered device
  |<-- POST /pair {code, webhook_url} ----------|  you confirm the code
  |                                             |
  |-- POST webhook {kind, timer_name, raw} ---->|  event + sensor entities
```

## Entities

Each paired tablet becomes one device with four entities:

| Entity | What it does |
| --- | --- |
| `event.<tablet>_timer` | Fires on `timer_finished`, with the timer's name in its attributes |
| `event.<tablet>_alarm` | Fires on `alarm_fired` |
| `sensor.<tablet>_last_timer` | Name of the most recent timer |
| `sensor.<tablet>_last_alarm` | Name of the most recent alarm |

The bus events `ha_android_timer_bridge_timer_finished` and `ha_android_timer_bridge_alarm_fired` are also
fired, if you would rather trigger on those.

A blueprint is included at `blueprints/automation/ha_android_timer_bridge/` for the common case of
notifying phones.

## Setup

1. Copy `custom_components/ha_android_timer_bridge/` into your Home Assistant `config/` directory
   and restart. (The repo is HACS-compatible if you would rather add it that way.)
2. Install the APK on the tablet and open the app.
3. Tap **Open notification access settings** and switch the app on. This is the one
   permission Android will not let an app grant itself, and nothing works without it.
4. In Home Assistant, go to Settings → Devices & services. The tablet should be waiting as a
   discovered device. Confirm it and type the pairing code from the app's screen. If mDNS
   does not get through, use **Add integration → HA Android Timer Bridge** and enter the address the
   app shows.
5. Set a short timer and watch the log at the bottom of the app.

## What keeps the listener running

Nothing — and that is the point. `NotificationListenerService` is not started by the app;
Android binds it once notification access is granted, and the system owns its lifecycle:

- It is rebound automatically after a reboot, after a crash, and after the process is killed
  for memory.
- The binding keeps the app process at a high enough priority that it is rarely killed.
- The pairing server and mDNS advertisement are started from that service's `onCreate`, so
  they inherit the same lifecycle for free. There is no foreground service and no persistent
  notification.

The failure modes that do exist:

- **Force-stopping the app** from Settings stops the listener until the app is opened again.
- **After an app update** a listener is occasionally left unbound. The app calls
  `requestRebind()` whenever it notices access is granted but nothing is connected, and the
  status line on the main screen tells you which state you are in.
- **Doze** can delay the outgoing POST when the tablet is on battery. It does not apply while
  charging, so a docked tablet is unaffected; the app offers a battery-optimisation
  exemption for the rest of the time.
- A timer that fires during the brief window while the listener is rebinding is missed —
  `onListenerConnected` does not replay notifications.

## What Google Clock actually posts

Worth recording, because it drives the detection rules. Observed on an Android 15 image:

| | Firing timer | Firing alarm | Running timer | Upcoming alarm |
| --- | --- | --- | --- | --- |
| channel | `Firing` | `Firing` | `Timers` | `Upcoming Alarms` |
| category | `alarm` | `alarm` | none | none |
| title / text extras | **all null** | `Sunrise` / `Thu 6:52 PM • Swipe to stop` | **all null** | `Upcoming alarm` |
| custom layout text | `00:00`, `Pasta` | none | `00:05`, `Pasta` | none |
| buttons | `Stop`, `Add 1 min` | `Snooze`, `Stop` | `Pause`, `Add 1 min` | `Dismiss alarm` |
| notification id | `2147483640` | `2147483645` | `2147483641` | `1` |

### Hub Mode is different again

A timer set through Hub Mode, with the tablet docked, posts **only the notification that
fires** — on a `Timers v2` channel, with no full-screen intent:

| | Normal timer | Hub Mode timer |
| --- | --- | --- |
| countdown notification | yes, `Pause` / Add 1 min | **none** |
| firing notification | channel `Firing` | channel `Timers v2` |
| label | present | present |
| length | derivable | **not derivable** |

Names come through fine — an unnamed timer is labelled `Time's up`, which is Clock's
placeholder and is stripped rather than reported.

The **length cannot be recovered for Hub Mode timers**. It is measured from the countdown
notification, and Hub Mode never posts one; the timer lives on the hub's screen instead. The
firing notification carries `zeroElapsedRealtime`, which is when the timer reached zero, not
how long it ran, and `progress`/`progressMax` are both `0`. Timers set the normal way still
report their length.

Two things follow from this:

- **A firing timer sets none of the standard text extras.** The label only exists inside a
  custom `RemoteViews` layout, so `RemoteViewsScraper` inflates it and reads the text out.
  Any matcher that only looks at title/text will never see the name.
- **Alarms and timers ring on the same channel with the same category.** The buttons are what
  separate them: only an alarm offers *Snooze*, only a timer offers *Add 1 min*. That is the
  primary test, with the clock face (`00:00` counting vs `7:00 AM`) as the fallback.

## Tuning the detection

Timer notifications differ between Android builds, so the app logs everything it sees:
package, channel, category, every text field, the recovered layout text, the buttons, and
whether it matched. If a finished timer shows as `ignored`:

- Check the package is in **Watched packages**.
- If the package is right but the rules do not fire, tick **Forward everything from watched
  packages** and filter on the raw fields in Home Assistant instead.
- **Send this to HA** on any log row replays it, so you can test the Home Assistant side
  without waiting for a real timer.

The rules live in `TimerMatcher.kt`, with the table above encoded as fixtures in
`TimerMatcherTest.kt` — run `./gradlew :app:testDebugUnitTest`.

## Payload

```json
{
  "event": "timer_finished",
  "kind": "timer",
  "kind_reason": "notification offers a \"add 1 min\" button",
  "device": "Pixel Tablet",
  "timer_name": "Pasta",
  "timer_name_source": "viewText",
  "duration": null,
  "match_reason": "posted on the \"Firing\" channel",
  "is_test": false,
  "fired_at_ms": 1787820473455,
  "raw": { "package": "...", "channel_id": "Firing", "view_texts": ["00:00", "Pasta"],
           "action_titles": ["Stop", "Add 1 min"], "...": "every other field" }
}
```

`timer_name` is null for an unnamed timer, and for "2 timers expired" where Clock gives no
single name. `raw` is always included so an automation can fall back to the untouched
notification content.

## Building

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. There is no native code,
so the single APK covers every ABI.

Publish it under a **versioned filename** — `ha-android-timer-bridge-<version>.apk`.
camurphy.com sits behind Cloudflare, which caches by URL, so overwriting a filename in
place leaves the old build being served to anyone who fetched it recently.

## Things worth knowing

- **The signing key must not be lost.** It lives at
  `/Volumes/Media/GDriveCam/keys/ha-android-timer-bridge.jks`, with its passwords in
  `ha-android-timer-bridge.properties` beside it, on the volume that reaches Backblaze.
  Android identifies an app by its signature, so if that keystore goes missing the
  installed app can never be updated in place again — only uninstalled and reinstalled,
  losing its pairing and settings. The build reads that properties file automatically;
  override with `-PsigningProperties=/path/to/file`, and if it is missing the build falls
  back to the debug key with a loud warning.
- Do not ship a debug-signed build. The debug certificate is publicly known and Play
  Protect refuses the install outright with "App blocked to protect your device".
- The pairing code is what stops anything else on the LAN repointing the tablet at another
  webhook. It is only ever shown on the tablet's screen. Regenerate it from the app.
- The pairing server listens on port 8127 and only accepts a webhook URL — it exposes no
  notification content.
- A ringing timer re-posts itself repeatedly. The app forwards it once and suppresses
  repeats until the notification is dismissed, so an un-attended timer will not notify your
  phones every minute.
