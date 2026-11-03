# Local development and verification

[AGENTS.md](../AGENTS.md) defines the default test-first workflow. Run the local gate before handing changes back; the GitHub Actions mirror reproduces it but is not a substitute.

## Setup

Install JDK 21, Android SDK platform 37, Build Tools 36.0.0 and Python 3.11+. Configure the SDK in untracked `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`. The Gradle wrapper pins Gradle and the Android plugin supplies Kotlin. Python runs `tools/verify`, the phone runner, metadata regeneration and the build-time icon generator using only the standard library. The checked-in wrapper JAR matches upstream Gradle 9.8.2; it is Apache 2.0 build tooling and is not bundled in the APK.

`tools/verify` uses the standard Gradle cache, respects `JAVA_HOME`, `GRADLE_USER_HOME` and `ANDROID_USER_HOME`, and uses the workstation's JDK 21 when `JAVA_HOME` is unset. Debug builds use the Android user directory default (`~/.android`), which holds the same debug key published APKs are signed with; set `ANDROID_USER_HOME` to override. Keys stay untracked.

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

Android lint treats warnings as errors and runs without a baseline. Fix findings rather than introducing a baseline or disabling checks globally. Narrow source suppressions document cases where a check does not apply, such as Kotlin-only view constructors and radar clicks dispatched through `GestureDetector`. The gate does not include detekt, ktlint, screenshot testing, managed emulators, or a release-signing check.

Reports are generated under `app/build/reports/` (JVM tests and lint), with JUnit XML under `app/build/test-results/`. APKs are under `app/build/outputs/apk/`. These generated directories are ignored by Git. Release builds are unsigned and are not installation artifacts; published APKs are debug builds signed with the public release key described below.

## Icon artwork

Edit SVGs in [`artwork/icons/`](../artwork/icons/README.md). Android `preBuild`
automatically regenerates their VectorDrawable XML and `IconAssets.kt`; manual
regeneration/checking uses `python3 tools/generate_icons.py [--check]`.
The converter uses Python's standard library, and the app uses native vectors
without a new runtime dependency. Its focused tests are
`python3 -m unittest discover -s tools/tests -p test_generate_icons.py`.

## Connected phone

Add your SDK's `platform-tools` directory to `PATH`, or pass `--adb /path/to/adb`. The phone must be unlocked. List devices using `adb devices`; pass `--serial` explicitly when more than one is connected. Both APKs must be signed with the same debug key and installed:

```sh
./tools/verify --gradle-only assembleDebug assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Fixture-backed quick checks: navigation, layout, forecast, lifecycle, radar,
# search and camera recreation. Public HTTP is forbidden.
python3 tools/verify_phone.py --serial SERIAL

# Maintained focused regression checks also cover notice/dialog lifecycle,
# corrupt radar frames, fixed-clock forecast states and table/layer UI.
python3 tools/verify_phone.py --serial SERIAL --focused

# Live providers/transport and persisted viewport; kept separate from fixtures.
python3 tools/verify_phone.py --serial SERIAL --live

# Select maintained phases directly (repeat --phase for additional phases).
python3 tools/verify_phone.py --serial SERIAL --phase fixture-forecast

# Optional Places integration: selects/saves test places; state is restored top-level.
python3 tools/verify_phone.py --serial SERIAL --places-integration

# Optional full integration: also changes test favourites and connectivity.
python3 tools/verify_phone.py --serial SERIAL --full
```

The runner rejects an instrumentation phase outside its registered suite,
focused-check and probe groups, and the instrumentation runner fails an unknown
phase instead of reporting `PASS`. `lifecycle` recreates the Activity with Places
selected and again with the Data & licences dialog open, then asserts the
replacement Activity's own view tree (Places shown, dialog dismissed, no leaked
window). It deliberately does not use the accessibility tree for the dialog case:
on API 33, destroying the focused dialog window detaches the instrumentation
accessibility connection for the rest of that run.

Diagnostic probes report measurements or
exercise gestures and are not part of suite gating; invoke one directly when
diagnosing a device, for example:

```sh
adb -s SERIAL shell am instrument -w -e phase recenter-probe app.drizziq.test/app.drizziq.PhoneExercise
adb -s SERIAL shell am instrument -w -e phase geocoding-probe app.drizziq.test/app.drizziq.PhoneExercise
adb -s SERIAL shell am instrument -w -e phase station-benchmark app.drizziq.test/app.drizziq.PhoneExercise
adb -s SERIAL shell am instrument -w -e phase radar-gestures app.drizziq.test/app.drizziq.PhoneExercise
```

