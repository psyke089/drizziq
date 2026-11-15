# Sunset visibility and nearby viewing spots

Revised **2026-11-05** against released Drizziq **1.6.4**, commit `2af89b3`,
and the current working tree, which declares **1.6.5**. Working-tree behavior
below is not a claim that 1.6.5 has been published. Originally drafted against
1.2.0 (`0d75471`); supersedes `tmp/sunset-feature-spec.md`.

Local standard-horizon sunrise and sunset times have shipped since 1.3.0.
The remaining proposal extends that implementation with date selection,
sunset bearing, weather context and eventually horizon evaluation/spot finding.
Preparing this specification does not enable new services or authorize
operating infrastructure.

### Implemented baseline and remaining scope

| Capability | Current status | Consequence for this proposal |
| --- | --- | --- |
| Local sunrise/sunset | `SolarCalculator.calculate(place, date)` returns UTC epoch milliseconds in `SolarDay`, with explicit polar-day, polar-night and skipped-local-date outcomes. | Reuse and extend it; do not build a second event-time calculator. |
| Today sun card | `SunriseCard` shows both events in the selected place's zone, including when DWD weather is unavailable. Week intentionally has no solar card. | Extend the existing card with a detail action, keeping sunrise and the current empty-weather behavior. |
| Solar work ownership | `MainActivity.ensureSolar` keys results by coordinates/zone/local date; a dedicated worker has one pending slot, cancellation and generation checks. | Extend the existing ownership for detail-date requests; do not add a second worker for the same calculation. |
| Solar tests | Fixed USNO event references, polar cases, DST, fractional zones, antimeridian and a skipped civil day are covered by `SolarCalculatorTest`. | Preserve these tests; add bearing/path and detail-state tests for new behavior. |
| Bearing/date detail | No azimuth in `SolarDay`; elevation is private to the calculator. The UI calculates the current civil day only. | Remaining increment A1. Arbitrary-date calculation already exists at the pure API level. |
| Weather layers | The MOSMIX whitelist retains `N`, but not `Nl`, `Nm` or `Nh`. | Additional layers require a tested parser change; they cannot simply be read from `Hour.values`. |
| Horizon/spots/spatial clouds | No terrain pack, skyline evaluator, OSM spot dataset, cloud-grid decoder or derived-data service exists. | B0/B/C remain future work. |

Implementation anchors:
[SolarCalculator.kt](../../app/src/main/java/app/drizziq/SolarCalculator.kt),
[SunriseCard.kt](../../app/src/main/java/app/drizziq/SunriseCard.kt),
[MainActivity.kt](../../app/src/main/java/app/drizziq/MainActivity.kt),
[Weather.kt](../../app/src/main/java/app/drizziq/Weather.kt) and
[SolarCalculatorTest.kt](../../app/src/test/java/app/drizziq/SolarCalculatorTest.kt).

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
| Background work | Executors, Handler and generation tokens; an owned bounded solar worker already exists. Keep I/O/math off the UI thread and reject obsolete work before expensive processing as well as before display. |
| Navigation | Fixed Today / Week / Radar / Places bottom tabs. Extend the existing sun card in Today with details; do not add another tab implicitly. |
| Places | Keyless city search, optional one-shot Android location and saved favourites; no manual-coordinate entry. Reuse `PlaceChoices` and `choose()` only for explicit selection. Search uses debounce/cancellation, explicit result selection and Retry-After; detail-date changes must not start a city search. |
| Coordinates and zones | `Place` stores full coordinates, zone and Open-Meteo label provenance. `Zones` validates zone IDs at entry/storage; `ZoneLookup` works offline with an unresolved-zone prompt. Use the evaluated place's civil date, not the phone or forecast station zone. |
| Weather | Direct DWD MOSMIX station forecasts, matching-station retention and a 50 km inclusive limit. The current working tree separates healthy 30-minute forecast checks from five-minute visible-radar polling; issue freshness remains nine hours for forecasts and twenty minutes for radar. Open-Meteo is geocoding only. |
| Map | Dedicated precipitation radar: custom OSM renderer, persistent camera, fixed timeline, incremental loading and one playback loop. Satellite/temperature products and WMS requests were removed. It is not a general spot/navigation screen. |
| Storage/distribution | Private place preferences and file caches; no terrain/cloud backend or pack distribution has been defined. Public GitHub APK releases/source access do not establish a terrain/cloud data service. |
| Build/CI | JDK 21, SDK platform 37, Build Tools 36.0.0, checked-in Gradle wrapper, Python standard-library tooling, strict lint/ktfmt and GitHub CI running `tools/verify`. Tagged builds publish signed debug APKs; local release APKs are unsigned build checks. |
| Design | One dark HUD palette. Editable SVGs in `artwork/icons/` generate vectors and `IconAssets.kt`; edit the SVG sources. Runtime code may rotate/tint vectors; Canvas is for dynamic paths/maps/charts, not icon shapes. |
| Sources | Free/public sources only. Any account or API key, including a free registration, needs explicit approval first. No credentials are embedded in the app. |

