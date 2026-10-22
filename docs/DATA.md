# Data, assets and dependencies

## DWD weather

Source: **Deutscher Wetterdienst (DWD)**, [DWD open data](https://opendata.dwd.de/weather/), under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), as described in [DWD's reuse terms](https://www.dwd.de/copyright).

- Forecast: `local_forecasts/mos/MOSMIX_L/single_stations/{id}/kml/MOSMIX_L_LATEST_{id}.kmz`.
- Radar: `radar/composite/rv/composite_rv_LATEST.tar` and the observed member of the preceding twelve five-minute archives. As served on 2026-10-22, these contain ODIM HDF5 uint32 ACRR rasters, 1100 × 1200 pixels, with the WGS84 DE1200 polar stereographic projection declared in the files.
- Station metadata: [official MOSMIX station catalogue](https://www.dwd.de/DE/leistungen/met_verfahren_mosmix/mosmix_stationskatalog.cfg?view=nasPublication&nn=16102), bundled as `app/src/main/assets/stations.tsv`. Retrieved 2026-10-22. Catalogue degrees/minutes are converted to decimal degrees. The forecast's own KML coordinates and elevation are displayed with the reading.

The app converts Kelvin to Celsius and m/s to km/h, groups hourly temperatures into available daily ranges, and totals hourly rain/sunshine only when all intervals of a complete local day exist. Wind in the outlook is the maximum provided hourly speed. Daily condition is the most severe supplied hourly condition. A day's completeness respects 23/25-hour daylight-saving transitions. Forecast values remain station forecasts; the app does not spatially interpolate them. Relative humidity is only displayed if DWD provides it, and is never calculated from dew point.

Radar pixels use the declared gain, offset, undetect and nodata metadata. The app colours five-minute precipitation amounts, hatches nodata pixels, and projects the raster onto the map. Forecast times come from the HDF5 validity metadata and must agree with the issue and lead in the archive member name. No synthetic frames or extrapolation are used. Missing historical frames leave visible gaps in the timeline and mark the result stale.

The forecast freshness threshold is nine hours from issue; radar is twenty minutes. Connection loss and refresh failures mark retained readings stale immediately. Saved data begins cached while online validation is pending. The original issue/validity timestamps and last successful reception time remain visible.

The test resources are genuine DWD samples captured on 2026-10-22: Munich MOSMIX issue 15:00 UTC, observed radar 20:00 UTC, and radar forecast issued at 20:15 UTC and valid at 22:15 UTC. These are test fixtures only and are not packaged as app weather.

## Basemap

© [OpenStreetMap contributors](https://www.openstreetmap.org/copyright). Data is available under the Open Database License 1.0. Raster tiles are obtained directly from `https://tile.openstreetmap.org/{z}/{x}/{y}.png` and used under the [tile usage policy](https://operations.osmfoundation.org/policies/tiles/).

The map visibly links the attribution to the licence page, identifies the app in its User-Agent, requests tiles only for the displayed viewport, and retains tiles for at least seven days. There is no bulk/offline-download feature. Tiles remain runtime cache files and are not redistributed in the repository. The map darkens these tiles locally.

## Offline time-zone metadata

The files `timezones.tree` and `timezones.txt` are extracted without changing their lookup content from [PhotoStructure tz-lookup](https://github.com/photostructure/tz-lookup), revision `6051e7e2fe8b754e23d40aff0120cf9b38bde608`. Its code and the port in `ZoneLookup.kt` are under [CC0](licenses/tz-lookup-CC0.txt), originally by The Dark Sky Company, LLC, with subsequent PhotoStructure changes. Data retrieved 2026-10-22.

The geographical source is [timezone-boundary-builder](https://github.com/evansiroky/timezone-boundary-builder), © OpenStreetMap contributors, under [ODbL 1.0](licenses/timezone-data-ODbL-1.0.txt). The extracted database remains available in source in this repository under ODbL. Zone lookup is approximate around borders; the location form offers an explicit IANA-zone override. Android's installed time-zone rules supply the current civil-time offset.

Run `python tools/update_metadata.py` to regenerate the station catalogue and pinned timezone data. This needs a network connection; the app uses the bundled metadata offline.

## Code and visual assets

The application uses Android's native UI, HTTPS networking, bitmap renderer and touch APIs. `Hud.kt`, `ic_launcher.xml`, the README badge SVGs and all weather/radar pictograms are created in this repository. Text uses Android system fonts. The committed screenshots are raw captures of the running app on the USB-connected OnePlus 5T.

Direct dependencies:

| Component | Purpose | Licence |
| --- | --- | --- |
| Kotlin standard library | Kotlin runtime | Apache 2.0 |
| [jHDF 0.8.6](https://github.com/jamesmudd/jhdf/tree/v0.8.6) | Maintained Java 8-compatible HDF5 decoder | [MIT](licenses/jhdf-MIT.txt) |
| Android desugar JDK libraries 2.1.5 | Java library compatibility on API 24 | GPLv2 with Classpath Exception; associated notices in the distribution |
| JUnit 4.13.2 | JVM tests only | EPL 1.0 |

jHDF transitively uses SLF4J (MIT), Apache Commons Lang (Apache 2.0), compress-lzf (Apache 2.0), and lz4-java (Apache 2.0). They support the reader's metadata and compression handling. jHDF is pinned to its Java 8-compatible line to retain Android 7 support. No analytics, map SDK, web UI framework, GPS, or weather aggregator is used.
