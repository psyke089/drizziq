<h1 align="center">Drizziq</h1>

<p align="center">A dark atmospheric console for DWD weather forecasts and precipitation radar.</p>

<p align="center">
  <a href="https://codeberg.org/k4r/drizziq"><img src="docs/badges/codeberg.svg" alt="Codeberg · private repository"></a>
  <a href="app/build.gradle.kts"><img src="docs/badges/version.svg" alt="Version 1.4.1"></a>
  <img src="docs/badges/android.svg" alt="Android 7.0+ · API 24">
  <a href="LICENSE"><img src="docs/badges/license.svg" alt="Licence MIT"></a>
</p>

<p align="center">
  <img src="docs/screenshot.png" alt="Drizziq Today view on a OnePlus 5T, with the hourly forecast and SVG weather icons" width="300">
</p>

Raw capture from the current debug build on a OnePlus 5T / Android 13, using the
public Munich preset. Weather data © Deutscher Wetterdienst, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

Drizziq is a native Kotlin Android app for one selected place. It reads weather directly from Deutscher Wetterdienst open data and runs on Android 7.0+ (API 24). No accounts, ads, analytics or tracking. Location permission is optional and used only for an explicit current-location request.

## What it does

- **Today.** A full-width hourly table runs from the selected place’s local midnight through noon tomorrow, including civil-time DST transitions. Temperature is colour-coded with numeric labels; precipitation, wind, gusts and optional humidity retain explicit missing values. A locally calculated sunrise/sunset card works offline.
- **Week.** A full-width table for the coming seven days: condition, high/low, rain, maximum wind and sunshine. Daily rain/sunshine require complete local days. Today and Week share the station details and last-updated card.
- **Radar.** A full-width map with playback, local frame time and a floating layer menu. Optional DWD regional 2 m temperature and three-hourly satellite infrared imagery sit below precipitation. Layer failures leave the radar and basemap usable; when DWD advertises expired timestamps the app probes recent slots once, keeps valid times visible, and reports when no current data is served.
- **Places.** City searches start after a 400 ms typing pause or immediately from the keyboard; selecting a result is explicit. Optional one-shot location and labelled favourites retain stored provenance. Dialogs close with their corner control, Back or outside dismissal.
- **Header, navigation and downloads.** A taller header emphasizes current temperature, condition icon and wind to the right of a smaller, two-line location name with ellipsis for long names, shared across every view. Square Today, Week, Radar and Places tabs. A thin global progress line tracks weather, city-search and map-tile downloads in a reserved slot, so appearing/disappearing never moves the layout.
- **Honest data states.** Forecast freshness is explicit; radar shows loading, cached, stale and error notices when needed. Failed refreshes retain timestamped successful data. Missing DWD fields are omitted; relative humidity is normally absent from MOSMIX. Daily rain and sunshine totals require every hourly interval of a complete local day.

## Build and install

Requires JDK 21, Android SDK platform 37, Build Tools 36.0.0 and Python 3.11+ (standard library only). Python runs `tools/verify`, the phone runner and the build-time icon generator; no third-party Python packages are needed. Open this directory in Android Studio, or set `ANDROID_HOME` to your SDK and run:

```sh
./tools/verify --gradle-only assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.drizziq/.MainActivity
```

The wrapper pins Gradle 9.8.2 with a distribution checksum. Its checked-in JAR matches the upstream Gradle 9.8.2 wrapper artifact and is Apache 2.0 build tooling, not part of the APK; see [gradle/wrapper/NOTICE](gradle/wrapper/NOTICE). Android Gradle Plugin 9.4.1 supplies Kotlin support. The only application library is jHDF 0.13.0, which reads DWD's current ODIM HDF5 radar format; Android core-library desugaring supports API 24.

Run the complete local development gate with:

```sh
./tools/verify
```

This checks Kotlin formatting, Python tooling tests, JVM unit tests, Android lint, and debug/release/device-test APK builds. The GitHub Actions mirror runs the same gate; no phone is required. See [DEVELOPMENT.md](docs/DEVELOPMENT.md) for test-first development, focused checks, debug signing, and quick versus full phone verification. [AGENTS.md](AGENTS.md) defines the workflow for agents.

The default phone runner uses isolated local fixtures and fails unexpected external requests. Use `--live` for separate provider checks, `--places-integration` for city selection and favourite persistence checks that restore the original places and map camera, and `--full` explicitly for connectivity-changing integration.

## Data

Weather forecasts and precipitation come from `https://opendata.dwd.de/weather/`; optional rendered radar overlays use DWD's `https://maps.dwd.de/geoserver/dwd/wms`. MOSMIX is a station forecast: the app chooses the nearest bundled point within 50 km and displays its distance. DWD radar covers Germany and its surroundings. The keyless OpenStreetMap basemap has visible attribution and caches only tiles viewed on screen.

City search sends typed search text to the free, non-commercial Open-Meteo geocoding service after a short pause, with GeoNames attribution. Device positions resolve to forecast points locally, without reverse geocoding. All app icon sources are editable SVGs in [artwork/icons](artwork/icons/README.md), compiled automatically to native Android vectors. [DATA.md](docs/DATA.md) records data sources, processing, licences and metadata regeneration.

## License

[MIT](LICENSE). DWD data, OpenStreetMap data, timezone data and dependencies retain their [respective licences](docs/DATA.md). The public source mirror, including the bundled timezone database offered under ODbL, is [psyke089/drizziq](https://github.com/psyke089/drizziq).
