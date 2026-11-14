# Changelog

## 0.5.0

- Redesigned the app with a Material 3 colour scheme: dynamic colour on Android 12 and later, and a teal fallback palette for light and dark themes.
- Replaced the long tab list with bottom navigation for Dashboard, Insights, Gesture lab, and Settings.
- Made the Dashboard a glanceable home: watch connection and battery, today’s steps with a goal ring, latest and resting heart rate, last sync time, and one Sync button.
- Grouped settings into Alerts & sync, Permissions, Battery optimisation, Age, Laptop companion, Raise-to-wake, and Advanced.
- Moved the BLE scanner and the raw log under Advanced. Both stay scrollable.
- Friendlier empty, loading, and error copy, including what to do when CB SmartFit is holding the watch.
- Versioned the debug APK (`watch-coach-v0.5.0-debug.apk`) and added a GitHub Actions workflow that builds it and, on a `v*` tag, publishes a release.

## 0.4

- Optional hourly background sync (WorkManager, connected-device foreground service on Android 14).
- Insights: night resting heart rate, 7- and 30-day trends, adaptive step goal, hour heatmap, and heart-rate zones.
- Monday–Sunday weekly report that can be shared as an image, PDF, or text, plus an optional Sunday 18:00 notification.
- Gesture lab for documented GloryFit `D1` / `C4` events, with actions for apps, intents, media, and the laptop companion.
- Python laptop companion (`desktop/watch_coach_companion.py`) for keystrokes and mouse movement.
- Phone-sensor air mouse as a fallback. The watch does not stream raw motion.
- Raise-to-wake write. It sends the whole `A9` user-profile packet.

## 0.3

- Cleaned up the raw log: empty all-zero `34F2` frames collapse, and repeated live-heart-rate lines collapse unless verbose mode is on.
- Post-sync summary card (firmware, battery, step and heart-rate ranges, sleep, duration, errors).
- Fixed a write/await race so a command is not treated as finished before the watch replies.

## 0.2

- Direct GloryFit / UTE BLE sync: time, battery, firmware, step history, heart-rate history, and sleep.
- Local Room storage used by the dashboard when Health Connect is empty.
- Optional live heart-rate read.

## 0.1

- Health Connect reader for steps, heart rate, resting heart rate, sleep, distance, and calories, plus the permissions-rationale activity.
- BLE scanner that lists nearby devices and GATT services and highlights likely Everest / CB watches.
