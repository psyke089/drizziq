# Sunset visibility and nearby viewing spots

Revised draft, 2026-10-31, initially against Drizziq **1.2.0**, commit `0d75471`; navigation/place-entry references updated for the subsequent Today/Week UI revision.
Supersedes `tmp/sunset-feature-spec.md`. This is a proposed feature, not part
of the 1.2.0 release. Preparing this specification does not enable new services
or authorize operating infrastructure.

Current implementation note: Drizziq 1.3.0 ships local sunrise and sunset times
in Today using a standard horizon. This covers the basic event-time portion;
date selection, sunset bearing, horizon/cloud visibility and spot finding below
remain proposed. The runtime parser explicitly whitelists MOSMIX fields; adding
further cloud layers requires a tested parser change, not just reading arbitrary
keys from `Hour.values`.

## 1. Intended result

Answer two related questions:

1. When and in which direction does the sun set at the selected place, and
   what can the available terrain and weather data tell us about the view?
2. Which nearby candidate places offer a better modelled horizon?

Keep astronomy, horizon obstruction and weather evidence separate. A computed
sunset time is not a prediction that the sun will be visible. Missing terrain,
clouds, access information or precision must remain unknown. Do not convert
an uncalibrated heuristic into a percentage likelihood or promise colourful skies.

The full spot finder remains a goal. Deliver it in the increments below so the
existing app can gain useful local calculations before committing to a terrain
distribution service or recurring weather pipeline.

## 2. Current project facts and constraints

| Area | Existing implementation and consequence |
| --- | --- |
| Android | Kotlin, API 24+, one `:app` module, native Views/Canvas, `MainActivity` and `Repository`. No map SDK, Compose, ViewModel, DI, Room, coroutines or WorkManager. |
| Background work | Executors, Handler and generation tokens. Keep I/O/math off the UI thread and reject obsolete callbacks; no architecture migration is required. |
| Navigation | Fixed Today / Week / Radar / Places bottom tabs. Begin with a Sunset card/detail in Today; do not add another tab implicitly. |
| Places | City search, optional one-shot Android location and saved snapshots; no manual-coordinate entry. Reuse these paths and `choose()`. No second permission flow or automatic tracking. |
| Coordinates and zones | `Place` stores full coordinates and an IANA zone. Use its civil date, not the phone or forecast station zone. `ZoneLookup` is available offline. |
| Weather | Direct DWD MOSMIX station forecasts, with matching-station cache/freshness rules and a 50 km inclusive lookup limit. Open-Meteo is geocoding only. |
| Map | `RadarMap` is a custom OSM tile renderer with radar-specific overlays, gestures and persistent camera. It is not an existing general spot/navigation screen. |
| Storage | Private place preferences and file caches. No backend, scheduled jobs, hosting contract or CI exists. Codeberg is currently private; its private release assets are not an anonymous public data service. |
| Design | One dark, sci-fi/futuristic colour scheme. Generate vectors/Canvas artwork locally; do not download icons. |
| Sources | Free/public sources only. Any account or API key, including a free registration, needs explicit approval first. No credentials are embedded in the app. |

Follow [AGENTS.md](../../AGENTS.md) and [DATA.md](../DATA.md). Preserve the current selection, favourites, search
attribution, radar camera, connectivity and permission settings during ordinary
checks. Existing app accounts are not approval for a new data account.

## 3. Delivery increments

| Increment | User-visible scope | Completion gate |
| --- | --- | --- |
| A — Local sunset | Sunset time/bearing for today, tomorrow and the following local date; a north-referenced direction glyph; separate available MOSMIX cloud context. Works without new services or permissions. | Pure reference tests, honest missing-weather states, native UI/lifecycle checks and full local gate. |
| B0 — Data proof | Developer-only prototype for a bounded Munich-area pilot: one licensed elevation source, candidate extract, horizon calculations and measured pack size/error. No production spot recommendations yet. | Reproducible generator, source/licence/access audit, fixture validation, published coverage and measured device budgets. |
| B — Regional horizon/spot finder | Horizon evidence at supported positions and a ranked list of eligible nearby candidates, with a map preview and explanations. Unknown outside validated pilot coverage. | B0 passed; pack distribution chosen; geometry/uncertainty/access contracts below implemented. |
| C — Spatial cloud context | DWD grid fields along the sunset direction, displayed as model evidence; any ranking contribution requires validation. | A working DWD decoding/data-delivery proof plus an explicit operations/distribution decision. |
| Later | Routing/travel times, detailed buildings/canopy, notifications, satellite nowcasting and wider regions. | Separate specs and any required source/account approvals. |

