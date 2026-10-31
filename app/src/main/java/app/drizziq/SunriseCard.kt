package app.drizziq

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.*

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
                SolarEventGlyph(context, rising),
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

/** Generated locally: sun, horizon and rise/set arrow. All paths are prepared once. */
@SuppressLint("ViewConstructor") // Private programmatic child; never inflated from XML.
private class SolarEventGlyph(context: Context, private val rising: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sun = RectF(18f, 28f, 64f, 74f)
    private val arrow =
        Path().apply {
            if (rising) {
                moveTo(76f, 42f)
                lineTo(76f, 16f)
                moveTo(66f, 26f)
                lineTo(76f, 16f)
                lineTo(86f, 26f)
            } else {
                moveTo(76f, 16f)
                lineTo(76f, 42f)
                moveTo(66f, 32f)
                lineTo(76f, 42f)
                lineTo(86f, 32f)
            }
        }

    override fun onDraw(canvas: Canvas) {
        val scale = min(width, height) / 100f
        canvas.save()
        canvas.translate((width - 100 * scale) / 2, (height - 100 * scale) / 2)
        canvas.scale(scale, scale)
        paint.color = Hud.amber
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.5f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawArc(sun, 180f, 180f, false, paint)
        canvas.drawLine(10f, 51f, 68f, 51f, paint)
        canvas.drawLine(41f, 18f, 41f, 10f, paint)
        canvas.drawLine(16f, 25f, 10f, 19f, paint)
        canvas.drawLine(21f, 58f, 21f, 64f, paint)
        paint.color = Hud.cyan
        canvas.drawPath(arrow, paint)
        canvas.restore()
    }
}
