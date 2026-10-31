package app.drizziq

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Calculated sunrise/sunset, independent of DWD coverage and connection state. */
class SunriseCard(context: Context) : LinearLayout(context) {
    private val rise: TextView
    private val set: TextView

    init {
        tag = "sunrise-card"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        pad(14)
        background = panel()
        minimumHeight = context.dp(92)
        fun event(title: Int, rising: Boolean): TextView {
            val group = context.row()
            group.addView(
                SvgIcon(context, if (rising) "sunrise" else "sunset"),
                LayoutParams(context.dp(38), context.dp(48)),
            )
            val text = context.column()
            text.addView(context.label(context.getString(title), 9f, Hud.muted, true))
            text.space(5)
            val value =
                context.label("—", 22f, Hud.ink, true).apply {
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                }
            text.addView(value, LayoutParams(-1, context.dp(30)))
            group.addView(text, LayoutParams(0, -2, 1f).apply { leftMargin = context.dp(6) })
            addView(group, LayoutParams(0, -2, 1f))
            return value
        }
        rise = event(R.string.sunrise_title, true)
        set = event(R.string.sunset_title, false)
    }

    fun bind(day: SolarDay?, zone: String, failed: Boolean = false) {
        fun bind(view: TextView, event: Long?, rising: Boolean) {
            view.text =
                event?.let { Times.format(it, "HH:mm", zone) }
                    ?: if (day == null) "—"
                    else
                        context.getString(
                            if (rising) R.string.sunrise_none else R.string.sunset_none
                        )
            view.textSize = if (event != null || day == null) 22f else 11f
            view.contentDescription =
                if (event != null)
                    context.getString(
                        if (rising) R.string.sunrise_accessibility
                        else R.string.sunset_accessibility,
                        Times.format(event, "HH:mm z", zone),
                    )
                else
                    context.getString(
                        if (failed) R.string.sun_times_unavailable
                        else if (day == null) R.string.sun_times_loading
                        else if (rising) R.string.sunrise_none else R.string.sunset_none
                    )
        }
        bind(rise, day?.sunrise, true)
        bind(set, day?.sunset, false)
        contentDescription =
            when (day?.state) {
                SolarState.POLAR_DAY -> context.getString(R.string.sun_polar_day)
                SolarState.POLAR_NIGHT -> context.getString(R.string.sun_polar_night)
                else -> context.getString(R.string.sun_times_card)
            }
    }
}