Do not silently replace B/C with a single-station “sunset quality” score.
Do not imply that a Germany-wide 25 m horizon database or daily tile service
already exists. There is no fixed implementation-duration promise.

## 4. Interaction and presentation

Increment A adds a compact Sunset card to Today with local date/time, true
bearing clockwise from north, a generated glyph and a Details action. It remains
available when the selected place is outside MOSMIX's 50 km coverage or weather
loading fails. Keep astronomy independent of the existing empty-weather layout.

Details has a three-date selector using the selected place's zone. Default to
today; after today's sunset show “Sunset has passed” and offer tomorrow rather
than silently changing the requested date. Show “No sunset on this date” for
polar cases. Never display a made-up time. No device-heading sensor or magnetic
compass is required: label the bearing as true north referenced.

Show evidence in separate rows:

- **Astronomy:** conventional sunset time and bearing, computed locally.
- **Horizon:** modelled clear / obstructed during the stated interval / uncertain
  / unavailable, with resolution and relevant obstruction angle when known.
- **Weather:** supplied cloud values, station/model location, issue/valid time
  and existing loading/live/cached/stale/error state. “Cloud context unavailable”
  is distinct from a clear sky.

In B, Best nearby spots opens a native list and map preview with a straight-line
radius selector (1, 5 or 10 km; default 5 km), at most ten displayed results and
at most 100 evaluated eligible candidates. Previewing a spot does not call
`choose()`, save a favourite or change the selected-place radar camera. An
explicit “Use this place” action uses the existing selection path. “Open in
maps” uses a resolvable external geo intent; absence of a handler is handled.
There is no existing routing feature to hand off to internally.

The map uses north-up true-bearing geometry. Refactor a small shared basemap
piece only when B needs it, with radar gesture/camera regression tests. Keep
sunset preview state separate from `radar-viewport`; do not mix sunset markers
into radar playback. Preserve OSM attribution/cache policy. Dense cards, charts
and markers must fit the existing dark language and expose accessible text.

## 5. Astronomy contract

Implement a small pure Kotlin solar calculator, with explicit position, civil
date and IANA zone inputs and UTC instants as outputs. `java.time` is available
through the existing core-library desugaring; no astronomy runtime dependency
is presumed. Use a documented Meeus/NOAA-style method, independently checked
against fixed reference cases; do not scrape a calculator at runtime.

Define conventional sunset as the descending crossing of **geometric solar
centre elevation −0.833°**, representing the usual disc/refraction convention
for an unobstructed reference horizon. This convention does not include the
actual local skyline or measured atmospheric refraction. Return explicit no-event
results rather than clamping an invalid hour-angle calculation into a sunset.

Find the event within the requested local calendar day, using zone rules at
the event instant, not a constant offset. Test 23/25-hour days, leap years,
UTC date crossings, both longitude signs, the antimeridian and polar cases.
Use degrees clockwise from true north in `[0, 360)`, with consistent radians
inside trigonometry. Do not inherit a legacy calculator's west-positive
longitude convention accidentally.

Reference-test targets in ordinary mid-latitude cases: within two minutes for
the conventional event and 0.5° bearing against independently recorded
reference values using the same convention. These are implementation acceptance
tolerances, not guarantees of when an observer sees the last sunlight.

For horizon evaluation in B, sample the solar path at 30-second intervals over
the 30 minutes before through 10 minutes after conventional sunset. Identify
visible/obstructed intervals, allowing multiple disappearances across a ridge.
Label this finite evaluation window; do not claim it captures every earlier
terrain disappearance. Refine crossings numerically when needed. Derive the
azimuth range from that path. Do not use the draft's fixed 235–305° sector or
constant azimuth speed; both depend on latitude, date and time.

## 6. Weather evidence and its limits

### A/B: existing MOSMIX context

`Hour.values` contains only explicitly retained MOSMIX fields. The committed
Munich source fixture contains `N`, `Nl`, `Nm` and `Nh`, but only `N` is currently retained by the parser; extend the whitelist and verify units, meanings
and validity semantics against DWD's parameter description before exposing
them. Presence in one fixture does not guarantee availability for all products.

Use only a matching, in-coverage forecast for the evaluated place. Show the
two real hourly values bracketing the event when present, with their times;
do not extrapolate beyond coverage, silently fill missing layers or treat a
past event as covered by a future forecast. Missing one side remains incomplete.
Keep issue/reception timestamps and stale state. The three date choices are
astronomical options, not a promise of three complete weather days.

