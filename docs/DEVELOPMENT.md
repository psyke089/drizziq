# Local development and verification

[AGENTS.md](../AGENTS.md) defines the default test-first workflow. There is no CI; run the local gate before handing changes back.

## Setup

Install JDK 21, Android SDK platform 37 and Build Tools 36.0.0. Configure the SDK in untracked `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`. The Gradle wrapper pins Gradle and the Android plugin supplies Kotlin.

`tools/verify` uses the standard Gradle cache, respects `JAVA_HOME`, `GRADLE_USER_HOME` and `ANDROID_USER_HOME`, and uses the workstation's JDK 21 when `JAVA_HOME` is unset. If the existing project debug key is present at `tmp/android/debug.keystore`, it selects that Android user directory so builds remain compatible with the connected phone. Keys stay untracked. On other machines, set these variables as needed.

The first run needs network access to download build, test, lint and formatter dependencies. Later runs can use `--offline`. Missing artifacts are setup failures, not failing tests. The formatter is a pinned build-only ktfmt dependency; it is not packaged with the app.

## Test first

For a behavior change, add a focused regression test, run it and confirm the expected assertion failure, implement the behavior, then refactor with tests green. New APIs may first fail compilation; add the minimum compiling shape and observe the behavioral failure as well. Existing behavior can be characterized by passing tests before extraction/refactoring.

Use JUnit 4, fixed timestamps and checked-in DWD fixtures for JVM tests. Put Android-specific interactions in device tests. Inject inputs or extract small pure rules rather than building an unused architecture. JVM Android stubs now fail on use instead of quietly returning default values.

```sh
# Full gate: Python tooling tests, formatting, JVM tests, Android lint,
# debug APK, unsigned release APK, and device-test APK. No phone required.
./tools/verify
./tools/verify --offline

# Iterate on a focused test; Gradle options are passed through.
./tools/verify --gradle-only testDebugUnitTest --tests 'app.drizziq.ReadingPolicyTest' --offline

# Apply Kotlin formatting explicitly; verification never reformats files.
./tools/verify --gradle-only formatKotlin
./tools/verify --gradle-only formatCheck
```

Kotlin/Gradle files use ktfmt's Kotlin style. `.editorconfig` supplies basic editor settings. Python 3.11+ tooling tests use only standard-library `unittest`; they fake ADB commands and never contact a device.

Android lint fails on new warnings and errors. `app/lint-baseline.xml` records existing findings when introducing this gate; see [LINT_DEBT.md](LINT_DEBT.md) for their scope. Do not regenerate the baseline to accept new warnings. Fix findings as relevant code changes, and remove resolved baseline entries deliberately. The gate does not include detekt, ktlint, screenshot testing, managed emulators, or a release-signing check.

Reports are generated under `app/build/reports/` (JVM tests and lint), with JUnit XML under `app/build/test-results/`. APKs are under `app/build/outputs/apk/`. These generated directories are ignored by Git. Release builds are unsigned and are not intended for installation over the debug app.

## Connected phone

Add your SDK's `platform-tools` directory to `PATH`, or pass `--adb /path/to/adb`. The phone must be unlocked. List devices using `adb devices`; pass `--serial` explicitly when more than one is connected. Both APKs must be signed with the same debug key and installed:

```sh
./tools/verify --gradle-only assembleDebug assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Quick checks: tabs, Activity recreation, forecast scrolling, coordinate-dialog cancel, radar gestures, timeline scrubbing, playback, and map-camera persistence across force-stop/relaunch and Activity recreation.
python3 tools/verify_phone.py --serial SERIAL

# Optional full integration: also changes test favourites and connectivity.
python3 tools/verify_phone.py --serial SERIAL --full
```

A signature mismatch means the build used a different key. Find/use the original key; never uninstall the app to get past the mismatch. On this workstation it is `tmp/android/debug.keystore`.

Smoke checks do not change connectivity, favourites or the selected place. They pan/zoom the map and force-stop/relaunch the app to verify viewport persistence, so the saved camera position can change. They still load real forecast data, so they require a usable network/current forecast and are separate from the deterministic JVM gate. The full suite creates/deletes labelled test favourites, changes the selected place, temporarily enables airplane mode and disables Wi-Fi/mobile data, then attempts to restore every original connectivity setting even if a check or another restoration fails. Do not run it on a personal phone implicitly. Favourites/selected-place changes are not automatically rolled back.

Phone logs go to ignored `tmp/verification/` by default (`--reports PATH` overrides it). Full integration also captures the offline screen. The runner reopens Drizziq after successful completion because instrumentation may return to the launcher. Before manual taps, verify the foreground package and current bounds. Keep visual checks brief; do not repeatedly capture screens to assess small aesthetic details.

## Growing the suite

Add tests alongside behavior changes rather than setting arbitrary coverage targets. Prioritize boundaries and failure paths in repository/cache policies, request ordering, missing DWD fields and local-day calculations. Use device tests for Activity recreation, navigation state, persistence and actual gestures. Keep live-service integration separate from fast deterministic checks. CI can later invoke the same local gate without changing its definition.
