# Additional layers on the radar map

Proposed feature, 2026-10-31, against the Drizziq 1.4.0 working tree. DWD
service details were checked on 2026-10-30. This document does not implement
or enable new overlays, and it does not authorize a new data provider.

## Intended result

Add optional, single-select overlay layers to the radar page:

| Overlay | DWD product | Practical source | Cadence | Finding |
| --- | --- | --- | --- | --- |
| Clouds / satellite | METEOSAT RGB day/night, Europe | WMS `Satellite_meteosat_1km_euat_rgb_day_hrv_and_night_ir108_3h` | 3 h | Available; good daytime image; coarse cadence |
| Temperature | ICON-EU 2 m temperature | WMS `Icon-eu_reg00625_fd_gl_T` | 1 h | Available; opaque contour bands |
| Wind | ICON 10 m mean wind | WMS `Icon_reg025_fd_sl_UV10M` | 1 h | Wind barbs only, not a shaded speed field |

All three are keyless DWD GeoServer layers under CC BY 4.0 with a source note.
The endpoint is `https://maps.dwd.de/geoserver/dwd/wms` (WMS 1.3.0), separate
from the existing `opendata.dwd.de` host.

Recommendation: ship **clouds and temperature** first. Treat **wind** as an
experimental barb overlay because DWD does not expose a shaded mean-wind
raster in the examined capabilities. Add **lightning density** next, because
it matches the radar timeline and is the most useful storm overlay after
precipitation.

Keep precipitation radar as the primary layer. The default launch state
remains radar-only, and only one non-radar overlay may be active at a time.

Important limitations:

- Satellite is **3-hourly**, not a radar-like five-minute animation. It adds
  context, not nowcasting.
- DWD's WMS returns **rendered rasters**, not raw values. Temperature is an
  opaque contour fill; satellite is an opaque RGB image; wind is a transparent
  barb layer. The client cannot recolour or interpolate values it does not get.
- The ICON-EU temperature layer failed once with the implicit `time=current`
  request. Explicit `TIME` and `REFERENCE_TIME` parameters worked. The client
  must send explicit dimensions and tolerate WMS `ServiceException` responses
  that arrive with HTTP 200.
- Wind has no shaded speed field in the public WMS. A wind overlay therefore
  means sparse barbs. If those are not legible, omit the overlay instead of
  inventing a data source.

## Verified source details

### Endpoint and licensing

`GET https://maps.dwd.de/geoserver/dwd/wms?service=WMS&version=1.3.0&request=GetCapabilities`
returned 988,181 bytes of XML on 2026-10-30. Supported image formats include
PNG, JPEG, GIF, TIFF, GeoTIFF and SVG. Useful layers publish `time` and, for
model layers, `REFERENCE_TIME` dimensions, plus EPSG:3857 bounding boxes.

