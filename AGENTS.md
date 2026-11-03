# Working on Drizziq

## Project and environment

- Native Kotlin Android app with platform Views, one Activity, a Repository, and pure data/parsing code. There are no ViewModels, use cases, Flows, coroutines, or architecture-test framework. Keep business rules testable without Android; introduce abstractions only when needed by a real behavior/test.
- Use JDK 21, Android SDK platform 37 and Build Tools 36.0.0. Use the checked-in Gradle wrapper. `local.properties` or `ANDROID_HOME` supplies the SDK path.
- This workstation has JDK 21 at `/usr/lib/jvm/java-21-openjdk` and SDK at `/home/psyke/Android/Sdk`. Keep machine-specific paths out of tracked build configuration.
- `tools/verify` supplies local environment defaults; caller-provided environment variables take precedence. `--offline` requires dependencies to have been downloaded previously. Report missing dependencies separately from test failures.
- This is a personal, single-developer project, not an app-store product. Prefer working behavior and low maintenance over process completeness; the checks below are sized accordingly.
- GitHub Actions mirrors every push to `main` and every `v*` tag through the Codeberg push mirror; the workflow runs `./tools/verify` and publishes release APKs. The remote run is a backstop; run the release gate locally before tagging. See `docs/DEVELOPMENT.md` for the mirror, tag and signing rules.

## Product design, assets and data

- Visual language: dark, sci-fi and futuristic. Keep the interface consistent with the existing HUD palette and restrained typography/pictograms.
- Use a single dark color scheme. Do not add a light mode or switch palettes based on the system theme.
- Generate visual assets locally or use assets explicitly supplied by the user. Icon artwork belongs in `artwork/icons/` as editable SVG, the source of truth for generated Android vectors. Builds run `tools/generate_icons.py`; edit SVGs rather than the generated XML or `IconAssets.kt`. Keep icon shapes out of Canvas code; runtime code may position, tint and rotate the compiled vectors. Dynamic charts/maps still use Canvas. See `artwork/icons/README.md` for supported SVG features and intensity conventions. Do not hunt for icons online or download icon packs/stock assets.
- App data sources must be free and publicly available. Any source requiring an account or API key needs explicit user approval before integration, even if it has a free tier. Do not add an account/API-key requirement or obtain credentials implicitly.

## Testing and verification

- Pure logic (parsing, selection, cache policy, time handling): write or extend the smallest focused JVM test first when practical, observe it fail, then implement. A one-line fix may take its focused test immediately after the change instead.
- UI, layout, copy, SVG and style changes: do not invent tests. Run the app and check the changed screen. Update device assertions only when the UI contract actually changed (for example a renamed button).
- While iterating, run only the affected tests: one JVM class, one phone phase, or a formatter. Do not run the full gate after every edit.
- Before a release or tag: run `./tools/verify`, then the phone phases that match the release surface. Run `--full` only when connectivity, offline behavior or persistence changed, or when asked.
- Docs, formatting and changes that cannot affect runtime need no tests and no gate.
- Report the checks you actually ran in one or two lines, with any failure. Skip red/green narration unless the test is non-obvious.

Test verification scripts through their command behavior with fake subprocesses; never toggle a real phone's radios to test the runner itself.

## Commands

- Release gate (formatting, Python tooling tests, JVM tests, Android lint, debug/release APKs and device-test APK): `./tools/verify`
- Cached dependencies only: `./tools/verify --offline`
- Focused JVM test: `./tools/verify --gradle-only testDebugUnitTest --tests 'app.drizziq.ReadingPolicyTest'`
- Formatting check / apply: `./tools/verify --gradle-only formatCheck` / `./tools/verify --gradle-only formatKotlin`
- Android lint: `./tools/verify --gradle-only lintDebug`
- Phone smoke checks: `python3 tools/verify_phone.py --serial SERIAL` (navigation/layout, lifecycle, Today/Week forecast, radar, city search/cancel and viewport restart checks; no favourites or connectivity changes). The runner snapshots and restores the original selected place, favourites and camera after success or failure and requires the debuggable debug APK.
- Focused phone regression checks: `python3 tools/verify_phone.py --serial SERIAL --focused` (static resources and SVG vectors, hourly refresh, day forecast, shared header weather, refresh-banner absence and Places layout). Diagnostic probes are documented for direct invocation in `docs/DEVELOPMENT.md`.
- Places integration: `python3 tools/verify_phone.py --serial SERIAL --places-integration` (selects/saves test places, restores original places and camera; leaves permissions/settings unchanged).
- Full phone integration: `python3 tools/verify_phone.py --serial SERIAL --full` (also creates/deletes test favourites and temporarily changes airplane mode, Wi-Fi and mobile data; restores original places, camera and settings).
- Phone runner expects matching debug and test APKs installed. It does not install/uninstall applications. See `docs/DEVELOPMENT.md` for signing and installation.

Android lint and ktfmt are the configured analysis/formatting tools. Detekt, ktlint, Paparazzi, managed-emulator tasks, MockK, Turbine and Konsist are not installed; do not claim their checks passed or add them without a concrete need.

## Test quality and scope

- JVM tests use JUnit 4 in `app/src/test`; device checks use `PhoneExercise` in `app/src/androidTest`. Keep tests in the same package as the code under test. Python tooling tests use the standard library's `unittest` in `tools/tests`.
- Prefer names like `functionName_condition_expectedResult`. Keep existing tests intact unless their behavior/contract actually changed.
- Never delete, skip, weaken assertions, or introduce a lint baseline just to turn a failure green. If an existing test appears wrong, explain the discrepancy before changing its contract.
- Assert observable behavior and edge cases that have bitten before (boundaries, stale/cache handling, failed refresh retention, missing data, time zones, lifecycle). Skip tests that pin constants, wiring or incidentals.
- Unit tests use fixed timestamps, checked-in fixtures and fakes; no live requests or real sleeps. Device tests may poll with a deadline and use gesture pacing.
- Android framework calls in JVM tests should fail rather than silently return defaults. Keep pure rules independent of Android.
- No coverage targets. Add tests where the risk is real; do not add architecture or test tooling for its own sake.

## Phone and completion rules

- For every UI release, capture fresh screenshots from that app version, attach them to the Codeberg release and embed them in its notes. Update the README images to the same version; never leave screenshots of an older UI in the README. Use a public city preset for captures and restore the phone's original places/camera afterward. Keep captures raw and visual review brief.
- Use an explicit device serial when multiple devices are present. Preserve app data. Never uninstall to solve a signing mismatch; use the existing debug key.
- Debug builds use the standard `~/.android/debug.keystore`, the same key as published APKs, so local and released builds share one signing identity. Set `ANDROID_USER_HOME` only to override it. Do not commit keys or passwords.
- Quick checks must not modify connectivity or favourites. Full integration is an explicit opt-in; restore connectivity even on failure and attempt every restoration action.
- Verify the foreground package and current UI bounds before coordinate taps. Instrumentation can exit to the launcher after finishing. Prefer semantic accessibility selectors over coordinates.
- Done means the change works in the app, the tests for touched behavior pass, and nothing unrelated changed. Android lint runs without a baseline and stays strict.
- Run the full gate and affected phone checks when releasing, not for every change. A missing phone blocks release checks only; state it plainly instead of claiming a pass.
