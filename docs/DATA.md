# Data, assets and dependencies

## DWD weather

Source: **Deutscher Wetterdienst (DWD)**, [DWD open data](https://opendata.dwd.de/weather/), under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), as described in [DWD's reuse terms](https://www.dwd.de/copyright).

- Forecast: `local_forecasts/mos/MOSMIX_L/single_stations/{id}/kml/MOSMIX_L_LATEST_{id}.kmz`.
- Radar: `radar/composite/rv/composite_rv_LATEST.tar` and the observed member of the preceding twelve five-minute archives. As served on 2026-10-22, these contain ODIM HDF5 uint32 ACRR rasters, 1100 × 1200 pixels, with the WGS84 DE1200 polar stereographic projection declared in the files.
- Station metadata: [official MOSMIX station catalogue](https://www.dwd.de/DE/leistungen/met_verfahren_mosmix/mosmix_stationskatalog.cfg?view=nasPublication&nn=16102), bundled as `app/src/main/assets/stations.tsv`. Retrieved 2026-10-22. Catalogue degrees/minutes are converted to decimal degrees. The forecast's own KML coordinates and elevation are displayed with the reading.

The app converts Kelvin to Celsius and m/s to km/h, groups hourly temperatures into available daily ranges, and totals hourly rain/sunshine only when all intervals of a complete local day exist. Wind in the outlook is the maximum provided hourly speed. Daily condition is the most severe supplied hourly condition. A day's completeness respects 23/25-hour daylight-saving transitions. Forecast values remain station forecasts; the app does not spatially interpolate them. Relative humidity is only displayed if DWD provides it, and is never calculated from dew point.

The parser retains `R101` (probability of precipitation greater than 0.1 mm in the preceding hour, already 0–100 percent) and `FX1` (maximum gust in the preceding hour, converted from m/s to km/h). Missing/out-of-range probability and invalid gust values remain unknown; a 3/12-hour gust is never substituted. The shared forecast details card shows current-hour `PPPP` (DWD reduced surface pressure, converted from Pa to hPa) when available, and the selected place’s IANA timezone. Fields and units were checked against DWD’s [element definition](https://opendata.dwd.de/weather/lib/MetElementDefinition.xml) on 2026-10-31. The linked meteoblue page is a visual/quantity reference; the app continues using DWD weather.

Today uses the selected place’s full local civil day, including DST boundaries. It shows available source timestamps without rounding in fractional-offset zones. Earlier hours are filled from the preceding DWD MOSMIX L issue covering local midnight, cached separately for offline use. Latest-issue hours take precedence. These are earlier forecasts, not observations; unavailable fields remain unknown. The latest issue metadata and daily aggregation stay unchanged. Daily forecasting rules remain unchanged.

Radar pixels use the declared gain, offset, undetect and nodata metadata. The app colours five-minute precipitation amounts, hatches nodata pixels, and projects the raster onto the map. Forecast times come from the HDF5 validity metadata and must agree with the issue and lead in the archive member name. No synthetic frames or extrapolation are used. Missing historical frames leave visible gaps in the timeline and mark the result stale.

The forecast freshness threshold is nine hours from issue; radar is twenty minutes. Connection loss and refresh failures mark retained readings stale immediately. Saved data begins cached while online validation is pending. Issue/validity timestamps remain in the data for freshness calculations. The forecast details card shows the last successful download as “Last updated”; loading saved data or failing a refresh does not advance it.

The test resources are genuine DWD samples captured on 2026-10-22: Munich MOSMIX issue 15:00 UTC, observed radar 20:00 UTC, and radar forecast issued at 20:15 UTC and valid at 22:15 UTC. These are test fixtures only and are not packaged as app weather.

## Local sunrise and sunset

Sun times are computed on-device with locally authored NOAA/Meeus-style solar-coordinate mathematics, finding geometric solar-centre crossings at −0.833° within the selected place’s civil day. This is a conventional unobstructed horizon, not a terrain/building/cloud visibility prediction. No astronomy API or device-coordinate request is used at runtime. Polar day/night and skipped civil dates have explicit no-event outcomes. Work is bounded to one worker/latest pending calculation and guarded against obsolete place/date callbacks.

The equations are documented in [NOAA calculation details](https://gml.noaa.gov/grad/solcalc/calcdetails.html). Independent [USNO reference requests](https://aa.usno.navy.mil/data/api) for fixed public-city coordinates were captured on 2026-10-31 for tests (Munich autumn/summer, New York DST day and Sydney summer); tests make no live requests. Reference agreement is tested within two minutes, not claimed as a universal observation guarantee. Real observed times depend on refraction and the local skyline. See `SolarCalculatorTest.kt` for fixed dates/values and [the future sunset plan](future/SUNSET.md) for the separate planned visibility/spot-finder feature.

## City search and optional location

Explicit city searches use the [Open-Meteo geocoding API](https://open-meteo.com/en/docs/geocoding-api), with [GeoNames](https://www.geonames.org/) place metadata. Query parameters are fixed to `count=10`, `language=de`, `format=json`, with no country filter. Weather still comes exclusively from DWD. Terms checked 2026-10-31: the [free API terms](https://open-meteo.com/en/terms) allow non-commercial use without an account/key, subject to quotas (600/minute, 5,000/hour, 10,000/day). Commercial use requires a separate provider/licensing decision.

Place metadata is attributed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), following [Open-Meteo's licence](https://open-meteo.com/en/licence). Linked Open-Meteo/GeoNames credits appear beside results and derived labels/favourites; edited labels retain provenance and are marked. The checked-in München response is an attributed geocoding test fixture, not runtime weather or a bundled search catalogue.

Only an explicit Search/Refresh sends search text. Results use a 20-entry, 30-minute memory cache, cleared by process exit or Clear search cache. No search history is written to disk. HTTP redirects stay on the exact approved endpoint, decoded responses are limited to 256 KiB, and Retry-After is respected without automatic retries. Queries, full URLs and device coordinates are not logged.

Use current location requests foreground coarse/fine access together after explicit consent, accepts approximate permission, and obtains one snapshot using eligible Android network/GPS providers. It never requests background access or follows movement. Provider services may use networks; offline acquisition is not guaranteed. Coordinates resolve to stations/time zones on-device, without a reverse-geocoding request. Only the selected snapshot/favourites are stored privately. Weather-station requests and map-tile requests continue to reveal their respective station/viewport to those services.

The bundled MOSMIX catalogue revision is SHA-256 `713df55d858359b021932d84b8dd9c2832b7d73b592e301b2f410c1d7a5b7f3c`. It is validated and loaded once per Repository, then scanned linearly with exact-distance ties broken by station ID. Forecast coverage is 50 km inclusive; points outside it do not trigger a KMZ fetch. A catalogue update re-resolves saved coordinates. The metadata generator validates all fetched outputs before replacing assets; invalid source rows fail with line numbers.

## Basemap

© [OpenStreetMap contributors](https://www.openstreetmap.org/copyright). Data is available under the Open Database License 1.0. Raster tiles are obtained directly from `https://tile.openstreetmap.org/{z}/{x}/{y}.png` and used under the [tile usage policy](https://operations.osmfoundation.org/policies/tiles/).

The map visibly links the attribution to the licence page, identifies the app in its User-Agent, requests tiles only for the displayed viewport, and retains tiles for at least seven days. There is no bulk/offline-download feature. Tiles remain runtime cache files and are not redistributed in the repository. The map darkens these tiles locally.

## Offline time-zone metadata

The files `timezones.tree` and `timezones.txt` are extracted without changing their lookup content from [PhotoStructure tz-lookup](https://github.com/photostructure/tz-lookup), revision `6051e7e2fe8b754e23d40aff0120cf9b38bde608`. Its code and the port in `ZoneLookup.kt` are under [CC0](licenses/tz-lookup-CC0.txt), originally by The Dark Sky Company, LLC, with subsequent PhotoStructure changes. Data retrieved 2026-10-22.

The geographical source is [timezone-boundary-builder](https://github.com/evansiroky/timezone-boundary-builder), © OpenStreetMap contributors, under [ODbL 1.0](licenses/timezone-data-ODbL-1.0.txt). The extracted database remains available in source in this repository under ODbL. Zone lookup is approximate around borders; an unresolved zone opens a focused IANA-zone prompt. Android's installed time-zone rules supply the current civil-time offset.

Run `python tools/update_metadata.py` to regenerate the station catalogue and pinned timezone data. This needs a network connection; the app uses the bundled metadata offline.

## Code and visual assets

The application uses Android's native UI, HTTPS networking, bitmap renderer and touch APIs. Editable icon sources live in [`artwork/icons/`](../artwork/icons/README.md). The weather set was supplied by the user from their local `todo-0/assets/weather/` project and restyled to Drizziq's palette; mixed-precipitation and remaining UI icons were authored locally. `tools/generate_icons.py` compiles the SVGs to native Android vectors during builds, without a runtime SVG library. The existing launcher safe zone is preserved. Text uses Android system fonts. The README badge SVGs are authored in this repository. The committed screenshots are raw captures of the running app on the USB-connected OnePlus 5T.

Condition mapping follows DWD's [MOSMIX weather-code workbook](https://www.dwd.de/DE/leistungen/opendata/help/schluessel_datenformate/kml/mosmix_element_weather_xls.xlsx?__blob=publicationFile&v=1), checked on 2026-10-31, including rime fog 49 and mixed precipitation 68/69/83/84. Explicit source intensities take precedence; the app's hourly-amount and wind-speed icon thresholds are documented with the SVGs. These visual thresholds do not change forecast values or constitute warning levels.

Direct dependencies:

| Component | Purpose | Licence |
| --- | --- | --- |
| Kotlin standard library | Kotlin runtime | Apache 2.0 |
| [jHDF 0.13.0](https://github.com/jamesmudd/jhdf/tree/v0.13.0) | HDF5 decoder for DWD radar | [MIT](licenses/jhdf-MIT.txt) |
| Android desugar JDK libraries 2.1.5 | Java library compatibility on API 24 | GPLv2 with Classpath Exception; associated notices in the distribution |
| JUnit 4.13.2 | JVM tests only | EPL 1.0 |
| org.json 20250517 | JVM JSON tests only; Android uses platform JSON | Public domain |

jHDF transitively uses SLF4J (MIT), Apache Commons Lang (Apache 2.0), compress-lzf (Apache 2.0), and lz4-java (Apache 2.0). They support the reader's metadata and compression handling. Android builds use D8 and core-library desugaring for compatibility with the app's minimum API 24. No analytics, map SDK, web UI framework or weather aggregator is used. Foreground location uses Android platform providers only.
