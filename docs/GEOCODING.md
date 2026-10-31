# Place search, current location, and nearest MOSMIX point

Revised specification, 2026-10-30; implemented in 1.2.0 on 2026-10-31.
It supersedes `tmp/spec-nearest-station-index.md`. See
[1.2.0 verification](VERIFICATION-1.2.0.md) for measured results and device limits.

Current UI revision: Today/Week replace the Forecast tab. Manual coordinate
entry has been removed by the subsequent UI request; saved coordinates still
load normally, and unresolved time zones now use a zone-only prompt. Device
snapshots display the locally resolved station name instead of their internal
`Current location` label. Those changes supersede the corresponding original
interaction details below; the source, privacy, caching and coverage rules remain.

## 1. Scope and decisions

Add **Search city** and **Use current location** to the Places view. Keep
coordinate entry, favourites, and the Munich default. Every entry path produces
a `Place` and uses the existing `MainActivity.choose()` selection path.

The first version uses:

- Open-Meteo for explicit city searches, with a user-selected result.
- Android platform APIs for one foreground location fix on demand.
- The existing bundled `app/src/main/assets/stations.tsv` for MOSMIX lookup.
- A maximum forecast-point distance of **50 km**, inclusive, for every entry path.
- The existing Activity, Repository, executors, Handler, and native Views.

Weather remains DWD-only. Open-Meteo supplies place metadata only. No new
runtime dependencies, ViewModels, Flows, coroutines, WorkManager, database,
backend, or weather proxy are needed for this feature.

Autocomplete, reverse geocoding, background location, continuous following,
runtime station-table downloads, CDC observations, POI products, warnings,
interpolation, and elevation-based ranking are outside this version.

## 2. Existing code to extend

| Existing component | Change |
| --- | --- |
| `MainActivity.locations()` | Add search and current-location buttons; retain coordinates and favourites. |
| `MainActivity.choose()` | Continue to persist a successful choice, update the map, and select forecast data. |
| `Place`, `PlaceStore` | Preserve label, full coordinates, IANA zone, and existing saved data; add optional search provenance. |
| `Station.distance()` | Reuse the current clamped haversine calculation. |
| `Repository.select()` | Use a pure nearest-point rule, enforce coverage, and preserve obsolete-request protection. |
| `Http` | Add a narrowly allowed geocoding endpoint, retaining weather/tile restrictions. |
| `tools/update_metadata.py` | Keep its MOSMIX source and TSV format; test and harden parsing when touching it. |

Small pure functions/data classes may live in `Geocoding.kt` and
`StationIndex.kt`. A small `CurrentLocation.kt` helper owned by the Activity
may own platform listeners. Introduce only seams needed to fake networking,
time, and location events in meaningful tests.

## 3. City-search interaction

1. **Search city** opens a native dialog with a text field, Search action,
   results, status, and Cancel. The keyboard search action submits too.
2. Trim the query and collapse whitespace; accept 2–120 Unicode code points.
   Invalid input shows an inline explanation and makes no request.
3. Submit only on Search. Typing makes no requests. Changing the query
   immediately invalidates old results and pending responses.
4. Show up to ten candidates in provider order, with name, available region,
   country, and coordinates. Distinguish same-name places. Never silently
   choose a result, even if there is only one.
5. Tapping a candidate selects its returned coordinates directly. Never
   search its display name again to obtain a supposedly better result.
6. Build a compact label from name and available region/country, capped to
   the existing 60-character limit without splitting a surrogate pair. Keep
   disambiguating details accessible in the result list even if text is ellipsized.
7. Selection calls `choose()`. Saving a favourite remains a separate action.
   Cancel, no matches, and errors leave the selected place, favourites,
   forecast, and map camera unchanged.

An out-of-coverage place can still be selected and saved. Its forecast shows
the explanation in section 8; radar independently checks its coverage.

## 4. Geocoding source and data contract

Use HTTPS GET, with UTF-8 query-parameter encoding:

```text
https://geocoding-api.open-meteo.com/v1/search?name=ENCODED_QUERY&count=10&language=de&format=json
```

Search globally; there is no implicit Germany filter. Keep `language=de`
fixed in this version. This is place-name search, not street-address search.

