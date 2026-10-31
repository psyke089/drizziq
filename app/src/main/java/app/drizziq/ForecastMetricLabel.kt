package app.drizziq

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout

enum class ForecastMetric(val description: String) {
    TIME("Time"),
    SKY("Weather condition"),
    TEMPERATURE("Temperature"),
    HIGH("Maximum temperature"),
    LOW("Minimum temperature"),
    RAIN("Precipitation"),
    PROBABILITY("Precipitation probability"),
    DIRECTION("Wind direction"),
    WIND("Wind speed"),
    GUST("Wind gust"),
    HUMIDITY("Relative humidity"),
    SUN("Sunshine duration"),
}

/** Shared SVG metric symbols for the two forecast tables. */
@SuppressLint("ViewConstructor")
class ForecastMetricLabel(context: Context, metric: ForecastMetric, unit: String = "") :
    LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        contentDescription =
            listOf(metric.description, unit).filter { it.isNotEmpty() }.joinToString(", ")
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(
            SvgIcon(context, "metric-${metric.name.lowercase()}"),
            LayoutParams(context.dp(21), context.dp(21)),
        )
        if (unit.isNotEmpty())
            addView(context.label(unit, 9f, Hud.muted, true).apply { gravity = Gravity.CENTER })
    }
}