[DWD's reuse terms](https://www.dwd.de/copyright) say freely accessible
geodata and geodata services may be reused under CC BY 4.0 with a source note.
Satellite products are derived from EUMETSAT/NOAA data, which DWD lists as
third-party sources. Confirm the exact required satellite credit before
release. OSM attribution remains required for the basemap.

### Clouds / satellite

Layer: `Satellite_meteosat_1km_euat_rgb_day_hrv_and_night_ir108_3h`

- Title: “Satellitenbild METEOSAT (RGB) Europa gesamt 3h”.
- `time` values observed from `2026-10-29T12:00Z` to `2026-10-30T21:00Z`,
  step `PT3H`.
- Published EPSG:4326 bbox: lon `-53.9941…76.9926`, lat `22.9984…54.0031`.
  A sample at 56° N still contained imagery, but declared coverage ends at
  54° N. Verify northern-Germany coverage during implementation.
- A 256×256 `GetMap` returned RGBA PNG with **alpha 255 everywhere**.
  `transparent=true` did not make the image transparent. The overlay needs its
  own alpha and, probably, a colour matrix.
- Daytime central Europe was a clear RGB composite with land, sea and clouds.
  Night was a dark grey IR image with low contrast on the HUD background.
- Fallback: `Satellite_worldmosaic_3km_world_ir108_3h`, a global 10.8 µm IR
  mosaic, 3 km, 3-hourly, opaque grey. It is coarser and less legible.

### Temperature

Primary layer: `Icon-eu_reg00625_fd_gl_T`

- Title: “Temperatur auf Höhen über Grund in m aus ICON-EU”.
- ICON-EU, 0.0625°, hourly, model runs at 00/06/12/18 UTC, steps +00…+120.
  Published EPSG:4326 bbox: lon `-23.53125…62.53125`, lat
  `29.46875…70.53125`; Germany is fully covered.
- `time` observed from `2026-10-29T12:00Z` to `2026-11-04T18:00Z`, step
  `PT1H`. The `elevation` dimension offers 2, 50, …, 500 m; default 2 m.
- The default style rendered as an **opaque** PNG with five colours over
  Germany: `#86B1D1`, `#96C6E3`, `#E6E6E6`, `#8292BD`, `#8172A8`. The palette
  is style-dependent; check the selected style before using these in a legend.
- `time=current` once returned a `ServiceException` (“No data available …
  modelrun 28.10.26 06:00, forecasttime 64:00”). The same request with
  `TIME=2026-10-30T22:00:00Z` and `REFERENCE_TIME=2026-10-30T18:00:00Z`
  returned an image. Treat `time=current` as unreliable for model layers.
- Fallback: `Icon_reg025_fd_sl_T2M`, global ICON at 0.25°, hourly, about eight
  days. Its sample was an opaque yellow/green banded raster.

### Wind

Layer: `Icon_reg025_fd_sl_UV10M`, style
`icon_reg025_fd_sl_uv10m_wmc_windbarbs`.

- Title: “Wind in 10m aus ICON”; abstract: mean wind at 10 m.
- Hourly `time`, six-hourly `REFERENCE_TIME`, global coverage.
- A 600×600 sample rendered **transparent wind barbs**, not a filled field.
  The barbs are thin, black and small; they are nearly invisible on the dark
  basemap without recolouring.
- `Aicon_reg025_fd_sl_UV10M` (“Mittelwind in 10m”) is also a barb layer, at
  3-hourly cadence.
- `Icon-eps_reg025_fd_pl_SP10M` rendered sparse contour lines with very low
  alpha. The gust-probability layer
  `icon-d2-eps_ecwarn_reg002_fd_sl_pVMAX10MgtX` was fully transparent.
- Conclusion: the examined public DWD capabilities do **not** expose a shaded
  mean-wind raster. A wind layer means barbs, or no wind layer.

### Error behaviour

`GetMap` errors can arrive with HTTP 200 and an XML body:

```xml
<ServiceExceptionReport version="1.3.0" ...>
  <ServiceException>Error rendering coverage on the fast path ...</ServiceException>
</ServiceExceptionReport>
```

The existing `Http.bytes` checks status and size but not content type or body.
Feeding such XML to `BitmapFactory` yields `null`, and the current tile path
reports a generic “Invalid map tile”. Overlay code needs a PNG-signature check
and a readable WMS error message.

## Current app boundaries

| Area | Existing implementation and consequence |
| --- | --- |
| Radar map | `RadarMap` is a custom `View` with a 64-entry base-tile `LruCache`, a three-thread executor, a 60 s failed-tile backoff, 256 px Web Mercator tiles, OSM base drawing, radar bitmap mesh, place marker and notices. |
| Radar data | `Repository` loads DWD RV HDF5 observations and forecasts. Radar freshness is 20 minutes; the timeline uses five-minute frames plus available nowcast frames. |
| HTTP | `Http.checked` permits only `tile.openstreetmap.org` and `opendata.dwd.de/weather/`. It keeps redirects on the approved endpoint and enforces size limits. |
| UI | `MainActivity` owns the radar panel, playback, timeline, legends, notices, attribution and accessibility text. There are no ViewModels, flows, coroutines or use-case layer. |
| Design | One dark HUD palette. Keep the existing cyan/amber/red accents and restrained typography. |

Follow [AGENTS.md](../../AGENTS.md) and [DATA.md](../DATA.md). No new runtime
dependency, permission, account or API key is needed for this feature.

## Interaction and time behaviour

Add a compact chip row inside the map viewport, top-left below the existing
notice area:

```text
[ CLOUDS ] [ TEMP ] [ WIND ]
```

Use native `TextView`/`Button` views styled with `Hud` tokens, about 32 dp
high, 11 sp monospace, with 8 dp gaps and corner radius. Tapping an inactive
chip selects it and deselects the previous overlay; tapping the active chip
turns overlays off. `contentDescription` names the overlay and selected state.
Chips must not intercept drag/pinch gestures outside their bounds and must
remain reachable while the map is panned.

When no overlay is active, the existing precipitation legend and notice are
unchanged. When active, add one compact line below the radar legend:

- Clouds: `CLOUDS · METEOSAT RGB · 3h · 21:00Z`
- Temperature: `2 m TEMP · °C` plus four to six swatches matching the chosen
  WMS style, with the valid hour.
- Wind: `10 m WIND · barbs · unit per DWD legend`. Confirm the barb increment
  and unit before showing a number.

The overlay valid time is shown in UTC (`HH:mmZ`). For model layers, also show
whether the step is an analysis or forecast when it differs from the radar
frame. Do not imply that a three-hourly satellite image is synchronous with a
five-minute radar frame.

Errors use a separate small notice, for example `Clouds unavailable · DWD`,
rather than replacing the radar stale/offline notice. While loading, show
`CLOUDS · loading…` or a subtle chip spinner without blanking the map.

The existing OSM attribution stays visible and linked. When an overlay is
active, add `© Deutscher Wetterdienst (DWD)` linking to the DWD copyright page.
For satellite, include the third-party source in the label or its content
description (for example `Satellitendaten: EUMETSAT`) after confirming the
required wording.

### Time selection

- **Clouds:** choose the latest satellite slot at or before “now”; never show
  a future slot. With a nowcast radar frame, keep the latest observation and
  label it “latest”. If the radar frame is older, keep the latest slot but
  show its own valid time rather than pretending it matches.
- **Temperature / wind:** choose the forecast step nearest to, and not after,
  the selected radar frame, floored to the layer cadence. Use the latest model
  run whose `REFERENCE_TIME` covers that valid time. If the frame is outside
  the available range, hide the overlay and show `No overlay for this time`.
- Changing radar frames within the same overlay bucket must not refetch tiles;
  only the timestamp text changes. Changing buckets starts a new tile set.
- Offline: keep the last successfully cached tile set for the current bucket
  and mark it `Cached overlay`. With no cache, hide the overlay and show
  `Overlay offline`.

## Implementation plan

### Pure overlay model

Add `app/src/main/java/app/drizziq/Overlay.kt` with an `enum class Overlay`
and JVM-testable helpers. Starting metadata:

| Overlay | WMS layer | Style | Cadence | Max tile z | Alpha |
| --- | --- | --- | --- | --- | --- |
| CLOUDS | `Satellite_meteosat_1km_euat_rgb_day_hrv_and_night_ir108_3h` | default | 3 h | 7 | ~110 |
| TEMPERATURE | `Icon-eu_reg00625_fd_gl_T` | default | 1 h | 5 | ~120 |
| WIND | `Icon_reg025_fd_sl_UV10M` | `icon_reg025_fd_sl_uv10m_wmc_windbarbs` | 1 h | 8 | ~220 |

The zoom caps are starting values based on native resolution (~1 km
satellite, ~6.25 km ICON-EU, ~25 km ICON). Stop requesting deeper WMS tiles
once the source cannot add detail; stretch the max-zoom tile beyond that.

Helpers should cover:

- `tileBounds(z, x, y)` for a 256 px Web Mercator tile in EPSG:3857.
- `validTime(overlay, targetTime, now, availableRange)` using the rules above.
- `referenceTime(validTime, runs)` for ICON runs at 00/06/12/18 UTC.
- `getMapUrl(overlay, validTime, referenceTime, z, x, y)`.
- `overlayTileKey(...)` including layer, times, zoom and tile coordinates.
- `parseServiceException(bytes)` returning the WMS error text.
- `attribution(overlay)`.

These rules must not depend on Android.

### HTTP allow-list

Extend `Http.checked` narrowly:

```kotlin
url.host == "maps.dwd.de" && url.path.startsWith("/geoserver/")
```

Keep the existing User-Agent, redirect, size and timeout behaviour. Extract the
predicate as an `internal` function so a JVM test can cover approved and
rejected hosts and paths without opening a socket. Do not allow arbitrary DWD
paths or arbitrary hosts.

### Radar map

`RadarMap` gains:

- overlay selection, valid time and reference time fields;
- a mirrored online flag;
- a separate byte-sized overlay `LruCache` (for example 8 MB);
- a second two-thread executor for WMS tiles, shut down in `dispose()`;
- a request path that builds the WMS URL, checks the PNG signature and decodes
  the bitmap;
- overlay drawing between OSM base tiles and the radar mesh.

Limit overlay concurrency to two so base-map tiles are not starved. Draw order:
OSM base → overlay → radar mesh → marker → notices. Use `p.alpha` for blending.
For wind, recolour the black barbs to `Hud.cyan`/white and hide the overlay
below the minimum zoom (for example 5) with `Zoom in for wind`. For satellite
and temperature, a mild contrast/saturation matrix may be needed; tune it
visually. Keep bitmap filtering enabled so stretched low-zoom tiles are not
blocky.

### Activity state

`MainActivity` owns the selected overlay, overlay times, chip row, legend,
notice and attribution label. `createRadar()` creates them; `renderRadar()` and
`requestFrame()` recompute the overlay bucket when the radar frame changes and
call `RadarMap.setOverlay(...)` only when the layer or bucket changes.
`updateRadarAccessibility()` includes the active overlay, valid time and
cached/offline state. Leaving the radar page keeps the selection in memory.
Persisting it across restarts is optional; default to no overlay on a fresh
launch.

No forecast `Repository` change is required. The overlay is a map tile
service, not a weather reading. If a shared state type helps, keep a small
`OverlayState` in `Overlay.kt` rather than routing tiles through the forecast
path.

### Cache and failure handling

- Disk path: `cacheDir/overlays/<layer>/<validTime>-<refTime>/<z>/<x>/<y>.png`.
- Write `<file>.part`, then rename atomically, following the base-tile path.
- TTL: clouds 6 h; temperature and wind 2 h.
- Cap each overlay at 32 MB; evict least-recently-used files by
  `lastModified` after a successful write.
- Delete overlay directories older than two days on start or bucket change,
  but never the directory currently displayed.
- Request only visible tiles. Do not prefetch.
- Mark a failed tile for 60 s, like base tiles. Mark a WMS `ServiceException`
  failed for five minutes and show its readable message; never retry in a
  tight loop.
- A failed overlay must leave radar fully usable. Offline means no requests;
  cached tiles may still be shown, labelled as cached. Never synthesize
  cloud, temperature or wind pixels.

## Testing plan

Follow the test-first workflow in [AGENTS.md](../../AGENTS.md) and
[DEVELOPMENT.md](../DEVELOPMENT.md). Write the focused test, observe the red
failure, implement, then run the full gate.

### JVM tests

Add `app/src/test/java/app/drizziq/OverlayTest.kt` covering:

1. `tileBounds_zoomZero_coversWholeWebMercatorWorld`.
2. `tileBounds_zoomAndNeighbours_areContiguous`.
3. `validTime_clouds_usesLatestSlotNotFuture`.
4. `validTime_temperature_floorsToHourAndNeverExceedsTarget`.
5. `validTime_staleRange_returnsNull`.
6. `referenceTime_iconRun_isLatestRunAtOrBeforeValidTime`.
7. `getMapUrl_includesLayerStyleCrsBboxTimeAndReferenceTime`.
8. `overlayTileKey_changesWithLayerTimeReferenceAndTile`.
9. `parseServiceException_extractsMessage_andReturnsNullForPng`.
10. `overlayMetadata_pinsVerifiedLayerNamesAndCadence` against a trimmed
    checked-in capabilities fixture, not a tautology.
11. `windOverlay_hiddenBelowMinZoom_andClampedAtMaxZoom`.
12. `attribution_includesDwdAndSatelliteSource`.

Fixtures:

- `app/src/test/resources/wms-capabilities-trimmed.xml` with only the three
  layers and their dimensions/styles, copied from the live response.
- `app/src/test/resources/wms-service-exception.xml` with the observed error.
- Optionally a 256×256 sample PNG per layer for decode tests. Do not test the
  live service or pixel palette in JVM tests.

### Device and manual checks

Extend the radar smoke exercise to:

- open radar and tap each chip; wait for legend/notice to settle;
- pan and zoom with an overlay active; verify no crash and a correct marker;
- scrub across an overlay bucket boundary and verify the timestamp updates;
- turn the overlay off and verify the radar legend/notice returns;
- recreate the Activity and verify radar still works (persistence optional);
- in `--full` only, enable airplane mode, verify `Cached overlay` and
  `Overlay offline` states, then restore connectivity.

Do not make smoke checks depend on clouds or wind being present; a transparent
or empty tile is valid. Manually check day/night satellite legibility,
northern-Germany coverage, temperature band/legend agreement and wind barb
contrast at zoom 5–8. If barbs are unreadable, remove wind from the first
version and keep this finding.

```sh
./tools/verify --gradle-only testDebugUnitTest --tests 'app.drizziq.OverlayTest'
./tools/verify
python3 tools/verify_phone.py --serial SERIAL
```

## Risks and open decisions

1. **Satellite cadence.** Three-hourly clouds are context, not a nowcast. A
   higher-cadence source such as EUMETSAT View would be a second provider with
   separate terms; the DWD cloud-top NetCDF products need a new decoder.
   Decide whether 3 h is acceptable before implementing.
2. **Satellite coverage.** Declared coverage ends at 54° N. Verify the northern
   edge; if it is clipped, use the global IR mosaic there or show an explicit
   gap rather than stretching.
3. **Satellite appearance.** Day RGB land colours may clash with the HUD;
   night IR is low-contrast. Tune alpha and colour handling, or use IR only.
4. **Opaque temperature.** The contour fill tints the whole map. Confirm that
   alpha blending keeps the basemap and radar readable; reduce alpha or hide
   the overlay during radar playback if necessary.
5. **Wind barbs.** Thin black barbs are nearly invisible and may be too sparse
   or dense at some zooms. Keep a visual acceptance gate and be willing to
   ship without wind.
6. **WMS `time=current`.** It failed for ICON-EU once. Send explicit
   `TIME`/`REFERENCE_TIME` and handle `ServiceException` bodies. Parse
   capabilities at runtime only if fixed model-run selection proves
   insufficient.
7. **Server load.** Per-tile `GetMap` is heavier than pre-rendered tiles. Keep
   concurrency at two, cache aggressively, cap zoom and do not prefetch.
   Revisit larger metatiles or lower zoom limits if DWD rate-limits.
8. **Licence and attribution.** Confirm the DWD source note and the exact
   EUMETSAT/NOAA credit for the satellite layer before release.
9. **Memory.** Base tiles, radar bitmaps and overlay caches share a tight
   budget on low-end devices. Cap by bytes and measure on the OnePlus 5T.
10. **Accessibility.** Chips must work without map gestures, and overlay/time
    changes must be announced. The overlay is supplementary, not the only way
    to reach weather information.

Open decisions:

- Is a three-hourly satellite overlay acceptable, or should clouds wait for a
  higher-cadence source?
- Should wind ship as barbs, or be omitted?
- Should the overlay selection persist across restarts?
- Should warnings and lightning be added in the same slice or separately?

## Other DWD layers worth considering

These were found in the same capabilities document. The verdict is relative to
the radar page, not a general product roadmap.

| Layer | WMS name | Cadence | Verdict |
| --- | --- | --- | --- |
| Lightning density | `Blitzdichte` | 5 min | Best next overlay for storm context; transparent raster and matches the radar timeline. Recommended next. |
| Thunderstorm cells | `Gewittercluster` | 5 min | Detected convective cells/tracks; polygons. Useful but can clutter. |
| Weather warnings | `Warnungen_Gemeinden` | current | Severity/type polygons; high value, but warnings may deserve their own view. |
| Feels-like temperature | `GefuehlteTemp` | hourly | Alternative to 2 m temperature. |
| Pressure (QFF) | `Icon-eu_reg00625_fd_sl_QFF` | hourly | Isobars give synoptic context; labels are rendered by DWD. |
| Model precipitation | `Icon-eu_reg00625_fd_sl_TOTPREC01H` | hourly | Redundant inside the radar nowcast; could extend beyond radar range. |
| UV index / dose | `UVIndex`, `UV_Dosis_EU_CL`, `_CS` | daily | Seasonal; not a radar-time layer. |
| Pollen | `Pollenflug` | daily | Seasonal, 27 regions; better as a separate view. |
| Bioweather | `Biowettervorhersage` | daily | Niche; 11 regions. |
| Station observations | `RBSN_T2m`, `RBSN_FF` | hourly | Point symbols, not a raster; likely too cluttered. |
| Radar accumulations | `RADOLAN-RW/RY/SY`, `SF-Produkt`, W1–W4 | product-dependent | The app already uses RV; accumulations are a separate product, not an overlay. |
| Satellite cloud-top height/temperature | `opendata.dwd.de/weather/satellite/clouds/CTH` and `/TS` | 5–10 min | Better cadence than the 3-hourly WMS image, but NetCDF `.nc.bz2` (roughly 0.6–2.2 MB) needs a new decoder. Not recommended now. |
| Satellite radiation | `opendata.dwd.de/weather/satellite/radiation/sid` and `/sis` | 10 min | About 16 MB NetCDF per step; too heavy for this app. |
| Air quality | not DWD | — | Would require UBA or another provider; out of scope. |

## Acceptance and delivery

The feature is complete when:

- radar still launches in the current state: precipitation radar, OSM basemap,
  existing timeline/legend, no overlay;
- each overlay can be selected and deselected with one tap, only one is active
  at a time, and radar remains visible;
- every active overlay shows its product, valid time, units/legend and DWD
  attribution;
- time selection follows the rules above; frames within a bucket do not
  refetch, and bucket changes do;
- tiles are cached on disk and in memory; only visible tiles are requested;
  zoom is capped per layer;
- WMS errors and offline states produce readable notices, leave radar usable
  and never display fabricated data;
- temperature and satellite are legible on the HUD, and wind passes visual
  acceptance or is removed with the limitation documented;
- new pure rules have JVM tests, `./tools/verify` passes, and radar phone smoke
  checks pass with overlays toggled;
- no new dependencies, coroutines/ViewModels or permissions are added.

Suggested slices:

1. **Pure overlay model + tests** — `Overlay.kt`, tile bounds, time rules, URL
   builder, error parser and metadata fixture.
2. **HTTP allow-list + tile fetch** — extend `Http.checked`, add PNG/WMS error
   handling and disk cache tests.
3. **RadarMap rendering** — overlay tile loop, alpha, zoom caps, colour
   filters and draw order. Start with temperature because it is the most
   reliable layer.
4. **Clouds** — satellite RGB, alpha/contrast tuning, coverage check and
   fallback decision.
5. **Wind** — barb recolouring, minimum zoom and visual acceptance; remove if
   unreadable.
6. **UI polish** — chips, legends, notices, attribution, accessibility, docs
   and full verification.

When implemented, update [DATA.md](../DATA.md) with the WMS endpoint, layer
names, cadence, rendered-raster limitation, licence/source note and cache
policy; update [README.md](../../README.md) with the radar overlay bullet; and
replace this document's “proposed” status with implemented behavior. No
`build.gradle.kts` dependency change is expected.