At the JSON boundary, retain a candidate's positive integer `id`, nonblank
`name`, finite in-range `latitude`/`longitude`, and optional `admin1`,
`country`, `country_code`, and `timezone`. Ignore unknown fields. Missing
`results` or an empty array means no matches. A wrong-shaped response or API
error object is a failure. Drop invalid individual candidates; if a nonempty
array yields none valid, report an invalid-response error. Deduplicate by
provider ID, keeping the first valid entry; display at most ten.

Keep coordinates as doubles without rounding. Use a returned zone recognized
by the device, otherwise the existing offline `ZoneLookup`. If neither yields
a recognized zone, ask for an IANA zone using the existing coordinate form
rather than assuming Berlin. Daily grouping uses the place's zone, never the
forecast station's zone.

Add optional `geocodeId` provenance to `Place` and its stored JSON, for example
`"open-meteo:2950159"`. It supports attribution, not station resolution or
favourite identity. Existing stored places without it load normally. Label
edits preserve provenance; coordinate changes clear it. Manual/device-location
places have no geocoding provenance. Reopening favourites needs only their
saved label, coordinates, and zone, with no provider lookup.

Source: [Open-Meteo geocoding API](https://open-meteo.com/en/docs/geocoding-api),
checked 2026-10-30. Change cache keys and fixtures when changing provider or
query parameters.

## 5. Use current location

**Use current location** obtains one position and selects a fixed place.
It does not follow movement or reacquire on restart.

- Declare foreground `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`.
  Request them together only after the button is tapped and access is absent.
  Accept approximate-only access; precise permission is optional and enables
  GPS where available, including without network access. No background
  permission, foreground service, or Google Play Services dependency is needed.
- Denial leaves the current place intact and explains that search/manual
  coordinates still work. If Android will no longer prompt, provide an
  app-settings action. Never repeatedly prompt without another explicit tap.
- Use `LocationManager`: consider enabled `NETWORK_PROVIDER` with coarse
  access and `GPS_PROVIDER` with fine access. Handle absent providers and
  revoked permissions. Location-disabled/no eligible provider shows a
  retryable explanation; never change device settings automatically.
- A usable fix has valid coordinates, reported finite positive horizontal
  accuracy of at most **5,000 m**, and elapsed-realtime age from **0 through
  120 seconds**, inclusive. Use monotonic time, not wall time. These are app
  acceptance limits, not accuracy guarantees.
- Check permitted enabled providers' last-known fixes first. Among usable
  fixes choose newest, then smaller accuracy, then provider name lexicographically.
  Otherwise request updates using API-24-compatible platform calls and accept
  the first usable fix. Acquisition times out after **20 seconds**, excluding
  time in the permission prompt. Do not relax fix quality at the deadline.
- Show progress and Cancel. Remove every listener and timeout on success,
  cancellation, failure, replacement, Activity stop, or destruction. An obsolete
  permission callback must not start acquisition. Returning from the permission
  dialog may proceed only for the still-current request while foreground.
  Backgrounding aborts acquisition; returning does not silently restart it.
- On success use label **Current location**, full fix coordinates, and offline
  zone lookup, with the same zone-error handling as city search. Show a success
  message including reported accuracy and explain that approximate position
  can affect which forecast point is nearest. Resolve coordinates locally,
  without reverse geocoding. A favourite saves this position as a snapshot.
- Timeout, poor fixes, and errors leave the selected place/data intact.

A new tap checks permission and freshness again. Store only the selected
snapshot, not location history. A persisted snapshot is not a fresh location fix.

References: [runtime permissions](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime),
[LocationManager](https://developer.android.com/reference/android/location/LocationManager),
and [Location](https://developer.android.com/reference/android/location/Location),
checked 2026-10-30.

## 6. Network, threading, and obsolete results

Reuse `HttpURLConnection` with 10-second connect and 20-second read timeouts
for geocoding, a 256 KiB decoded response limit, and the existing descriptive
app User-Agent. Permit HTTPS host `geocoding-api.open-meteo.com`, path
`/v1/search`, for this feature. Recheck and bound redirects; retain the existing
DWD/tile restrictions. Treat `429` and server errors as failures. Honour valid
`Retry-After` before another network submission; show when retry is possible
instead of sleeping a worker. No automatic retries/background searches in v1.

Geocoding/catalogue I/O runs off the main thread. Keep slow geocoding off the
two weather workers with one owned search executor; bound pending work to
the latest search. Location callbacks and UI state changes run on the main
thread. Close owned resources with their Activity/Repository.

Use a generation token for the whole pending place-choice interaction,
separate from existing forecast generation. Editing/cancelling search,
starting another selection method, choosing a favourite, or destroying the
owner invalidates pending search, permission, and location results. Check
the token before rendering, writing search cache, persisting, or calling
`choose()`. Cancellation/disconnection is best effort; tokens enforce correctness.
An obsolete operation must not replace a newer choice or touch a destroyed UI.

Expose success, no-match, and failure distinctly at the UI boundary. Catch
parsing/network errors there. If persisting a new choice fails, keep the
previous selection and offer retry.

## 7. Caching and offline behavior

Cache at most **20 successful nonempty search responses in memory** for
**30 minutes**, measured with an injected monotonic clock. Key by provider,
endpoint/parameter version, language, and query normalized with NFC, collapsed
whitespace, and `Locale.ROOT` lowercasing. Preserve accents and punctuation.
At exactly the expiry boundary an entry remains valid. Evict expired entries,
then least recently used when necessary.

Use valid cached results before requesting; mark them cached and allow explicit
Refresh to bypass them. Failed refresh retains valid cached results with an
explanation; a successful empty response removes the old entry and shows no
matches. Never cache errors. Rate limits do not block cache reads. Re-evaluate
expiry before displaying retained results after a failed refresh.

No search-cache file/database is needed. Process restart clears search history;
selected places and favourites continue using `PlaceStore`.

| Situation | Required behavior |
| --- | --- |
| Offline, coordinates or saved place | Resolve MOSMIX locally; show only matching saved weather, marked cached/stale. |
| Offline, city query with valid memory cache | Show cached candidates; require normal explicit selection. |
| Offline, unseen/expired query | Explain that search needs a connection; offer coordinates or favourites. |
| Offline, Use current location | Try permitted enabled providers; a usable fix is not guaranteed. |
| Restart with a saved selection | Use saved coordinates/zone without geocoding or acquiring location. |

No place-to-station cache is needed: one linear scan is cheap at this catalogue
size. Retain the station-keyed KMZ cache and existing freshness rules.

## 8. Nearest MOSMIX point and coverage

Keep the existing `Station` shape, including string ID and nullable double
elevation. IDs are opaque, case-sensitive strings: preserve zeros and
alphanumeric values such as `P0489`, `P659`, and `X104`. There is no numeric-ID
field or cross-product merge. This index serves MOSMIX only.

The pure API needs one result, not an unused top-k list:

```kotlin
data class StationHit(val station: Station, val distanceKm: Double)

class StationIndex(stations: List<Station>) {
    fun nearest(place: Place): StationHit?
    fun nearestInCoverage(place: Place, maxKm: Double = 50.0): StationHit?
}
```

- Keep `Place` coordinate validation at input boundaries. The index constructor
  rejects invalid coordinates, blank IDs/names, non-ASCII-alphanumeric IDs,
  and duplicate IDs. An empty list is allowed for tests.
- Reuse haversine with radius **6371.0088 km** and intermediate clamping to
  `[0, 1]`. `nearest` returns null for an empty index, otherwise the minimum
  distance. Break exact computed-distance ties by case-sensitive lexicographic
  ID order. No epsilon may cause a farther point to win.
- Results depend on coordinates and catalogue content, independently of row
  order, locale, wall clock, or network state.
- `nearestInCoverage` requires a finite nonnegative radius; invalid radii
  throw `IllegalArgumentException` in this pure API. Return the nearest hit
  only when `distanceKm <= maxKm`, otherwise null. Zero radius is valid.
- Use a linear scan without sorting/spatial indexing. Load/validate the asset
  once per Repository on a worker and retain it in memory. Do not persist a
  redundant JSON/SQLite copy that still requires loading/parsing.

Keep the forecast point's name, ID, coordinates, elevation, and distance visible
using the existing display. Values remain station forecasts; proximity does not
guarantee local representativeness. Summit/airport ranking is unchanged. City
elevation must not replace station elevation. For device location, distance is
from the accepted position estimate.

Outside coverage show **No DWD forecast point within 50 km**. Keep the chosen
place, allow favourites, clear the previous place's forecast, and skip its KMZ
fetch. A missing/corrupt catalogue instead shows **Forecast station catalogue
unavailable**. Neither condition disables radar or coordinate entry.

A different place must never inherit the previous place's forecast as its own.
If both resolve to the same station, its matching cached product may be reused
with the new place's distance/zone. Continue rejecting obsolete forecast
callbacks and checking fetched KMZ station IDs. Fetch failure retains only
matching cached data with its original timestamp, marked stale; do not silently
switch to another forecast point.

## 9. Catalogue ownership and updates

Keep the [official MOSMIX catalogue](https://www.dwd.de/DE/leistungen/met_verfahren_mosmix/mosmix_stationskatalog.cfg?view=nasPublication&nn=16102)
already used by `tools/update_metadata.py`. Source coordinates use degrees/minutes,
not decimal fractions of a degree. Decode its fixed-width source as Latin-1
using the existing slices; convert minutes, preserve signs, and validate minute
components/ranges. Keep generated UTF-8 TSV columns: ID, name, decimal latitude,
decimal longitude, optional elevation. Blank elevation is absent; malformed
nonblank numbers are errors. Preserve name spaces and accept CRLF.

Test MOSMIX fixtures, not the original CDC regex. CDC uses a different namespace:
Munich city is CDC `03379`, MOSMIX `10865`. Observation `bis_datum` is not a
MOSMIX activity flag; never filter by today's date. Catalogue membership makes
a point eligible but does not guarantee a particular product fetch succeeds.

Updates are deliberate developer-run regenerations shipped with app releases.
Before replacing generated assets, validate every nonblank station row,
coordinate conversion, unique IDs, more than 2,000 rows, and section 11's fixture
examples. Invalid rows fail generation with line numbers. Fetch and validate
all outputs before replacement; a source/validation failure leaves existing
outputs intact. Review count/geographical changes, not just a nonempty result.

No runtime downloaded index, ETag protocol, schema fallback, or weekly job is
included. An app update uses its bundled catalogue in the new process. Do not
persist station IDs in places. Offline retirement detection is unavailable:
older builds may try removed points and must handle failure honestly. A new
catalogue re-resolves saved coordinates. Use the asset SHA-256 as data revision
for fixtures/diagnostics; app/schema version alone does not identify content.

## 10. Attribution and privacy

Before submission, explain that search text goes to Open-Meteo. Show linked
Open-Meteo/GeoNames attribution beside results and derived place labels,
including favourites; put source/licence links in Data & licences. Retain
provenance after label edits. Provide an action to clear the memory search
cache without deleting selected places/favourites. Preserve DWD/map attribution.

The [API terms](https://open-meteo.com/en/terms) restrict free access to
non-commercial use and impose quotas. This personal, ad-free version uses that
service; commercial distribution/provider changes need a separate decision.
Follow the [attribution licence](https://open-meteo.com/en/licence), including
links and an indication of edited labels. Recheck terms before shipping;
keyless access does not mean unrestricted access.

Explain location's purpose in the permission rationale. Resolve positions to
stations on-device without sending coordinates to a geocoder. Android location
providers may themselves use network services; acquisition is not guaranteed
offline. Attach no user/device identifier and keep the existing non-tracking
policy. Do not log queries, full request URLs, or device coordinates. Selected
snapshots use the existing private place storage.

## 11. Tests and acceptance

Follow `AGENTS.md`: meaningful failing tests first, implementation, focused
checks, full local gate, and affected phone checks. This documentation change
needs no artificial behavior test.

Use JUnit 4 with fixtures/fakes and Python `unittest` for tooling. No unit-test
live requests/sleeps. Keep rules independent of Android. If JVM JSON tests need
`org.json`, add a pinned test-only JVM implementation; do not enable framework
default returns or add a runtime library just for tests.

Required coverage:

- Query normalization/encoding (`München`, ampersands, commas), length limits,
  optional/unknown fields, malformed JSON/coordinates, absent/empty results,
  invalid candidates, duplicates, and distinguishable same-name cities.
- Zone fallback/failure, full coordinate precision, provenance, old place JSON,
  relabelling, and selected/favourite restart persistence.
- Cache expiry boundary/LRU, failed refresh retention, empty refresh, offline
  misses, HTTP errors/size limits, redirect restrictions, `Retry-After`, and
  obsolete responses, using fake networking and time.
- Denied/approximate/precise/revoked location access; disabled/missing providers;
  fresh/stale/future/poor/missing-accuracy fixes; exact age/accuracy boundaries;
  timeout, cancellation, listener cleanup, and callbacks after replacement,
  stopping, or destruction. Fake fixes/time; exercise the platform adapter on-device.
- MOSMIX fixed-width conversion with negative coordinates/minute boundaries,
  Latin-1/multiword names, CRLF, absent elevation, alphanumeric/zero-prefixed
  IDs, invalid rows/duplicates, and failed generation preserving existing files.
- Empty index, identical positions, shuffled rows, numeric/alphanumeric ties,
  poles/antimeridian, Berlin–Munich distance about 504 km, invalid radii, and
  just-inside/exact/outside coverage.
- Catalogue changes re-resolve saved coordinates; out-of-coverage/catalogue
  errors leave radar usable; same-station cache reuse, failed refresh retention,
  and obsolete forecast completion preserve correct place/zone/timestamps.

Reference checks against the currently bundled asset, independent of geocoding:

Asset SHA-256:
`713df55d858359b021932d84b8dd9c2832b7d73b592e301b2f410c1d7a5b7f3c`.

| Coordinates | Expected nearest ID | Approximate distance |
| --- | --- | --- |
| Munich centre `48.137, 11.575` | `10865` (MUENCHEN STADT) | 4.52 km |
| Berlin centre `52.520, 13.405` | `10389` (BERLIN-ALEX.) | 0.87 km |
| Hamburg centre `53.551, 9.993` | `P0489` (HAMBURG INNENSTADT) | 0.65 km |
| `0, 0` | No hit within 50 km | — |

Pin the asset hash with fixture expectations; update them only for reviewed
catalogue changes. Do not assert localized station-name spelling. `10338` is
Hannover; Munich airport is `10870`.

Use `PhoneExercise` conventions and semantic selectors for search/select/cancel,
disambiguation, location permission/failure states, recreation during pending
work, saved-place restart persistence, distance display, and radar recentering.
Check approximate access on API 31+ when available; report unavailable device,
API, or provider coverage. Ordinary smoke checks preserve selected data,
favourites, connectivity, location settings, and existing permissions. Actual
selection/saving, permission changes, or settings changes belong to an opted-in
integration/manual run. Do not assume the current full-phone runner restores
new permission/location settings; add restoration and fake-subprocess tests
before automating such changes.

Measure catalogue loading and lookup in a release build on the available phone.
Lookup target is under 5 ms at current size; record device/sample timing. No
unmeasured sub-millisecond claim, hard memory ceiling, primitive-array rewrite,
or spatial index is required.

## 12. Implementation order and completion

1. Extract/test nearest lookup and coverage using the existing catalogue/model.
2. Add/test explicit geocoding, selection, zone/provenance storage, memory cache,
   and request-generation handling.
3. Add/test current location, permission handling, fix policy, and cleanup.
4. Integrate native UI, attribution, errors, and device exercises. Update
   `docs/SPEC.md`, `docs/DATA.md`, notices, and UI text claiming no GPS is used.
   Permission remains unnecessary for city/manual entry.
5. Run focused checks, then `./tools/verify` (or `--offline` with cached
   dependencies) and affected opted-in phone checks. Report blocked checks
   separately. No CI or new analysis framework is assumed.

Future runtime catalogue refresh needs a separate design for data revisions,
atomic replacement/rollback, quality checks, and update precedence. Future
products need their own eligible station sets and identifier mappings.
Neither is an implicit part of implementing this version.
