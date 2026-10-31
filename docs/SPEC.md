# Drizziq — Android weather app

A native Android app for one selected place: a DWD weather forecast and a zoomable precipitation radar, presented in a single dark sci-fi interface.

## Required result

### Today and Week

- Four square bottom tabs: Today, Week, Radar and Places. Navigation stays fixed while content scrolls; selected tab survives Activity recreation.
- The taller shared header emphasizes temperature, condition icon and wind to the right of the selected location/station. Names use a smaller two-line label with end ellipsis. Missing weather fields use fixed placeholders; the summary stays above scrolling content on every tab.
- Today contains the complete selected-place civil day (00:00 to the next midnight) in one aligned horizontal grid, inspired by `tmp/meteoblue_hourly.png`: narrower columns, bold time labels/condition icons, temperature ribbon, precipitation amount/probability, wind direction/speed, one-hour gusts and humidity only if supplied. DST days contain 23/25 actual hourly instants, with repeated times distinguished by zone. Earlier hours use an archived DWD issue covering midnight; unavailable hours/fields remain unknown; the chart never bridges missing temperatures.
- Places starts directly with its action buttons; the selected location appears only in the shared header.
- Today omits the explanatory missing-value paragraph. Week omits its heading, calculation footnote and partial-day labels; missing/partial totals remain unavailable.
- Week contains a compact daily table for the next seven available days: weekday/date, condition, high/low, precipitation, wind and sunshine. Partial-day totals remain unavailable.
- Both forecast screens use the same formatted details card for forecast-point metadata, freshness, timezone and the last successful download (“Last updated”). Distance, elevation and available air pressure form a spaced metrics row. Issue/validity timestamps and the old DATA TRANSMISSION / LIVE line are removed; cached/stale states remain explicit. Device-location snapshots display their locally resolved forecast station name, independently of weather-fetch success; user-assigned/city labels are preserved.
- Forecast refresh/cache/failure messages do not insert a banner above the content. Freshness remains in the existing footer; fatal failures are explained inside the empty-state card.
- A thin indeterminate global download line covers weather, geocoding and viewed map tiles. Its two-dp slot remains reserved while idle; showing or hiding it cannot shift content or navigation. Overlapping requests keep it active until all transfers finish, including error/cancel cleanup.

- Today includes a locally calculated sunrise/sunset card for the selected coordinates/date/zone, with generated icons, standard-horizon assumptions and explicit no-event outcomes. It works offline and outside DWD coverage. Calculation runs on an owned worker; stale results cannot overwrite a newer place/date.

### Radar view

- A precipitation map that can be zoomed and panned freely.
- Animated playback covering recent observations and the available forecast window.
- The map fills the content area from edge to edge. Compact play/pause and timeline scrubbing controls overlay the map with a transparent background. There is no legend. The play control is icon-only; no NOW label is drawn. Persist the map center and fractional zoom across app restarts and Activity recreation. Choosing different coordinates recenters the map. Current frame timestamps and observed/forecast metadata remain available to accessibility services rather than taking visual space.
- A subtle marker for the selected location, without an in-map place label. Drag to pan, pinch in/out and double-tap to zoom; zoom/recenter accessibility actions replace visible map-control buttons.
- Compact notices for loading, cached, stale/offline or unavailable radar. Normal live status, issue/received metadata and frame date/time are not shown as separate lines. Keep map attribution visible.
- A free/open, keyless basemap. Show attribution only where the map licence requires it.

### Location

- Select places with explicit city search, optional device location or saved favourites. There is no manual-coordinate entry screen; old coordinate-based saved places continue to work.
- Initial location is Munich: 48.138108 N, 11.574726 E.
- The current location can be saved to a favourites list with a label.
- Favourites persist across app restarts and can be selected and deleted.
- Search city explicitly submits a name to Open-Meteo, shows up to ten disambiguated results and requires a result tap. Search is global; weather remains DWD-only.
- Use current location obtains one foreground position with optional coarse/fine permission, using Android providers. It does not follow movement or reacquire on restart. City search and favourites need no location permission.
- Resolve the nearest bundled MOSMIX point locally, within 50 km inclusive. Outside coverage, keep the selected place and radar available, clear previous weather and explain the limit.
- Preserve full coordinates, time zone and optional geocoding attribution in saved places. Unknown zones require explicit input. See [GEOCODING.md](GEOCODING.md) for the detailed behavior and acceptance contract.

### Data states

Every reading must carry one of: loading, live, cached, stale, error. Never show invented, zero-filled, or silently extrapolated values. A failed refresh keeps the last successful data visible but clearly marked stale, together with its timestamp.

## Constraints

- **Weather data:** DWD open data only (`https://opendata.dwd.de/weather/`). No other weather provider, aggregator, or API.
- If DWD does not supply a requested field, omit it rather than synthesize it.
- **Design:** one dark theme only. Futuristic, space/HUD-inspired, like e.g. the Expanse
- **Assets:** every icon, glyph, and illustration is created or generated in the repository. Nothing is downloaded from the web.
- **Dependencies:** keep them minimal; add only what the required behaviour cannot reasonably be built without.
- No accounts, ads, analytics, telemetry, or tracking. HTTPS only.

## Technical deliverable

- Android Studio project rooted at `/home/psyke/Projects/drizziq`.
- Kotlin, supporting Android 7.0 (API 24) and newer.
- A new **private** Codeberg repository initialized with that directory as its root.
- `README.md` following the structure of `/home/psyke/Projects/enso/README.md`: short description, one real app screenshot, git badges, and licence.
- Screenshot committed under `docs/`.
- The app builds, installs, and runs on the USB-connected phone.
- Both views, location entry, and favourite persistence are exercised on that phone.

## Acceptance

- Forecast and radar show live DWD-derived content for Munich on first launch.
- Selecting another city or saved snapshot resolves matching forecast and radar content for that place.
- A saved favourite survives a full app restart and can be selected again.
- Radar zooms, pans, scrubs, and loops without broken frames or stale timestamps.
- Airplane mode produces clearly marked stale/error states, not fabricated readings.
- The repository is private, the README renders with its screenshot and badges, and the build command is documented.