Follow [AGENTS.md](../../AGENTS.md), including its data-source guidance. Preserve the current selection, favourites, search
attribution, radar camera, connectivity and permission settings during ordinary
checks. Existing app accounts are not approval for a new data account.

## 3. Delivery increments

| Increment | User-visible scope | Completion gate |
| --- | --- | --- |
| A0 — Local sun times (implemented) | Today's local sunrise/sunset card with standard-horizon and polar/no-event semantics. Works offline and outside DWD forecast coverage. | Existing calculator/card, reference tests and phone checks are the baseline to preserve. |
| A1 — Sunset detail (remaining) | Extend the existing card with today/tomorrow/following-date selection, true-north sunset bearing and separate available MOSMIX cloud context. No new service, permission or runtime library. | Focused JVM tests for added behavior and a short affected phone check. Run the full local gate once before release, not after each edit. |
| B0 — Data proof | Developer-only prototype for a bounded Munich-area pilot: one licensed elevation source, candidate extract, horizon calculations and measured pack size/error. No production spot recommendations yet. | Reproducible generator, source/licence/access audit, fixture validation, published coverage and measured device budgets. |
| B — Regional horizon/spot finder | Horizon evidence at supported positions and a ranked list of eligible nearby candidates, with a map preview and explanations. Unknown outside validated pilot coverage. | B0 passed; pack distribution chosen; geometry/uncertainty/access contracts below implemented. |
| C — Spatial cloud context | DWD grid fields along the sunset direction, displayed as model evidence; any ranking contribution requires validation. | A working DWD decoding/data-delivery proof plus an explicit operations/distribution decision. |
| Later | Routing/travel times, detailed buildings/canopy, notifications, satellite nowcasting and wider regions. | Separate specs and any required source/account approvals. |

Do not silently replace B/C with a single-station “sunset quality” score.
Do not imply that a Germany-wide 25 m horizon database or daily tile service
already exists. There is no fixed implementation-duration promise.

## 4. Interaction and presentation

Increment A1 extends the existing sunrise/sunset card in Today with a Details
action. Preserve both existing event times and add local date/time, true bearing
clockwise from north and an SVG-backed direction glyph in the detail. Reuse
the sunrise/sunset assets and rotate a suitable vector rather than drawing
new icon shapes in Canvas. Astronomy remains available outside MOSMIX's 50 km
coverage or when weather loading fails; Week's existing layout stays focused
on daily forecasts.

Details has a three-date selector using the selected place's zone. Default to
today; after today's sunset show “Sunset has passed” and offer tomorrow rather
than silently changing the requested date. Show “No sunset on this date” for
polar cases, and distinguish a skipped local date (`NO_LOCAL_DAY`) from polar
day/night and calculation failure. Never display a made-up time or bearing
when no sunset event exists. No device-heading sensor or magnetic compass is
required: label the bearing as true north referenced. The selected detail date
must survive unrelated weather/radar callbacks; the Today card continues to
describe today even while another date is previewed in the detail.

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
into radar playback or reintroduce removed WMS/product-selection code. Opening
a spot preview must not invoke radar loading/playback. Share useful tile storage
only if it preserves independent cameras, cache validation, visible-only tile
requests and linked OSM attribution. Dense cards, charts and markers must fit
the existing dark language and expose accessible text.

Radar behavior lives in its implementation and tests, not in a separate
proposal. Re-read the code before borrowing map/cache infrastructure: avoid
queues of obsolete work and progress callbacks that rebuild unrelated UI.

## 5. Astronomy contract

