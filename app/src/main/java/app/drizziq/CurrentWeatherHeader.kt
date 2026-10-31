package app.drizziq

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Compact shared weather summary; every field keeps its slot while data is unavailable. */
class CurrentWeatherHeader(context: Context) : LinearLayout(context) {
    private val temperature =
        context.label("—", 42f).apply {
            tag = "header-temperature"
            if (android.os.Build.VERSION.SDK_INT >= 26)
                setAutoSizeTextTypeUniformWithConfiguration(
                    32,
                    42,
                    1,
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                )
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
    private val condition =
        WeatherGlyph(context).apply {
            tag = "header-condition"
        }
    private val direction = FrameLayout(context)
    private val windIntensity =
        SvgIcon(context, "weather-windy-light").apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    private val wind =
        context.label("—", 16f, Hud.ink, true).apply {
            tag = "header-wind-speed"
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
    private var lastHour: Hour? = null
    private var lastState: Freshness? = null
    private var lastZone = ""

    init {
        tag = "header-weather"
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(112)
        val top = context.row()
        top.addView(temperature, LayoutParams(0, -2, 1f))
        top.addView(
            condition,
            LayoutParams(context.dp(60), context.dp(60)).apply { leftMargin = context.dp(4) },
        )
        addView(top, LayoutParams(-1, -2))
        val bottom =
            context.row().apply {
                tag = "header-wind"
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        bottom.addView(direction, LayoutParams(context.dp(44), context.dp(44)))
        bottom.addView(windIntensity, LayoutParams(context.dp(24), context.dp(24)))
        bottom.addView(wind, LayoutParams(-2, -2).apply { leftMargin = context.dp(4) })
        addView(bottom, LayoutParams(-1, context.dp(44)))
    }

    fun bind(hour: Hour?, state: Freshness, zone: String) {
        if (hour == lastHour && state == lastState && zone == lastZone) return
        lastHour = hour
        lastState = state
        lastZone = zone
        val temp = hour?.temperature?.takeIf { it.isFinite() }
        if (temp == null) {
            temperature.setText(R.string.header_missing_value)
            temperature.contentDescription =
                context.getString(R.string.header_temperature_unavailable)
        } else {
            temperature.text = context.getString(R.string.header_temperature, temp)
            temperature.contentDescription =
                context.getString(R.string.header_temperature_description, temp, state.name)
        }
        temperature.setTextColor(
            if (temp == null) Hud.muted
            else if (state == Freshness.LIVE) Hud.ink else Hud.color(state)
        )
        condition.bind(hour)
        condition.contentDescription =
            if (hour == null) context.getString(R.string.header_condition_unavailable)
            else
                context.getString(
                    R.string.header_condition_description,
                    weatherLabel(hour.condition)
                        ?: context.getString(R.string.header_condition_unavailable),
                    Times.format(hour.time, "EEE dd MMM HH:mm z", zone),
                    state.name,
                )
        condition.invalidate()
        val speed = hour?.wind?.takeIf { it.isFinite() }
        val windIcon = windIconName(speed)
        windIntensity.visibility = if (windIcon == null) INVISIBLE else VISIBLE
        if (windIcon != null) windIntensity.iconName = windIcon
        if (speed == null) {
            wind.setText(R.string.header_missing_value)
            wind.contentDescription = context.getString(R.string.header_wind_unavailable)
        } else {
            wind.text = context.getString(R.string.header_wind_speed, speed)
            wind.contentDescription =
                context.getString(R.string.header_wind_description, speed, state.name)
        }
        direction.removeAllViews()
        val bearing = hour?.get("DD")?.takeIf { it.isFinite() && it in 0.0..360.0 }
        if (bearing != null)
            direction.addView(
                WindDirectionGlyph(context, bearing),
                FrameLayout.LayoutParams(-1, -1),
            )
    }
}
