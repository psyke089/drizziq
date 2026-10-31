# Verification — Drizziq 1.3.0

2026-10-31. Version 1.3.0, versionCode 4, minimum API 24.

## Local gate

`./tools/verify --offline` passed: 79 JVM tests, 23 Python tooling tests,
Kotlin formatting, Android lint and debug/release/device-test APK builds.
There are no new lint findings; eight reviewed baseline findings remain.
The SVG migration removed four obsolete drawing-allocation baseline entries.

New deterministic coverage checks local-day/DST hour selection, archived DWD
backfill without replacing latest data or mixing stations, pressure conversion,
solar reference cases, condition/intensity selection, SVG conversion and generated
asset synchronization. The icon migration's focused native check successfully
inflated and rendered all 63 vectors; its source contact sheet was inspected once.

## Phone

All eight default smoke phases passed on the connected OnePlus 5T
(`ONEPLUS A5010`, Android 13 / API 33): navigation, layout, lifecycle,
Today/Week forecasting, radar gestures/playback, city search/cancel and viewport
save/restore across force-stop and Activity recreation. Existing app data was
retained. This run did not change favourites, selections, connectivity or
permissions; viewport checks deliberately move the map.

## Downloadable APK

The existing release's debug-build format and public signing certificate are
retained. The published APK is signed separately from the phone development key;
no uninstall or data reset is used.

- File: `drizziq-1.3.0.apk`, 2,817,271 bytes.
- SHA-256: `77ed33a3ca2467508f1ba6d3e282e993c42228f8e92d553e63ce4012229c29b6`.
- Certificate SHA-256: `cde2781a239ab2b876e06f66b2654196979ba3f1d619dbb660a8d32601df1988`.
- `apksigner verify` passes v2/v3 verification; APK metadata confirms version 1.3.0,
  versionCode 4, minimum API 24 and target API 37.

Raw build and phone logs remain in ignored `tmp/release-1.3.0/`.

## Screenshots

The screenshot follow-up adds fresh Today, Week, Radar and Places captures from
the installed 1.3.0 app to the release and updates the README to the same images.
See [capture provenance](screenshots/1.3.0/README.md). It changes documentation and
release images only; the existing release tag and verified APK are retained.
