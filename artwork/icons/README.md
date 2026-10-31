# Editable icons

SVG is the source of truth. Edit the files here; Android builds automatically
regenerate their native VectorDrawable resources. No SVG runtime library or
network access is needed.

- `weather/`: the 32 weather SVGs supplied from the user's `todo-0` project,
  adapted to Drizziq's cyan/amber outline palette without gradients or glow.
  Four additional mixed rain/snow icons retain the supplied snow shapes and
  add rain strokes for MOSMIX codes 68/69 and 83/84.
- `ui/`: locally authored SVG versions of all other app icons: metric labels,
  navigation, sunrise/sunset, wind compass/arrow, radar crosshair, playback,
  wordmark symbol, launcher artwork and an explicit unknown-condition symbol.

The launcher foreground keeps its existing adaptive-icon safe zone. The wind
compass and arrow are separate SVGs so only the arrow rotates. Dynamic chart
lines, temperature curves, map tiles and radar rasters remain data rendering.

```sh
python3 tools/generate_icons.py         # regenerate after editing
python3 tools/generate_icons.py --check # verify generated files match
python3 -m unittest discover -s tools/tests -p test_generate_icons.py
```

Outputs are checked in under `app/src/main/res/drawable/`, plus the legacy
launcher in `mipmap-anydpi/` and the generated `IconAssets.kt` resource catalogue.
Do not edit those outputs by hand. Retired SVGs remove only their generated
drawable resources on the next generation run.

Supported SVG features are paths, circles, ellipses, rectangles, lines,
polygons/polylines, flat fill/stroke colours and opacity, and nested
translate/scale/rotate transforms. Unsupported features fail explicitly rather
than silently disappearing. Convert text to paths; avoid filters, gradients,
external references, CSS styles and embedded images.

## Conditions and intensity

`WeatherIcons.kt` maps every code in DWD's MOSMIX `ww` table to an icon, including
rime fog (`49`), freezing precipitation and mixed rain/snow. It also accepts the
additional supplied WMO variants (`48`, `77`, `96`, `97`, `99`). Missing or
unsupported codes use `weather-unknown`; they never become clear/cloudy weather.

DWD's explicit light/moderate/heavy code takes precedence over the amount.
For codes that have no intensity, the following app-selected thresholds use
the **preceding hour's** `RR1c` in millimetres, including snow's liquid-water
equivalent. They are icon-selection conventions, not new DWD classifications.

| Generic precipitation | Light | Medium | Heavy |
| --- | --- | --- | --- |
| Rain (`60`) | below 2.5 mm/h | 2.5 to below 7.5 | 7.5 or more |
| Drizzle (`50`) | below 0.1 mm/h | 0.1 to below 0.5 | 0.5 or more |
| Snow (`70`) | below 0.5 mm/h | 0.5 to below 1.5 | 1.5 or more |

Generic amounts that are missing/invalid keep the neutral medium variant.
DWD `81` covers moderate **or** heavy showers: use the heavy variant from
7.5 mm/h, otherwise medium. DWD `95` uses the heavier storm artwork from
7.5 mm/h. Explicit freezing and mixed light/heavy codes keep their own variants.

Wind has a separate symbol beside the speed, preserving the weather condition
and compass. Its thresholds use supplied mean speed, **not gusts**:
below 30 km/h light, 30 to below 40 medium, 40 or more strong. Unknown wind omits
the intensity symbol. These are visual bands, not warning/advisory levels.

The shared header and hourly table use their own hour's values. The weekly icon
keeps the day's existing most-severe condition and, where needed, uses the
largest hourly amount accompanying that condition, never a daily total.
