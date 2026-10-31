# Verification — Today/Week and layout revision

2026-10-31. This is the working UI revision after release 1.2.0; no new release or version bump is implied.

## Changes and automated gate

Today and Week replace the single Forecast tab. The hourly screen uses aligned horizontal hour columns, generated condition/wind icons, a temperature ribbon and rain bars, while Week shows daily rows. Places removes manual coordinates and explanatory copy, retaining saved-place compatibility and a focused unresolved-zone prompt. Device snapshots use the resolved station name without replacing their coordinates or user labels. Radar fills the content width with transparent overlaid playback controls and no legend. A reserved two-dp line tracks overlapping weather, tile and city-search transfers.

`./tools/verify --offline` passed: 66 JVM tests, 18 Python tooling tests, Kotlin formatting, Android lint, and debug/release/device-test APK builds. There are no new lint findings. The deleted `TemperatureTrace` resolved its existing constructor finding; only that baseline entry was removed, leaving 12 reviewed findings.

Test-first evidence:

- The new presentation/download APIs first failed compilation, then compiling placeholders failed all seven tests. Implementations passed tests for missing hours/fields, UTC/DST slots, the 24-hour boundary, immutable station display names, overlapping/idempotent transfer completion, observer removal and error cleanup.
- Additional HTTP-progress and station-resolution tests failed before integration, then passed. Station identity becomes available before weather I/O, including failed fetches; obsolete requests cannot publish it.
- The new native layout phase failed against the old UI because Today did not exist, then passed against the implementation.
- Two runner assertions failed before adding the layout phase; all 18 tooling tests passed afterward.

Existing coordinate-form tests were updated for the explicitly removed interaction. Coordinate bounds remain covered by pure model tests; opted-in device fixtures exercise out-of-coverage behavior through normal Activity startup. Selection, attribution, favourites and restoration assertions remain in place.

## Connected phone

On the OnePlus 5T / API 33, the complete runner with `--places-integration` passed all nine phases: navigation, layout, lifecycle, forecast, radar, places-search, viewport-save, viewport-restore and places-integration.

Checks include square fixed tabs; isolated Today/Week content; hourly horizontal scrolling; all seven daily rows and provenance footers; Week restoration after recreation; removed Places copy/manual entry; full-width map bounds; controls contained inside the map with transparent background; removed legend; gesture zoom/pan/recenter, timeline and playback; reserved download-line height and unchanged navigation bounds; search/edit/cancel/cache clear; persisted edited attribution; station-name display/default favourite label for a device snapshot; and out-of-coverage isolation.

The first full phone run exposed an old exact-string recenter assertion at a six-decimal rounding midpoint. A privacy-preserving probe measured a 0.5-microdegree latitude difference (about 5 cm), consistent with display rounding after the Mercator round-trip. The assertion now compares against half of the displayed coordinate unit, preserving the recenter requirement. Timeout logs no longer dump position/query text.

The Places integration restores exact original selected/favourite/camera preferences even on failure. It uses controlled persisted snapshots for the station-name/coverage checks; location permissions and connectivity settings are unchanged. The ordinary viewport checks deliberately move the camera to verify persistence, as documented in DEVELOPMENT.md. The latest normal debug app remains installed, without uninstalling or resetting data.

Visual verification was one brief inspection of the actual hourly screen against the supplied reference's horizontal layout. Generated screenshot/log artifacts remain private and ignored under `tmp/`; no browser was opened.

## Follow-up copy cleanup

Removed Today's missing-value explanatory paragraph, the duplicate selected-place block above the Places buttons, and Week's heading, calculation footnote and partial-day labels. Removed the now-unused duplicate-title refresh state. Data calculations and unavailable totals are unchanged.

The new `copy-cleanup` phone regression first failed with “Today explanation remains”, then passed after implementation. Updated layout/forecast checks also passed, asserting actual daily table headers instead of the removed heading and confirming the explanatory text is absent. The complete local gate passed again (66 JVM tests, 18 tooling tests, formatting, lint and all APK builds). The latest debug app is installed and open on the connected phone, preserving saved data.

## Forecast status banner removal

Removed the transient message banner above Today/Week content. Error details remain inside the existing empty-state card, and freshness remains in the footer. A focused `forecast-status` device regression reproduced banner insertion during a real refresh before the fix; afterward both Today and Week retained the same content top with no banner inserted. The complete local gate passed again (66 JVM tests, 18 tooling tests, formatting, lint and all APK builds). The fixed app is installed and open on the phone; saved data and permissions are unchanged.

## Shared current-weather header

Checkpoint `3f2898a` commits the preceding Today/Week, radar, Places and status-banner changes. The subsequent header revision moves temperature, condition icon and wind to the right of the selected location/station on every tab. Names use 20-sp text, two reserved lines and end ellipsis. Weather slots retain their dimensions when unavailable. The duplicate Today card is removed; station metadata, validity and provenance remain in the forecast footer.

The new `header-weather` device regression first failed against the checkpoint, then passed: weather sits right of the name and above scrolling content, a long test name wraps/ellipsizes without overlap, no duplicate current-temperature card remains, and the header stays fixed across scrolling and all tabs. The complete local gate passed (66 JVM tests, 18 tooling tests, formatting, lint and all APK builds). All nine standard/Places phone phases passed, including clearing header weather outside forecast coverage and restoring saved data. The focused refresh-stability regression also passed on Today and Week. One brief actual-phone header preview was inspected; the final build remains installed and open.

## Calendar-day forecast and sun card

Checkpoint `629271c` commits the previous header. The follow-up adds a taller, more prominent weather header; narrower/bold full-civil-day columns; validated DWD R101/FX1 fields; identical shared details cards without DATA TRANSMISSION; and worker-calculated sunrise/sunset, including outside weather coverage. Missing past forecast hours remain unknown.

Test-first evidence: nine of ten compiling-placeholder tests failed before implementation, plus the native layout regression. A fixture assertion then caught the parser dropping optional fields; retaining R101/FX1 made it green. Final gate: 72 JVM tests, 18 tooling tests, formatting, lint and all APK builds passed. All nine smoke/Places phases and focused day-forecast/refresh-stability phases passed. Original selected data/favourites were restored and permissions/settings were preserved. Brief native previews were inspected; the final app remains installed and open.
