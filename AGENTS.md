# Working on Drizziq

## Project and environment

- Native Kotlin Android app with platform Views, one Activity, a Repository, and pure data/parsing code. There are no ViewModels, use cases, Flows, coroutines, or architecture-test framework. Keep business rules testable without Android; introduce abstractions only when needed by a real behavior/test.
- Use JDK 21, Android SDK platform 37 and Build Tools 36.0.0. Use the checked-in Gradle wrapper. `local.properties` or `ANDROID_HOME` supplies the SDK path.
- This workstation has JDK 21 at `/usr/lib/jvm/java-21-openjdk` and SDK at `/home/psyke/Android/Sdk`. Keep machine-specific paths out of tracked build configuration.
- `tools/verify` supplies local environment defaults; caller-provided environment variables take precedence. `--offline` requires dependencies to have been downloaded previously. Report missing dependencies separately from test failures.
- GitHub Actions mirrors every push to `main` and every `v*` tag through the Codeberg push mirror; the workflow runs `./tools/verify` and publishes release APKs. Local verification remains the required gate, so do not rely on the remote run to catch failures. See `docs/DEVELOPMENT.md` for the mirror, tag and signing rules.

## Product design, assets and data

- Visual language: dark, sci-fi and futuristic. Keep the interface consistent with the existing HUD palette and restrained typography/pictograms.
- Use a single dark color scheme. Do not add a light mode or switch palettes based on the system theme.
- Generate visual assets locally or use assets explicitly supplied by the user. Icon artwork belongs in `artwork/icons/` as editable SVG, the source of truth for generated Android vectors. Builds run `tools/generate_icons.py`; edit SVGs rather than the generated XML or `IconAssets.kt`. Keep icon shapes out of Canvas code; runtime code may position, tint and rotate the compiled vectors. Dynamic charts/maps still use Canvas. See `artwork/icons/README.md` for supported SVG features and intensity conventions. Do not hunt for icons online or download icon packs/stock assets.
- App data sources must be free and publicly available. Any source requiring an account or API key needs explicit user approval before integration, even if it has a free tier. Do not add an account/API-key requirement or obtain credentials implicitly.

## Test-first workflow (default for every behavior change)

1. Write or extend the smallest meaningful test of the requested behavior before changing production code. For bugs, reproduce the failure with a regression test.
2. Run that test and observe the expected failure. A compilation/dependency/environment failure does not prove a behavioral regression. For a new API, confirm the missing API failure, then make it compile and observe the failed assertion before implementing the behavior.
3. Implement the smallest change that makes the test pass, then refactor while keeping tests green. Proceed through these steps autonomously; do not wait for separate implementation permission unless the user explicitly asks for tests only.
4. Run the focused tests while iterating, then the complete local verification command once the change is ready. Run affected phone checks for UI/lifecycle behavior.
5. Report what changed, the red/green evidence, the checks run, and any checks that remain blocked. Keep visual verification brief; the user can assess appearance at a glance.

Documentation and formatting-only changes do not need artificial behavior tests. Test verification scripts through their command behavior with fake subprocesses; never toggle a real phone's radios to test the runner itself.

## Commands

- Full local gate (formatting, Python tooling tests, JVM tests, Android lint, debug/release APKs and device-test APK): `./tools/verify`
- Cached dependencies only: `./tools/verify --offline`
- Focused JVM test: `./tools/verify --gradle-only testDebugUnitTest --tests 'app.drizziq.ReadingPolicyTest'`
- Formatting check / apply: `./tools/verify --gradle-only formatCheck` / `./tools/verify --gradle-only formatKotlin`
- Android lint: `./tools/verify --gradle-only lintDebug`
- Phone smoke checks: `python3 tools/verify_phone.py --serial SERIAL` (navigation/layout, lifecycle, Today/Week forecast, radar, city search/cancel and viewport restart checks; no favourites or connectivity changes).
- Places integration: `python3 tools/verify_phone.py --serial SERIAL --places-integration` (selects/saves test places, restores original places and camera; leaves permissions/settings unchanged).
- Full phone integration: `python3 tools/verify_phone.py --serial SERIAL --full` (also creates/deletes test favourites and temporarily changes airplane mode, Wi-Fi and mobile data; restores original settings).
- Phone runner expects matching debug and test APKs installed. It does not install/uninstall applications. See `docs/DEVELOPMENT.md` for signing and installation.

Android lint and ktfmt are the configured analysis/formatting tools. Detekt, ktlint, Paparazzi, managed-emulator tasks, MockK, Turbine and Konsist are not installed; do not claim their checks passed or add them without a concrete need.

## Test quality and scope

- JVM tests use JUnit 4 in `app/src/test`; device checks use `PhoneExercise` in `app/src/androidTest`. Keep tests in the same package as the code under test. Python tooling tests use the standard library's `unittest` in `tools/tests`.
- Prefer names like `functionName_condition_expectedResult` for new tests. Keep existing tests intact unless their behavior/contract actually changed.
- Never delete, skip, weaken assertions, or introduce a lint baseline just to turn a failure green. If an existing test appears wrong, explain the discrepancy before changing its contract. Necessary selector changes for an authorized UI change must preserve the original assertions.
- Unit tests must use fixed timestamps, checked-in fixtures and fakes for clocks, networking and storage; no live requests or real sleeps. Device integration tests may poll with a deadline and use gesture pacing.
- Assert observable behavior, including boundaries and failure paths; avoid tests that simply mirror the implementation. Cover stale/cache handling, failed refresh retention, obsolete requests, missing data, time zones and lifecycle state when changing those behaviors.
- Android framework calls in JVM tests should fail rather than silently return defaults. Keep pure rules independent of Android.
- Do not force an unrelated architecture migration or add runtime dependencies for test convenience. Test tooling needed for this workflow is in scope; future additions should have a specific benefit.

## Phone and completion rules

- For every UI release, capture fresh screenshots from that app version, attach them to the Codeberg release and embed them in its notes. Update the README images to the same version; never leave screenshots of an older UI in the README. Use a public city preset for captures and restore the phone's original places/camera afterward. Keep captures raw and visual review brief.
- Use an explicit device serial when multiple devices are present. Preserve app data. Never uninstall to solve a signing mismatch; use the existing debug key.
- On this workstation the phone's existing build uses `tmp/android/debug.keystore`. The verification wrapper prefers that directory when the key exists. Do not commit keys or passwords.
- Quick checks must not modify connectivity or favourites. Full integration is an explicit opt-in; restore connectivity even on failure and attempt every restoration action.
- Verify the foreground package and current UI bounds before coordinate taps. Instrumentation can exit to the launcher after finishing. Prefer semantic accessibility selectors over coordinates.
- Done means relevant test-first evidence, a green local gate, affected device checks where available, and no unintended changes. Android lint runs without a baseline; warnings fail the gate. A missing SDK, dependency or phone is a reported verification limitation, never a passing check.