Single-station cloud fractions describe forecast context near that point.
They are not measured cloud opacity, a horizon ray, a probability of seeing
the solar disc or a calibrated colour forecast. In B they may accompany a
geometric ranking, but cannot manufacture differences between nearby spots.
Do not issue one weather download per candidate implicitly. If a candidate has
no matching available forecast, its weather stays unknown.

### C: optional DWD spatial pipeline

The official ICON-D2 directory exposes `clcl`, `clcm`, `clch`, `clct` fields.
The inspected D2 listing contains native icosahedral files; the inspected EU
listing contains regular-lat-lon files. Do not assume both products arrive on
the same grid or hard-code the draft's forecast cadence/range. A spike must
download and decode representative files, inspect GRIB metadata, units, grid
coordinates and missing-value conventions, then record exact paths/checksums.

Keep GRIB2/eccodes/GDAL processing out of the Android app. If C is chosen,
developer-operated preprocessing and immutable small derived files are a
candidate design, **not an assumed existing backend**. Before enabling it,
decide ownership, hosting cost, public anonymous access, refresh/failure
handling and redistribution notices. Do not create accounts, deploy jobs or
add a wrapper weather provider as part of A/B.

Cloud fields must include run/issue time, validity instants, model/grid identity,
layer definitions, coverage mask and revision. Fetch a complete required set
from one run; never combine whichever files happen to be newest. Publish data
before an atomic manifest switch, retain the previous complete run, verify
checksums and reject partial/mismatched runs. Runtime parsing remains bounded.

Bilinear interpolation is valid only on an appropriate regular grid with valid
neighbours; native ICON requires a documented remapping step. Interpolate in
time only between supported valid instants of one run, without extrapolation.
No missing cell becomes zero cloud. Spatial sampling must include the actual
sunset bearings, model domain edges and the complete requested ray corridor.

The old 10–400 km samples and typical layer heights are hypotheses for a
developer experiment. Cloud fraction and assumed height alone do not establish
line-of-sight obstruction or independent blockage probabilities. A useful
obstruction model needs vertical/opacity assumptions, curved geometry and
validation. Do not multiply fractions into a probability or promise colour
quality. Until validated, expose the sampled evidence and its limitations.

## 7. Elevation, skyline and uncertainty

B0's candidate baseline is the publicly accessible Copernicus GLO-30 Public
distribution, subject to checking pilot tile availability and its exact licence.
The documented unsigned S3 route requires no AWS account. This is separate
from CDSE/Sentinel Hub services with registration/access restrictions; do not
replace it silently with a credentialled endpoint.

Copernicus is a **digital surface model (DSM)**, already including surface
effects from buildings and vegetation at its resolution. It is not bare-earth
terrain. Do not add OSM building or canopy heights on top. A later bare-earth
DTM plus separate surface layers is a different, explicitly versioned model.
No urban “building-clear” claim follows from a coarse DSM. Void/missing land
cells are unknown; treat ocean as zero only with an authoritative ocean mask.

Record horizontal CRS/grid, vertical datum/units, source resolution, acquisition
and revision, nodata, resampling, extent and source checksums. Do not assume
one uniform pixel width across all source latitudes. Align datums before
combining heights. Observer elevation comes from a compatible ground model
plus a stated eye height (default 1.7 m), not MOSMIX station elevation. A DSM
sample is not automatically the ground below a person; where that distinction
cannot be resolved, label the estimate uncertain. Do not mix unqualified GPS
altitude into it.

Ray tracing should retain the maximum obstruction angle, distance and data
quality along each evaluated azimuth, plus explicit coverage gaps. For a local
approximation, one candidate relation is:

```text
angle = atan2(h_sample - h_observer - d² / (2 R_effective), d)
```

Use metres consistently and state the curvature/refraction assumptions.
An effective Earth radius such as 7/6 of the physical radius is a configurable
modelling assumption, not measured weather or a universal correction. Keep
solar apparent elevation, disc radius and terrain refraction conventions
consistent: the −0.833° event convention must not be applied a second time
when comparing the upper limb with a horizon.

Compare upper/lower limb clearance with the skyline over the sampled interval;
partial disc visibility and uncertain margins are distinct. Start with a 1°
minimum uncertainty band for coarse data, widening it where source/position
uncertainty demands. This is a conservative product rule to validate, not a
statistical confidence interval. Uncovered rays cannot support “clear”.

Initial maximum terrain distance is 50 km, explicitly shown in provenance;
mountain visibility can extend beyond it. Observer cells near pack edges need
the full ray halo. A candidate pack cannot end at the search-radius boundary.
Use adaptive stepping/multiresolution data only after synthetic tests prove
that narrow high obstructions are not averaged away. Signed angle storage is
required; an unsigned byte cannot represent a below-horizontal skyline.

