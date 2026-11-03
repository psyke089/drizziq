package app.drizziq

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** One shared, source-backed details layout for Today and Week. */
@SuppressLint("ViewConstructor") // Programmatic card requires an actual forecast; no XML inflation.
class ForecastDetailsCard(
    context: Context,
    reading: Reading<Forecast>,
    place: Place,
    refresh: () -> Unit,
) : LinearLayout(context) {
    init {
        tag = "forecast-details"
        orientation = VERTICAL
        pad(16)
        background = panel()
        val forecast = requireNotNull(reading.data)
        val now = AppPlatform.now()
        val heading = context.row()
        heading.addView(
            context.label(forecast.station.name, 15f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LayoutParams(0, -2, 1f),
        )
        val status =
            if (reading.state in listOf(Freshness.STALE, Freshness.CACHED, Freshness.ERROR))
                reading.state.name
            else ""
        heading.addView(
            context.label(status, 9f, Hud.color(reading.state), true).apply {
                gravity = Gravity.END
            },
            LayoutParams(context.dp(54), context.dp(16)),
        )
        addView(heading)
        space(5)
        addView(
            context
                .label(
                    context.hudString(
                        R.string.details_station_id,
                        forecast.station.id,
                        forecast.station.lat,
                        forecast.station.lon,
                    ),
                    10f,
                    Hud.muted,
                    true,
                )
                .apply {
                    contentDescription =
                        context.hudString(
                            R.string.details_station_description,
                            forecast.station.id,
                            forecast.station.lat,
                            forecast.station.lon,
                        )
                }
        )
        space(14)
        val metrics = context.row().apply { gravity = Gravity.TOP }
        fun metric(title: String, value: String, description: String) {
            val box = context.column()
            box.addView(context.label(title, 9f, Hud.muted, true))
            box.space(4)
            box.addView(
                context.label(value, 15f, Hud.cyan, true).apply { contentDescription = description }
            )
            metrics.addView(
                box,
                LayoutParams(0, -2, 1f).apply {
                    if (metrics.childCount > 0) leftMargin = context.dp(12)
                },
            )
        }
        val distance =
            context.hudString(R.string.details_distance, forecast.station.distance(place))
        metric(
            context.hudString(R.string.details_distance_label),
            context.hudString(R.string.details_distance_value, forecast.station.distance(place)),
            distance,
        )
        val elevation =
            forecast.station.elevation
                ?.takeIf { it.isFinite() }
                ?.let { context.hudString(R.string.details_elevation, it) } ?: "—"
        metric(
            context.hudString(R.string.details_elevation_label),
            elevation,
            context.hudString(R.string.details_elevation_description, elevation),
        )
        forecast.current(now)?.pressure?.let {
            val pressure = context.hudString(R.string.details_pressure, it)
            metric(
                context.hudString(R.string.details_pressure_label),
                pressure,
                context.hudString(R.string.details_pressure_description, pressure),
            )
        }
        addView(metrics)
        space(12)
        addView(
            View(context).apply { setBackgroundColor(Hud.line) },
            LayoutParams(-1, context.dp(1)),
        )
        space(12)
        fun time(value: Long) = Times.format(value, "dd MMM HH:mm z", place.zone)
        fun info(title: String, value: String) {
            val row =
                context.row().apply {
                    gravity = Gravity.TOP
                    setPadding(0, 0, 0, context.dp(9))
                }
            row.addView(context.label(title, 10f, Hud.muted), LayoutParams(context.dp(78), -2))
            row.addView(
                context.label(value, 10f, Hud.ink, true).apply { gravity = Gravity.END },
                LayoutParams(0, -2, 1f),
            )
            addView(row)
        }
        info(context.hudString(R.string.details_timezone), place.zone)
        reading.fetched?.let { info(context.hudString(R.string.details_updated), time(it)) }
        space(3)
        addView(
            context
                .action(if (reading.refreshing) "REFRESHING…" else "REFRESH DWD") { refresh() }
                .apply { isEnabled = !reading.refreshing }
        )
        contentDescription = context.hudString(R.string.details_accessibility, reading.state.name)
    }
}
