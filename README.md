# Watch Coach

Watch Coach is an Android companion for the **Crossbeats Everest 2.0** (and similar GloryFit / UTE watches). The watch runs a closed Realtek RTOS with no official SDK, so the app lives on the phone and talks to the watch directly over Bluetooth LE.

Version **0.5.0** (`versionCode` 5). `minSdk` 26, `compileSdk` / `targetSdk` 36, application id `com.watchcoach.app`. The debug build uses `com.watchcoach.app.debug`.

Tested on a **OnePlus Nord 2T**. The sample watch address used during development is `78:02:B7:55:68:40`.

Licensed under the **GNU Affero General Public License v3.0**. See `LICENSE` and `NOTICE`. The GloryFit command set is adapted from [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (also AGPL-3.0).

## What it does

CB SmartFit often cannot sync this watch to Health Connect. Watch Coach connects over BLE, asks the watch for steps, heart rate, and sleep, and stores them on the phone. The dashboard reads that local store, and Health Connect when it actually has data.

## Features

- **Manual BLE sync.** One Sync button connects to the saved watch, sets the time, and reads battery, firmware, steps, heart-rate history, and sleep.
- **Hourly background sync.** An optional WorkManager job repeats that same sync about once an hour, then disconnects. On Android 14 it runs as a connected-device foreground service.
- **Insights.** Night resting heart rate, 7- and 30-day trends, an adaptive step goal, an hour-of-day heatmap, and heart-rate zones from `220 − age`.
- **Weekly report.** A Monday–Sunday summary you can share as an image, PDF, or text. An optional notification posts on Sunday at 18:00 local time.
- **Gesture lab.** Listens for the watch events Gadgetbridge documents (`D1` music / find-phone / hang-up, `C4` camera) and can open an app, fire an intent, control phone media, or send a key to the laptop.
- **Laptop companion.** `desktop/watch_coach_companion.py` presses keys and moves the mouse on the same Wi-Fi network.
- **Phone-sensor air mouse.** A fallback that tilts the *phone*. The watch does not stream raw motion.
- **Raise-to-wake.** Turns the watch display’s raise-to-wake bit on or off. That write replaces the whole user-profile packet.

Health Connect read (and optional write) is still there, including the permissions-rationale screen Health Connect requires. Inactivity and elevated-resting-heart-rate notifications are configured under Settings.

## Install the APK

The debug APK is signed with the Android debug key. The file name includes the version, for example `watch-coach-v0.5.0-debug.apk`.

1. On the phone: **Settings → About device**, tap **Build number** seven times, then **Developer options → USB debugging**.
2. Connect a data cable and accept the RSA prompt.
3. From a machine with [platform-tools](https://developer.android.com/tools/releases/platform-tools):

```bash
adb devices
adb install -r watch-coach-v0.5.0-debug.apk
```

If `adb` does not see the Nord 2T, try another cable, set **Default USB configuration → File transfer**, or run `adb kill-server && adb start-server`.

Before the first sync, force-stop **CB SmartFit** (Settings → Apps → CB SmartFit → Force stop) and leave it stopped. The watch accepts one BLE connection.

GitHub Releases (see below) attach this same debug APK. You can also build it yourself; the file lands at `app/build/outputs/apk/debug/watch-coach-v0.5.0-debug.apk`.

## Build

JDK 17 and Android SDK platform 36 with build-tools 36.0.0.

```bash
export JAVA_HOME=/path/to/jdk-17
# optional: export ANDROID_HOME=/path/to/android-sdk
chmod +x gradlew
./gradlew assembleDebug
```

Android Studio (a release that supports AGP 8.13 and compile SDK 36) can open this folder. It writes `local.properties` with the SDK path. Do not commit that file, keystores, or build outputs.

## Laptop companion

On the computer, from `desktop/`:

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python watch_coach_companion.py --token YOUR_TOKEN --port 8765
```

The token is shown in the app under **Settings → Laptop companion**. Put the computer’s LAN IP and port there, tap **Save**, then **Ping laptop**.

**On Linux this needs an X11/Xorg session, not Wayland.** pynput cannot inject keys or move the pointer under Wayland. Log out, choose **Ubuntu on Xorg** (or another X11 session) on the login screen, and confirm `echo $XDG_SESSION_TYPE` prints `x11` before you start the script. The script warns if it detects Wayland.

macOS needs Accessibility permission for pynput. Windows may ask to allow port 8765. The server is plain HTTP on the LAN only.

Phone-sensor air mouse is on the Gesture lab screen and uses the address saved in Settings. It moves the pointer from the phone’s gyroscope.

## Screens

Bottom navigation:

- **Dashboard** — connection and battery, today’s steps against the step goal, latest and resting heart rate, last sync, and Sync.
- **Insights** — trends, heatmap, zones, and the weekly report.
- **Gestures** — listen, map `D1`/`C4` events, and the phone-sensor air mouse.
- **Settings** — alerts and hourly sync, permissions, battery optimisation, age, companion IP and token, raise-to-wake, and Advanced.

**Advanced** (inside Settings) opens the BLE scanner and the raw log. Both scroll, including while a watch is connected.

## Known limits

- **Sleep sync is unverified** on this Everest 2.0. The app still requests it. The summary says “none / unsupported” when the watch does not answer with stages.
- **The watch sends no raw motion and no wrist-raise event.** Gesture lab only reacts to the documented `D1` and `C4` packets. Anything else is logged as unknown. Air mouse uses the phone.
- **Raise-to-wake rewrites the whole user-profile packet** (Gadgetbridge’s 19-byte `A9`: height, weight, timeout, step goal, heart-rate alerts, age, gender, units, and the raise-to-wake byte). It is not a dedicated command. Confirm the values in Settings before sending it.
- There is no official Everest 2.0 SDK, so watch faces and firmware updates stay in CB SmartFit.
- Distance and calories from a BLE sync are phone-side estimates (`km ≈ steps × height_cm × 0.418 / 100000` with the saved height, default 170 cm; kcal ≈ steps × 70 × 0.0005). Classic `B2` bytes 8–17 are walk/run counts, not distance or calories.
- UTE `EB` step fields and `EC` sleep are best-effort. Classic `B2` / `F7` / `31` follow Gadgetbridge.
- A normal sync does not send `E5 00` to stop live heart rate. `E5 00` is sent only after a live-heart-rate session this app started.
- CB SmartFit’s Health Connect toggle is unreliable on some phones. If Health Connect is empty, the dashboard uses the watch sync.
- Recovery score is a simple heuristic (60% sleep on a 4–8 hour scale, 40% resting heart rate versus the 7-day baseline), not a medical assessment.
- Debug and release application ids differ (`com.watchcoach.app.debug` vs `com.watchcoach.app`). Uninstall one before installing the other if the signing keys differ.

## Cut a release

The debug APK is enough for now. GitHub Actions builds it on every push and pull request. Pushing a tag `v*` (for example `v0.5.0`) builds that same APK and publishes a GitHub Release. The release notes are the matching `CHANGELOG.md` section, and the versioned APK is attached.

To publish a version:

1. In `app/build.gradle.kts`, bump `versionCode` and `appVersionName`. The APK name follows `appVersionName` (`watch-coach-v0.5.0-debug.apk`).
2. Add a `## x.y.z` section at the top of `CHANGELOG.md`. The heading must match `appVersionName` (no `v`).
3. Merge that commit to `main`.
4. Tag the merge commit and push the tag:

```bash
git checkout main
git pull
git tag v0.5.0
git push origin v0.5.0
```

The tag name is `v` plus `versionName`. Open the Actions run, then the Release, and install the attached APK.

For **0.5.0** specifically: merge the pull request, then tag `v0.5.0` on `main` and push that tag. Do not create the release by hand; the workflow attaches the APK and the changelog section.

## Project layout

```
app/src/main/java/com/watchcoach/app/
  ui/                   Compose screens (dashboard, insights, gestures, settings)
  ble/gloryfit/         GATT client, protocol, parsers
  data/                 Room, Health Connect, DataStore
  work/                 Hourly sync, weekly report, health alerts
  gestures/             Watch-event service, actions, phone air mouse
desktop/                Python companion
```

## Watch Sync commands

Nothing is written until you tap **Sync**, **Read live heart rate**, start Gesture lab listening, or confirm a raise-to-wake write.

On connect the client requests MTU 247 and enables notifications on `0x33F2`. `0x34F2` notifications are enabled only for the sleep window or a UTE fallback, because this watch otherwise floods empty frames. All-zero frames are dropped before they reach the parsers.

**Classic GloryFit (as in Gadgetbridge):**

| Direction | Packet | Meaning |
|---|---|---|
| TX `33F1` | `A1` | firmware / version |
| TX `33F1` | `A2` | battery (reply `A2 <pct> [01=charging]`) |
| TX `33F1` | `A3 yyyy MM dd HH mm ss` | set phone time |
| TX `33F1` | `B2 FA` | hourly step history. End `B2 FD` |
| TX `33F1` | `F7 FA` | heart-rate history. End `F7 FD` |
| TX `33F1` | `31 01` | sleep; stages on `34F2` as `32` packets, end `31 02` |
| RX `33F2` | `B1 …` | realtime steps, if the watch sends them |

**UTE fallback, only if the classic fetch returns nothing** (Gadgetbridge issue #5505). `EB` step field mapping and `EC` sleep layout are speculative.

**Live heart rate**, only from the Sync details button: TX `D6 02`, wait, `E5 11`, later `E5 00`. Unsolicited `E5 11` samples during a normal sync are shown and stored. A normal sync does not send `E5 00`.

**Raise-to-wake only**, after the confirmation dialog: the 19-byte `A9` user-info packet. Factory reset, firmware, watch faces, and other settings commands are never sent.

## Recovery score

```
score = 0.6 * sleepScore + 0.4 * heartScore
```

Sleep is 100 at 8 hours or more and 0 at 4 hours or less, linear in between. Heart rate is 100 at or below the 7-day resting baseline and 0 at 15 bpm above, linear in between. If only one input exists, the score is that input. The alert for elevated resting heart rate uses its own threshold (default +10 bpm).

## License

Watch Coach is licensed under the GNU Affero General Public License v3.0 (`LICENSE`, `NOTICE`).

Watch Sync’s opcodes, handshake, and classic record parsers are adapted from [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)’s GloryFit support (`service/devices/gloryfit/`, José Rebelo and other Freeyourgadget contributors), also AGPL-3.0. The UTE dialect follows [Gadgetbridge issue #5505](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/5505) and the [Gadgetbridge-tools GloryFit dissector](https://codeberg.org/Freeyourgadget/Gadgetbridge-tools/src/branch/main/gloryfit).