Location permission precision does not guarantee metre-level geometry. The
current `Place` snapshot does not retain fix accuracy. If B uses device fixes,
add backward-compatible optional accuracy/time metadata and preserve the
one-shot policy. Older/manual snapshots have unknown accuracy. Approximate
positions spanning materially different horizons must produce uncertain or
unavailable skyline evidence; do not demand precise permission just to display
astronomy or a regional search.

## 8. Candidate selection and ranking

For B0, create a bounded, versioned regional candidate extract from OSM with
source ID/type/version, coordinates, tags, source date and attribution. Candidate
classes may include viewpoints, peaks and publicly tagged waterfront/open
spaces, but none alone proves an unobstructed or lawful viewing spot.

Exclude explicit private/no-access or known closed-at-event-time candidates.
Missing/unsupported access or opening-hours data remains unknown and visible.
Do not label a place safe, wheelchair-accessible, reachable on foot or open
from missing tags. No walking-time or routing filter ships before a routing
source/engine is chosen and tested. Straight-line distance is labelled as such.

Filter by true geodesic distance and validated pack coverage, then evaluate at
most 100 nearest eligible candidates (distance ties by OSM type and ID). Display
up to ten. Rank deterministically by horizon evidence tier, then duration of
modelled disc visibility in the evaluation window, then clearance outside the
uncertainty band, then straight-line distance, then stable source identity.
Unknown geometry is shown separately and never wins as a clear view.

This first ranking has no unvalidated 0–100 “quality” score, hidden amenity
weights or cloud probability. Show the evidence that determined each rank.
If the user later wants a combined index, specify/version its formula, missing
component behavior and validation first; an index must not be labelled probability.

Saving an OSM-derived candidate needs source attribution after restart and
label edits. Extend place origin metadata backward compatibly if necessary;
do not put an OSM ID into the Open-Meteo-specific `geocodeId`, display a false
Open-Meteo credit, or discard OSM label provenance. Coordinate changes clear
derived-place provenance as in the current Places contract.

Use developer extracts for the pilot. A public Overpass endpoint is not an
assumed production backend: its operator's guidance explicitly discusses this
limitation. Any later runtime query must disclose the transmitted search area,
use bounded explicit requests/caching and a verified service policy. No tile
stitching, mass scraping or public routing-demo dependency.

## 9. Android ownership, packs and offline behavior

Introduce pure solar/horizon/ranking types only as tests and implementation
need them. Keep one Activity-owned sunset request generation, separate from
weather/search generations; place/date changes, cancellation, stop/destruction
invalidate pending work. Use a small owned executor with bounded latest pending
work for expensive evaluation. Close it and reject stale callbacks before
rendering or committing derived caches. Do not monopolize weather workers.

Reuse `Reading`-style explicit states for fetched evidence. Astronomy is a local
calculation, not “LIVE DWD”. A failed terrain/cloud refresh retains only the
matching place/date/model evidence with original timestamps and a stale label.
Never carry a previous place's verdict into a new selection.

No Room, WorkManager, foreground service, background location or automatic
periodic sync is needed in A/B. A first bounded pilot pack can ship as an app
asset if its measured size is acceptable. Larger downloadable packs require
a deliberate public distribution decision; current private Codeberg assets do
not solve that. Do not hand-write a general GeoTIFF decoder for Android.

The developer pack generator may use pinned geospatial tools after reviewing
their licences and reproducibility. Stage and validate all outputs before
replacement, like `tools/update_metadata.py`. Require an explicit schema,
byte order, signed angle/height units, dimensions/count limits, nodata/masks,
checksums, source/datum/revision and attribution. B0 chooses exact encoding
after measuring a real pilot, not an assumed nationwide tile budget.

Cache keys include exact evaluated coordinates, date/zone, observer assumptions,
source/model/pack revisions and algorithm version. A camera position or label
is not an evaluation key. Show geographic/temporal coverage honestly. Clearing
feature caches must not clear places/favourites. If runtime downloads arrive,
use approved HTTPS endpoints, bounded redirects/timeouts/decoded sizes,
atomic writes, explicit refresh and no automatic retry loops.

Offline: astronomy works; saved packs support only covered geometry; valid
matching saved weather keeps its cached/stale state. Missing components remain
unavailable. Device positions are resolved locally, but tile/pack requests
reveal approximate areas to hosts and IP addresses are observable. Do not
repeat the draft's “no personal data leaves the device” claim. Do not log
precise coordinates, full URLs or search text.

## 10. Tests, acceptance and measurement

