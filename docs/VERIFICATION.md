# Verification — 2026-10-22

## Build

The Gradle wrapper completed successfully with JDK 21, Gradle 9.6.0, AGP 9.4.1, Android SDK 37 and Build Tools 36.0.0:

```sh
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
```

[Build output](verification/build.txt), [seven passing JVM tests](verification/unit-tests.xml), and [machine-readable result / APK SHA-256](verification/result.json) are committed. Lint completed without errors. The minimum SDK is 24; the physical-device exercise used API 33. The app was installed with `adb install -r` and launched on the USB-connected **OnePlus 5T (ONEPLUS A5010), Android 13**.

The data tests cover coordinate boundaries and non-finite input, genuine DWD MOSMIX decoding, missing values, the absence of invented relative humidity, a 25-hour local day, ODIM uint32 pixels and nodata, the declared radar projection and inverse, actual forecast-frame validity, and truncated archives.

## Physical-device exercise

The recorded native instrumentation phases all returned `result=PASS`:

| Phase | Observed result | Raw log |
| --- | --- | --- |
| Main | Live Munich forecast; invalid latitude 91 and longitude 181 rejected; Berlin coordinates produce live Berlin forecast; labelled favourites saved; radar pans and changes zoom under a real two-finger pinch; button zoom and recenter work; timeline reaches the actual +120-minute frame; playback loops from forecast to observations; Munich favourite can be selected | [main](verification/device-main.txt) |
| Forecast | Scroll through the forecast; all seven days are reachable horizontally; hourly detail and the issue / validity / received footer are reachable; unavailable humidity is explicitly omitted | [forecast](verification/device-forecast.txt) |
| Persistence | After `am force-stop` and a new process, both saved favourites still exist; Berlin can be selected and deleted; Munich can be selected again | [persistence](verification/device-persistence.txt) |
| Offline | With airplane mode on and both Wi-Fi and mobile data disabled, a full restart retains the successful forecast and radar with explicit stale/offline labels; cached radar playback works | [offline](verification/device-offline.txt) |
| Restore | Restoring the original radio settings produces a live forecast again | [restore](verification/device-restore.txt) |

The phone's initial airplane / Wi-Fi / mobile-data settings were `off / on / on`; these settings were restored. The final foreground app is the Munich forecast, with a saved Munich favourite.

These are historical results from 2026-10-22. For current commands and test-first development, see [DEVELOPMENT.md](DEVELOPMENT.md). Reproduce the full integration exercise after building and installing both APKs:

```sh
python3 tools/verify_phone.py --serial YOUR_ADB_SERIAL --full
```

The script records actual instrumentation output, captures the offline screen, and restores the original radio settings in a `finally` block. It deliberately creates and deletes labelled test favourites. A network connection and an unlocked phone are needed for the live phases.

## Visual checks and screenshot provenance

- [README screenshot](drizziq-v1.0.0-screenshot.png): raw `adb exec-out screencap -p` capture of the live Munich forecast on the phone.
- [Radar screenshot](drizziq-v1.0.0-radar.png): raw capture of the live DWD radar, selected Munich marker, loaded OpenStreetMap basemap, attribution, timeline, NOW marker and legend.
- [Airplane-mode screenshot](verification/drizziq-v1.0.0-offline.png): raw capture showing the retained Munich forecast with stale/offline labels and the airplane-mode system indicator.

The forecast and radar screenshots were visually inspected at native phone aspect ratio. No readings, map frames, timestamps or screenshot UI were composited or mocked. README badges are local SVG assets created in the repository. The README is rendered through Codeberg's Markdown API and its image/badge assets are checked after push.

## Data scope

The live Munich forecast uses DWD point `10865` (München Stadt), with the point's elevation and distance explicitly shown. The radar retrieves DWD RV's available five-minute data: one hour of observations plus up to two hours of forecast, normally 37 frames. Global coordinates are accepted; radar coverage outside Germany and its surroundings is explicitly unavailable. No API-24 physical device was connected, so API-24 compatibility was checked by compilation, desugaring and Android lint rather than a second device run.
