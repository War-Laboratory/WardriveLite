# Wardrive Lite

An original Android wardriving and detection app (Android 10+), inspired by the feature set of Wardrive Go.

## What it does
- Logs Wi‑Fi, Bluetooth LE and cell towers (LTE/5G NR/GSM/WCDMA) and tags them with GPS
- Raises alerts and notifications for:
  - Flock Safety ALPR cameras (by name; add MAC prefixes in `Detector.kt`)
  - Axon body cams/Tasers (MAC prefix 00:25:DF) and police in-car routers
  - Hidden/IoT camera hotspots
  - AirTags / Find My, Samsung SmartTag and Tile trackers. A tracker is flagged **FOLLOWING YOU** if it's still with you 10+ minutes later and 500+ m from where you first saw it
  - Flipper Zero and smart glasses (Ray-Ban/Meta)
- Map tab (OpenStreetMap) with every located device
- Writes a **WiGLE CSV (v1.6)** log; tap *Export CSV* to share it, then upload it at wigle.net
- Keeps scanning in the background as a foreground service with a Stop button in the notification

## Build
1. Install Android Studio and open this folder. Let Gradle sync.
2. Plug in your phone with USB debugging on and press **Run**.
   Or from a terminal: `./gradlew assembleDebug`, which writes `app/build/outputs/apk/debug/app-debug.apk`.

## Tips
- Turn off **Wi‑Fi scan throttling** in Developer options. Otherwise Android only allows about 4 Wi‑Fi scans every 2 minutes.
- Keep Bluetooth and Location turned on.
- Detection rules are heuristics. Edit `Detector.kt` to add signatures you trust.

The app only listens to what devices broadcast. It never connects to them or interferes with them.