Follow test-first development: smallest meaningful test, observed assertion
failure, implementation, focused green checks, then `./tools/verify` and affected
phone phases. Documentation does not need artificial behavior tests. There is
no CI. Use fixed references/fixtures and fake clocks/network/storage; no live
requests or sleeps in unit tests.

- **Astronomy:** independent reference cases, solstices/equinoxes, hemispheres,
  zone/DST boundaries, polar no-event outcomes, true bearing wrap and requested
  local day. Missing weather cannot hide the local result.
- **Horizon:** synthetic flat ground, known wall/ridge height and distance,
  negative skyline, curvature units, partial disc, multiple crossings, angular
  interpolation across north, narrow obstacles, ray boundaries/nodata, DSM/DTM
  handling, datum mismatch and position uncertainty. No unknown becomes clear.
- **Sources/packs:** malformed/truncated/oversized files, bad dimensions/units,
  checksums, incompatible revisions, ocean versus void, partial publication,
  failed regeneration retaining previous outputs and attribution retention.
- **Weather:** field units/ranges, missing layers/bracketing hours, out-of-range
  dates, station mismatch/50 km limit, stale timestamps, obsolete completions;
  C adds archived GRIB decoding/remapping and same-run interpolation fixtures.
- **Ranking/storage:** deterministic ties, radius boundaries, candidate cap,
  private/closed/unknown access, missing geometry, unchanged selection while
  previewing, source provenance after label edits and legacy-place migration.
- **Phone:** card/detail/date navigation, accessible glyphs, no implicit location
  prompt, pending-work cancellation/recreation, offline states and B's map
  gestures/preview isolation. Permission/settings/data changes require explicit
  integration opt-in with snapshot/restore and fake-runner tests first.

Measure pure astronomy, pack loading, ray evaluation and 100-candidate ranking
on the connected OnePlus 5T in a release build, using fixed inputs and sample
counts. Provisional targets: cached detail within two seconds and 100 candidates
within 300 ms off-main. Report median/p95/max, APK/pack/download size and peak
memory; these are unverified targets, not current app performance claims.
Keep the pilot bounded and measure before committing to a 50–100 MB cache or
1–2 MB daily transfer claim. No unlimited global prefetching.

Before describing B as useful for recommendations, compare a small diverse
set of open horizons, valleys and urban sites with observations at recorded
positions/times. Record resolution/uncertainty and failures. No photo upload,
feedback endpoint, tracking or calibration crowdsourcing is implied.

## 11. Remaining decisions and handoff

The next implementable increment is A. It needs no new service, account or
runtime library. B0 must settle the pilot extent, compatible observer-ground
source, candidate extract licence, actual pack size and achievable uncertainty.
B cannot claim reliable building/tree visibility before those checks pass.
C additionally needs a hosting/operations decision and a validated spatial
cloud interpretation. Leave those explicit rather than guessing credentials,
infrastructure or scientific thresholds during UI implementation.

Update `docs/DATA.md`, bundled notices, README and tests when the corresponding
increment is implemented. Do not mark this
feature shipped, bump the app again or attach it to the 1.2.0 release merely
because this draft exists.

## 12. Primary references checked 2026-10-31

- [NOAA calculation details](https://gml.noaa.gov/grad/solcalc/calcdetails.html)
  and [solar equations](https://gml.noaa.gov/grad/solcalc/solareqns.PDF):
  conventional event definition and calculation limitations. The online
  calculator is no longer maintained; use fixed independent references.
- [DWD ICON database manual](https://www.dwd.de/DWD/forschung/nwv/fepub/icon_database_main.pdf),
  [D2 field listing](https://opendata.dwd.de/weather/nwp/icon-d2/grib/00/),
  [D2 low-cloud listing](https://opendata.dwd.de/weather/nwp/icon-d2/grib/00/clcl/)
  and [EU low-cloud listing](https://opendata.dwd.de/weather/nwp/icon-eu/grib/00/clcl/):
  source discovery only; listing inspection is not a decoded pipeline proof.
- [Copernicus public distribution](https://registry.opendata.aws/copernicus-dem/)
  and [provider README](https://copernicus-dem-30m.s3.amazonaws.com/readme.html):
  DSM semantics, unsigned access and grid layout. Check the exact
  [GLO-30 licence](https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf)
  and attribution for actual redistributed derivatives. Other access routes
  have [different registration conditions](https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM).
- [Overpass operator guidance](https://dev.overpass-api.de/overpass-doc/en/preface/commons.html)
  and existing [OSM tile policy](https://operations.osmfoundation.org/policies/tiles/).
  Recheck the chosen source/endpoint before enabling downloads; free data does
  not imply unlimited public-service capacity.
