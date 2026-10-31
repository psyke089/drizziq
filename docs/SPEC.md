# Drizziq — Android weather app

A native Android app for one selected place: a DWD weather forecast and a zoomable precipitation radar, presented in a single dark sci-fi interface.

## Required result

### Forecast view

For the selected location, show:

- Current conditions: temperature, weather condition, wind, local time, place label, coordinates, elevation.
- A 7-day outlook: weekday/date, high and low, condition, precipitation amount, wind, sunshine duration.
- Hourly detail for at least the next 24 hours: temperature, precipitation, humidity, wind direction and speed.
- Forecast issue/validity time and a clear freshness state.

The reference image `tmp/forecast.png` defines **which information** the forecast view must carry. It is not a design or layout reference.

### Radar view

- A precipitation map that can be zoomed and panned freely.
- Animated playback covering recent observations and the available forecast window.
- The map occupies most of the available radar content area. Compact play/pause icons, timeline scrubbing with a current-time marker, and a slim precipitation legend remain below it. The play control is icon-only; no NOW label is drawn. Persist the map center and fractional zoom across app restarts and Activity recreation. Choosing different coordinates recenters the map. Current frame timestamps and observed/forecast metadata remain available to accessibility services rather than taking visual space.
- A subtle marker for the selected location, without an in-map place label. Drag to pan, pinch in/out and double-tap to zoom; zoom/recenter accessibility actions replace visible map-control buttons.
- Compact notices for loading, cached, stale/offline or unavailable radar. Normal live status, issue/received metadata and frame date/time are not shown as separate lines. Keep map attribution visible.
- A free/open, keyless basemap. Show attribution only where the map licence requires it.

### Location

- The user can select any place by entering latitude and longitude, with range validation.
- Initial location is Munich: 48.138108 N, 11.574726 E.
- The current location can be saved to a favourites list with a label.
- Favourites persist across app restarts and can be selected and deleted.
- Search city explicitly submits a name to Open-Meteo, shows up to ten disambiguated results and requires a result tap. Search is global; weather remains DWD-only.
- Use current location obtains one foreground position with optional coarse/fine permission, using Android providers. It does not follow movement or reacquire on restart. City search, manual coordinates and favourites need no location permission.
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
- A different latitude/longitude produces forecast and radar content for that place.
- A saved favourite survives a full app restart and can be selected again.
- Radar zooms, pans, scrubs, and loops without broken frames or stale timestamps.
- Airplane mode produces clearly marked stale/error states, not fabricated readings.
- The repository is private, the README renders with its screenshot and badges, and the build command is documented.
