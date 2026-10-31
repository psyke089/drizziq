# Verification — Places, version 1.2.0

2026-10-31. JDK 21, Gradle 9.6.0, AGP 9.4.1, SDK 37 / Build Tools 36.0.0.

## Local checks

`./tools/verify --offline` runs 18 Python tooling tests, 57 JVM tests, formatting, Android lint and debug/release/device-test APK builds. The reviewed 13-entry lint baseline is unchanged. A new plural-resource finding was fixed in resources, rather than added to the baseline.

Test-first evidence was observed before implementing each new behavior: the compiling station placeholder failed 6 of 7 assertions; search/storage placeholders failed 12 of 14 tests; location/HTTP placeholders failed 11 tests; forecast lookup failed 5 of 6 tests. The resulting suites pass. Tooling regression tests also failed before metadata and phone-runner changes. Fixtures and fake clocks, transports, preferences and location providers keep these checks independent of live services.

Coverage includes Unicode queries, strict response candidates, full coordinate precision, zone fallback, provenance, legacy JSON, failed persistence, cache boundaries/LRU/refresh/offline behavior, response limits, redirects, Retry-After, obsolete results, location quality/age boundaries, provider eligibility, cleanup/cancellation/revocation/timeout, catalogue validation and nearest-point coverage, station-matched cache retention, and safe metadata regeneration.

## Connected phone

OnePlus 5T (`ONEPLUS A5010`), Android 13 / API 33, existing app data and signing key retained. Native instrumentation verifies city validation/search/edit/cancel/cache-clear, explicit candidate selection, full saved coordinates and recognized zone, forecast distance, attribution, edited favourite persistence through Activity recreation, and out-of-coverage forecast clearing with radar still usable. The opted-in Places integration restores exact original selected/favourite/map-camera preferences in its cleanup. The ordinary smoke suite also exercises navigation, lifecycle, forecast, radar gestures/playback/timeline and viewport persistence across restart/recreation.

Neither run grants/revokes location permissions or changes location/connectivity settings. The platform location adapter was exercised with the phone's existing denied access: it exposes no eligible providers and rejects protected calls. Rationale cancellation preserves permission and selection state. Approximate/precise acquisition, permission revocation, fix quality and listener cleanup are covered by deterministic fakes; live approximate/precise fixes and Android's permission prompt were not exercised. No API-24 phone was available; compatibility is checked by compilation/desugaring/lint.

The first live city-search UI attempt failed transiently; a direct device request returned HTTP 200 with ten parseable candidates, and subsequent UI/integration checks passed. The first complete smoke run also read the radar pan accessibility state too soon; a focused rerun passed. Gesture assertions now poll for the same required pan/zoom changes with a three-second deadline. Live service checks remain separate from the deterministic local gate.

## Release-build station timing

Measured on that phone in a non-debuggable release build signed with its existing key. The benchmark checks the build flag and catalogue hash, includes asset read/parse/validation in loading, warms up 20 lookups, then measures 200 Munich nearest-in-coverage lookups using elapsed realtime. No catalogue code changed after measurement.

| Measurement | Milliseconds |
| --- | ---: |
| Catalogue load and validation | 84.520 |
| Lookup median | 1.484 |
| Lookup p95 | 1.754 |
| Lookup maximum | 2.722 |

All measured lookups were below the 5 ms target. These are one-device observations, not universal performance guarantees. The 84.5 ms initial load runs once per Repository on a worker.

Catalogue SHA-256: `713df55d858359b021932d84b8dd9c2832b7d73b592e301b2f410c1d7a5b7f3c` (unchanged). Munich, Berlin and Hamburg reference IDs and the `0,0` coverage miss are pinned by tests.

Generated raw build, red/green and phone logs remain in ignored `tmp/`; published APK checksums are recorded in the Codeberg release. Public APKs retain the previous release signing certificate; development/benchmark APKs use the separate existing phone key. No uninstall/data reset is used.