A signature mismatch means the build used a different key. Find/use the original key; never uninstall the app to get past the mismatch. Debug builds, release APKs and published APKs share `~/.android/debug.keystore`.

Before the first instrumentation phase, the runner force-stops the app and snapshots the original selected place and favourites (`shared_prefs/places.xml` plus its `.bak`) and saved map camera (`shared_prefs/radar-viewport.xml` plus its `.bak`) through `run-as`. Backups are included because Android promotes a stale `.bak` to the main preference file on load. After every run, including a smoke failure before Places or full integration, it attempts each restoration action, reports cleanup failures alongside the original error, and reopens the app. This requires the installed debuggable debug APK; the runner never uninstalls or requests permissions. Full integration separately restores connectivity, including originally disabled radios.

The default and focused suites inject a fixed clock, isolated private preferences/files and fixture-backed network transport into the debug build. Unexpected addresses fail rather than reaching public services; release builds have no fixture override. Fixture-only data is removed during cleanup. `--live` runs the smoke phases against actual providers; Places and full integration explicitly use live data. Phone timing JSON records per-phase/total results and device, API, version, cache and network mode; build/install time is excluded and failures are retained in timestamped reports. The baseline on OnePlus 5T/API 33 with 1.4.1 and existing caches was 205.43 seconds for eight smoke plus seven focused live phases (see `tmp/verification/baseline.json`).

Smoke checks do not change connectivity or favourites; camera changes from map gestures and Places integration selections are restored from the snapshot. Places integration tests explicit candidate selection, provenance/favourite persistence and out-of-coverage behavior without changing permissions or device settings. Full offline integration asserts `STALE` with retained forecast values on Today and `Offline radar` on Radar; it does not require uppercase `OFFLINE` on Today. Full integration changes connectivity only when explicitly selected, then attempts all restoration actions even after failures. Its stopped-network phase enables connectivity while backgrounded and verifies no refresh until foreground return; the outer runner restores the initial radio settings.

Phone logs go to ignored `tmp/verification/` by default (`--reports PATH` overrides it). Full integration also captures the offline screen. The cleanup reopens Drizziq because instrumentation may return to the launcher. Before manual taps, verify the foreground package and current bounds. Keep visual checks brief; do not repeatedly capture screens to assess small aesthetic details.

## GitHub remote and releases

Codeberg is the source of truth. A second remote, `github`
(`git@github.com:psyke089/drizziq.git`), is pushed to directly; there is no push mirror. Push
`main` with `git push github main` and tags with `git push github --tags` after pushing to
Codeberg. GitHub runs `.github/workflows/ci.yml`:

- every push to `main` runs `./tools/verify`, the same gate as a local checkout without a phone;
- every `v*` tag, or a manual `workflow_dispatch`, additionally checks that the tag matches
  `versionName`, that the built APK reports that version and the public release signature, then
  publishes `drizziq-<version>.apk` and its `.sha256` as release assets.

Keep every workflow file in Codeberg and mirror it to GitHub. `tools/install-android-sdk.sh`
installs the platform and build tools the workflow needs, and is usable on a fresh machine.

Published APKs keep the signing identity of the earlier Codeberg releases: the key at
`~/.android/debug.keystore`, stored in the `DRIZZIQ_KEYSTORE_B64` repository secret. The workflow
fails rather than publishing a build signed with a runner-generated key, because that breaks
updates with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Rotating the key is a deliberate user-facing
break, not a routine change.

Push `main` and a release tag separately. If the tag's release run does not start, recover with
`gh workflow run ci.yml --ref v<version>`.

## Release screenshots

For every release with UI changes, capture fresh raw screenshots from the matching
installed app version. Use a public city preset and restore original phone places,
favourites and map-camera preferences after capture. Do not uninstall or reset data.

Store the single raw README capture as `docs/screenshot.png`, update its README
caption and attach the release captures directly to the Codeberg release. Embed
the uploaded attachments in the release notes and verify the README image path
and release image URLs resolve. Keep the release tag and APK unchanged for
screenshot-only corrections. Screenshots are documentation changes and do not
require repeating the app test suite.

## Growing the suite

Add tests alongside behavior changes rather than setting arbitrary coverage targets. Prioritize boundaries and failure paths in repository/cache policies, request ordering, missing DWD fields and local-day calculations. Use device tests for Activity recreation, navigation state, persistence and actual gestures. Keep live-service integration separate from fast deterministic checks. The GitHub workflow invokes the same local gate, so new checks are picked up without changing its definition.