Extend the existing pure Kotlin `SolarCalculator`, with explicit position,
civil date and IANA zone inputs and UTC epoch-millisecond event instants as
outputs. `java.time` is available through core-library desugaring. Keep the
documented NOAA/Meeus-style calculation and fixed independent references;
do not introduce another calculator, runtime astronomy dependency or remote
calculation service.

`SolarDay` currently contains event times and `SolarState` only. Add a tested
azimuth result for an actual sunset event in A1. For B, expose a small pure
solar-position/path API when needed, sharing the existing coordinate math
instead of duplicating the private elevation calculation. Preserve interruption
checks and existing event-time reference tolerances while refactoring.

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

### A1/B: existing MOSMIX context

`Hour.values` contains only explicitly retained MOSMIX fields. The committed
`munich-20261022.kmz` fixture contains `N`, `Nl`, `Nm` and `Nh`, but only `N`
is currently retained by `Mosmix.fields` in `Weather.kt`. Extend that whitelist
with focused parser/unit/range tests before exposing layers. Verify meanings
and validity semantics against
[DWD's element definitions](https://opendata.dwd.de/weather/lib/MetElementDefinition.xml).
Presence in one fixture does not guarantee availability for all products.

Use only a matching, in-coverage forecast for the evaluated place. Show the
two real hourly values bracketing the event when present, with their times;
do not extrapolate beyond coverage, silently fill missing layers or treat a
past event as covered by a future forecast. Missing one side remains incomplete.
Keep issue/reception timestamps and stale state. The three date choices are
astronomical options, not a promise of three complete weather days.

Use actual forecast validity instants around the selected sunset, not
`Forecast.current(now)`: the header deliberately chooses the nearest latest-issue
hour within one hour, which may be in the future. Do not limit sunset context
to Today's midnight-through-noon-tomorrow display window; later hours remain
available in the forecast. Preserve fractional-offset validity instants instead
of rebuilding them from integer local-hour labels.

`Forecast.earlier` can contain preceding-issue hours used to cover local
midnight. They remain forecasts, not observations; the latest issue wins when
merging validity instants. `Hour` does not retain a per-hour issue timestamp.
If A1 uses earlier values, identify them as preceding-issue context and add
source-issue metadata where an exact issue display is needed. Do not label
every merged hour with the latest run's issue time. Read retained data without
changing the successful-download timestamp or starting a request per date click;
use the existing forecast refresh/retention policy.

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
add a wrapper weather provider as part of A1/B. Existing GitHub APK CI does
not provide a scheduled cloud-data publication pipeline.

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
current acquisition exposes accuracy, elapsed-realtime timestamp and provider
in `PositionFix`, but `PlaceChoices` currently converts it to a `Place` without
persisting that metadata. If B uses device fixes, preserve suitable optional
accuracy/acquisition-time metadata backward compatibly and keep the one-shot
policy. Do not persist elapsed-realtime values as wall-clock timestamps or
compare them across device reboots. Older/search-derived snapshots have unknown
accuracy. Approximate positions spanning materially different horizons must
produce uncertain or
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
label edits. Current `Place`/`PlaceStore` persist `geocodeId` and
`geocodeLabelEdited`; `PlaceCredit` renders Open-Meteo/GeoNames credit.
Extend origin metadata and credit rendering backward compatibly for OSM;
do not put an OSM ID into the Open-Meteo-specific `geocodeId`, display a false
Open-Meteo credit, or discard OSM label provenance. Coordinate changes clear
derived-place provenance as in the current `Place.withCoordinates` contract.

Use developer extracts for the pilot. A public Overpass endpoint is not an
assumed production backend: its operator's guidance explicitly discusses this
limitation. Any later runtime query must disclose the transmitted search area,
use bounded explicit requests/caching and a verified service policy. No tile
stitching, mass scraping or public routing-demo dependency.

## 9. Android ownership, packs and offline behavior

Extend existing solar types and introduce horizon/ranking types only when tests
and implementation need them. A1 starts from `MainActivity`'s `SolarKey`,
`solarWorker`, `solarGeneration`, `solarFuture` and `ensureSolar`. The current
key is always rebuilt from today's date during rendering; detail-date selection
needs explicit state so unrelated rendering cannot reset it. Reuse one solar
calculation path, retaining separate results/keys for today's card and the
detail date where needed. Key cached work by evaluated coordinates/zone/date,
not by the mutable favourite label.

Preserve existing cancellation and generation invalidation on place changes,
`onStop` and destruction. Detail dismissal/date changes invalidate detail work
without discarding today's still-matching result. B can add a bounded owned
worker if horizon/ranking cost warrants it; reject obsolete queued work before
expensive evaluation, then recheck before rendering or committing caches. Do
not monopolize weather, radar decoding or the small solar worker with a full
regional search. Close any new worker with its owner.

Reuse `Reading`-style explicit states for fetched evidence. Astronomy is a local
calculation, not “LIVE DWD”. A failed terrain/cloud refresh retains only the
matching place/date/model evidence with original timestamps and a stale label.
Never carry a previous place's verdict into a new selection.

No Room, WorkManager, foreground service, background location or new automatic
periodic sync is needed in A1/B. Reuse the existing forecast's freshness and
refresh policy for station context. A bounded pilot pack can ship as an app
asset if its measured size is acceptable. Larger downloadable packs require
a deliberate public distribution decision; existing APK release hosting alone
does not settle pack updates, retention, integrity or availability. Do not
hand-write a general GeoTIFF decoder for Android.

Keep ordinary project Python tooling standard-library-only. B0 may propose an
isolated developer preprocessing environment with pinned geospatial tools after
reviewing licences and reproducibility; it is not an implicit new build or
runtime dependency. Stage and validate all outputs before replacement, like
`tools/update_metadata.py`. Require an explicit schema,
byte order, signed angle/height units, dimensions/count limits, nodata/masks,
checksums, source/datum/revision and attribution. B0 chooses exact encoding
after measuring a real pilot, not an assumed nationwide tile budget.

Cache keys include exact evaluated coordinates, date/zone, observer assumptions,
source/model/pack revisions and algorithm version. A camera position or label
is not an evaluation key. Show geographic/temporal coverage honestly. Clearing
feature caches must not clear places/favourites. If runtime downloads arrive,
use approved HTTPS endpoints, bounded redirects/timeouts/decoded sizes,
atomic writes, explicit refresh and no automatic retry loops. `Http.allowed`
currently permits DWD weather, OSM tiles and the existing geocoding endpoint;
terrain/derived-cloud hosts need an explicit narrow policy extension with
tests when chosen. Do not broaden it to arbitrary user-provided URLs.

Give any feature cache its own measured budget and retention policy. Publish
validated files before switching a manifest; only clean up files protected by
the old manifest after the new manifest is saved successfully. Failure must
leave useful matching data readable. Keep access recency separate from
successful receipt/validation timestamps, as in the existing tile-cache policy.

Offline: astronomy works; saved packs support only covered geometry; valid
matching saved weather keeps its cached/stale state. Missing components remain
unavailable. Device positions are resolved locally, but tile/pack requests
reveal approximate areas to hosts and IP addresses are observable. Do not
repeat the draft's “no personal data leaves the device” claim. Do not log
precise coordinates, full URLs or search text.

## 10. Tests, acceptance and measurement

Follow test-first development for pure behavior when practical: smallest
meaningful regression test, observed assertion failure, implementation and
focused green checks. While iterating, run only affected JVM classes, tooling
tests or phone phases. Documentation-only changes need no app tests.

Before tagging/publishing, run `./tools/verify` once and a short smoke on the
usual phone, with targeted checks for changed behavior. GitHub CI in
[ci.yml](../../.github/workflows/ci.yml) runs the same local gate on main pushes
and also validates/publishes tagged APK builds. Use fixed fixtures and fake
clocks/network/storage; no live requests or sleeps in JVM tests. Do not add a
new framework, device matrix or aesthetic layout assertions for this feature.

- **Astronomy:** independent reference cases, solstices/equinoxes, hemispheres,
  zone/DST boundaries, polar no-event outcomes, true bearing wrap and requested
  local day. Extend `SolarCalculatorTest` rather than replacing its USNO
  time references. Add bearing/path reference cases as those APIs arrive.
  Missing weather cannot hide the local result.
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
- **Phone:** briefly inspect card/detail/date navigation and accessible glyphs;
  exercise no implicit location prompt, latest-date selection during unrelated
  updates, cancellation/recreation, offline states and B's preview isolation.
  Existing `day-forecast` checks the Today-only sun card;
  `places-integration` includes sun times outside forecast coverage. Add a
  focused phase only for new behavior that needs one. The runner defaults to
  isolated fixtures and restores places/favourites/camera, including preference
  backups. `--places-integration` and radio-changing `--full` remain explicit
  opt-ins; never toggle phone radios to test the runner itself.

First measure the existing solar calculation and new detail response on the
usual OnePlus 5T when available; B0 adds pack loading, ray evaluation and a
bounded candidate search. Use fixed inputs and record the actual build/device.
Use the normal signed debug installation for UI checks; local release APKs
are unsigned build checks, and must not trigger uninstalling or changing the
existing signing identity. No new benchmark infrastructure is needed.

Provisional experiment targets remain cached detail within two seconds and
100 candidates within 300 ms off-main. These have not been measured or accepted
as release gates; reassess after a real pilot. Report representative latency,
APK/pack/download size and peak memory; add percentile measurements only when
needed to understand variability. A missing phone blocks device measurements
only, not pure tests or documentation work.
Keep the pilot bounded and measure before committing to a 50–100 MB cache or
1–2 MB daily transfer claim. No unlimited global prefetching.

Before describing B as useful for recommendations, compare a small diverse
set of open horizons, valleys and urban sites with observations at recorded
positions/times. Record resolution/uncertainty and failures. No photo upload,
feedback endpoint, tracking or calibration crowdsourcing is implied.

## 11. Remaining decisions and handoff

The next user-visible increment is **A1**, extending the already implemented
A0 calculator/card. It needs no new service, account or runtime library. Start
with sunset bearing and the three-date detail; add supplied MOSMIX cloud context
with a tested whitelist extension and explicit missing-data handling. B0 can
independently investigate data feasibility without shipping spot recommendations.

B0 must settle the pilot extent, compatible observer-ground source, candidate
extract licence, actual pack size and achievable uncertainty.
B cannot claim reliable building/tree visibility before those checks pass.
C additionally needs a hosting/operations decision and a validated spatial
cloud interpretation. Leave those explicit rather than guessing credentials,
infrastructure or scientific thresholds during UI implementation.

Update `AGENTS.md`, bundled notices, README and relevant tests when the
corresponding increment is implemented; refresh the README screenshot if the
UI changes materially. Keep source texts/licences and the public upgrade
signing identity intact. Do not mark A1/B/C shipped or bump/publish a version
because this document was refreshed.

## 12. References and review dates

Original external-source review: **2026-10-31**. The **2026-11-05** revision
checked the current local implementation, test/phone tooling and CI; it also
revisited NOAA calculation details, the USNO API documentation, DWD D2/EU
low-cloud listings, the Copernicus AWS registry/provider README, Overpass
guidance and the OSM tile policy. Directory listings establish source discovery,
not a decoded data pipeline or a newly validated licence for redistribution.
The exact DEM licence and ICON manual below remain original-review references
to recheck during B0/C. The DWD element XML could not be rendered by the web
reader in this revision; validate it directly when adding cloud fields.

- [NOAA calculation details](https://gml.noaa.gov/grad/solcalc/calcdetails.html)
  and [solar equations](https://gml.noaa.gov/grad/solcalc/solareqns.PDF):
  conventional event definition and calculation limitations. The online
  calculator is no longer maintained; use fixed independent references.
- [USNO API documentation](https://aa.usno.navy.mil/data/api): independent
  reference source. Existing fixed rise/set cases are stored in
  [SolarCalculatorTest.kt](../../app/src/test/java/app/drizziq/SolarCalculatorTest.kt);
  neither the app nor tests call the service at runtime.
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

Local integration references:
[PlaceChoices.kt](../../app/src/main/java/app/drizziq/PlaceChoices.kt),
[PlaceStore.kt](../../app/src/main/java/app/drizziq/PlaceStore.kt),
[PlaceCredit.kt](../../app/src/main/java/app/drizziq/PlaceCredit.kt),
[ForecastLookup.kt](../../app/src/main/java/app/drizziq/ForecastLookup.kt),
[ReadingPolicy.kt](../../app/src/main/java/app/drizziq/ReadingPolicy.kt),
[Repository.kt](../../app/src/main/java/app/drizziq/Repository.kt),
[artwork instructions](../../artwork/icons/README.md),
[phone runner](../../tools/verify_phone.py) and
[PhoneExercise.kt](../../app/src/androidTest/java/app/drizziq/PhoneExercise.kt).
