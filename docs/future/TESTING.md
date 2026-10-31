# Faster, deterministic phone tests

Proposed plan, 2026-10-31, against the Drizziq 1.3.0 working tree.
This document does not implement or enable a new test configuration.

## Intended result

Make the default phone suite fast, repeatable and independent of public services.
Preserve the assertions in existing checks, and keep a smaller, explicitly invoked
live integration suite for real HTTP, provider compatibility and connectivity
recovery.

Prioritize deterministic UI/component testing ahead of a broad expansion of
repository tests. Introducing the small seams needed to supply fixture data also
creates useful boundaries for later repository coverage. Fix the runner's
false-PASS behavior first.

The expected speed improvement is in phone checks that currently wait for live
forecast, radar and search responses. JVM tests already use isolated inputs and
should see little benefit. App startup, instrumentation startup, gestures and UI
synchronization still take time; measure the improvement before promising a target.

## Current setup and gaps

The review found 79 JVM tests and 25 Python tooling tests. Existing coverage
includes time-zone/DST boundaries, missing data, failed refresh retention,
obsolete requests, location cancellation and persistence failures. The local gate
also checks formatting, strict Android lint and debug/release/device-test builds.

Phone checks use the custom `PhoneExercise` instrumentation runner. Several depend
on current weather and live city search. An unknown phase currently falls through
to PASS, and focused phases such as `hourly-refresh` and `day-forecast` are outside
both the default and full runner commands. Building the device-test APK in the
local gate does not execute these checks.

The full integration run restores connectivity but does not automatically restore
selected-place, favourite or map-camera changes. Places integration already has a
snapshot/restore pattern to build on. See [DEVELOPMENT.md](../DEVELOPMENT.md) for
current command behavior.

## Delivery order

1. **Make runner results trustworthy.** Reject unknown phases. Explicitly register
   suite membership and distinguish regression checks from diagnostic probes.
   Ensure maintained regression phases have a documented invocation. Test runner
   command behavior with fake subprocesses; never toggle real radios to test the
   runner itself.
2. **Record a baseline.** Measure total and per-phase duration for the current
   default suite. Record device/API, app version, initial cache state and network
   conditions. Separate setup/build time from test execution. Repeat runs to
   expose timing variation and record failures without hiding them with retries.
3. **Prove one fixture-backed forecast check.** Add only the dependency seams
   required for controlled forecast data, an app clock and isolated test storage.
   Exercise the real Views and presentation rules. Compare the existing assertions,
   runtime and repeatability against the live version before migrating more checks.
4. **Migrate the default regression suite incrementally.** Cover navigation,
   layout, Today/Week, lifecycle, radar and search using fixed local fixtures and
   controllable responses. Include loading, live, cached, stale, empty and error
   states where relevant. Use controlled completion to exercise cancellation,
   obsolete results and recreation while work is pending. Add radar image and map
   tile fixtures as needed so the fixture run makes no public-service requests.
5. **Retain a focused live integration suite.** Keep checks for actual transport,
   provider formats, persistence across process restart and offline/recovery
   behavior. Preserve quick, Places and full integration opt-ins. Before relying
   on repeatable full runs, restore original places, favourites and camera as well
   as connectivity on success and failure, attempting every restoration action.
6. **Extend component coverage using the new seams.** Prioritize radar refresh,
   corrupt caches, missing frame files, failed writes, incomplete history, failed
   refresh retention and completion after shutdown. Follow the existing isolated
   forecast-loading tests as a model.

## Implementation constraints

- Follow [AGENTS.md](../../AGENTS.md): regression test, observed behavioral failure,
  minimal implementation, focused green tests, full local gate and affected device
  checks. Preserve existing assertions when reorganizing tests.
- Keep the current native Views, Repository and pure Kotlin rules. Use small
  constructor inputs, factories or interfaces where a concrete test needs them;
  no architecture migration or mocking framework is required.
- Keep fixture selection and overrides confined to test/debug wiring. Release
  builds must use production dependencies and expose no fixture switch.
- Use fixed timestamps and public-city fixtures. Control the app clock rather
  than changing the phone's time or connectivity for deterministic tests.
- Isolate test preferences/caches from personal app data and restore any state
  touched by device checks. Preserve the installed app and signing key.
- Wait for observable conditions with deadlines. Keep only necessary gesture
  pacing; do not replace network waits with arbitrary sleeps or weaken assertions
  to obtain a faster run.
- Keep tests independently selectable and report which scenario failed. Split
  the large instrumentation runner as needed. Consider AndroidX Test if it offers
  a concrete benefit; adopting a new framework is not a prerequisite.

## Acceptance and follow-up

The migrated default suite must pass without usable public-service connectivity,
make no external requests, preserve existing behavior assertions and leave user
data/settings intact. Demonstrate repeated runs with fixed inputs and report
before/after total and per-phase timings. A failed scenario or unknown phase must
produce a failing command result.

Run `./tools/verify` and affected device checks for each implementation increment.
Report missing dependencies or devices separately from test failures. Update
[DEVELOPMENT.md](../DEVELOPMENT.md) with the implemented suite commands and
boundaries; keep live integration results separate from deterministic results.

Later improvements are an automated local-gate equivalent and a small release
matrix spanning minimum/current Android, compact screens, large fonts and location
permission states on disposable emulators where appropriate. Coverage reporting
can guide additional tests; an arbitrary percentage target is not an acceptance
criterion for this work.

## References

- [Android testing strategies](https://developer.android.com/training/testing/fundamentals/strategies)
- [Android test doubles](https://developer.android.com/training/testing/fundamentals/test-doubles)
