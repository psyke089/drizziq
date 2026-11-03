# Deferred work

Requirements intentionally left open after the 2026-11-03 implementation wave.
Each item is reachable; none is silently dropped. Everything else from the
temporary implementation plan and the review was delivered and is recorded in
[DEVELOPMENT.md](DEVELOPMENT.md), [DATA.md](DATA.md) and the
[review resolution audit](reviews/2026-11-01T00-20-44-ds41flash.md).

## 1. Live full phone integration

`python3 tools/verify_phone.py --serial SERIAL --full` was not executed because it
changes test favourites and toggles airplane mode, Wi-Fi and mobile data, which
requires explicit opt-in. Deterministic coverage exists for the same contracts:
offline Today keeps `STALE` with retained values, Radar shows `Offline radar`, and
the runner's offline/full-restoration behavior is covered by fake-subprocess tests.

To close: run `--full`, confirm all phases pass, and confirm favourites, selected
place, camera and the original radio states are restored.

## 2. Other Android versions, screen sizes and font scales

Only a OnePlus 5T (API 33) is available. API 24/25 temperature sizing, narrow
screens and large font scales were not exercised on hardware. The layout uses
measured growth for the Week date rail and bounded API 24+ header sizing, but
that is not device proof.

To close: run the focused suite on an API 24/25 device or emulator and on a
compact screen with a large font scale; adjust only demonstrable clipping.

## 3. Radar overlay live acceptance

Implemented and deterministically tested: capability parsing, time/reference-run
selection, tile bounds/URL keys, error parsing, allow-list narrowing, two workers,
8 MiB bitmap and 32 MiB per-layer disk budgets, visible-tile requests, obsolete
result rejection, offline/cached notices, recreation-safe selection, credits and
the floating layer menu. `fixture-ui` exercises layer switching on device.

Not yet accepted against the live service:

- Wind is omitted. Live samples rendered thin, unreliable black barbs with no
  usable zoom-8 sample, so there is no Wind menu row and no fabricated wind field.
- Clouds use the global three-hourly IR mosaic. Its advertised coverage ends at
  54° N, and night IR contrast was not visually accepted for northern Germany;
  the timestamp is shown separately and never implied to match radar frames.
- Temperature contour legibility over the dark basemap and precipitation was not
  reviewed on a live rendering (the live WMS advertised stale slots and was
  intermittently unavailable during checks).

To close: with current capabilities, review clouds and temperature at zoom 5–8
over Munich and northern Germany, adjust alpha only if demonstrably unreadable,
and record the accepted state in [DATA.md](DATA.md).

## 4. Overlay cache age deletion

Overlay images are reused only while younger than six hours (clouds) or two hours
(temperature); older files are refetched online and otherwise shown as
`Cached overlay`. Files are bounded by the per-layer disk budget and pruned by
access recency, but not deleted purely by age. This keeps offline viewing possible.

To close, if age-based deletion becomes desirable: prune overlay stores with an
expiry callback and cover the boundary in a cache test.

## 5. Commit, release and screenshot publication

The whole change set is uncommitted and the version remains 1.4.1. `README.md`
and `docs/screenshot.png` were updated from the current debug build on the
OnePlus 5T using the public Munich preset; the phone's original selected place was
restored afterwards. Publishing a new tag/release and attaching the raw captures to
the Codeberg release notes requires maintainer action (version bump, commit, tag,
push), which was not taken unasked.

To close: bump `versionName`, run the full gate and affected phone checks, commit
in independently verifiable slices, push/tag, then attach the raw captures and
embed them in the release notes per [DEVELOPMENT.md](DEVELOPMENT.md).

## 6. Broader test matrix and coverage reporting

The GitHub Actions workflow already runs the full local gate. A release matrix
spanning minimum/current Android, compact screens, large fonts and location
permission states on disposable emulators, plus coverage reporting, remains
optional follow-up rather than an acceptance requirement.

The planned sunset work in [future/SUNSET.md](future/SUNSET.md) is intentionally
untouched.
