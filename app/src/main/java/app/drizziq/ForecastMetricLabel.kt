package app.drizziq

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kotlin.math.min

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

/** Shared, locally drawn metric symbols for the two forecast tables. */
@SuppressLint("ViewConstructor")
class ForecastMetricLabel(context: Context, metric: ForecastMetric, unit: String = "") :
    LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        contentDescription =
            listOf(metric.description, unit).filter { it.isNotEmpty() }.joinToString(", ")
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(MetricGlyph(context, metric), LayoutParams(context.dp(21), context.dp(21)))
        if (unit.isNotEmpty())
            addView(context.label(unit, 9f, Hud.muted, true).apply { gravity = Gravity.CENTER })
    }
}

@SuppressLint("ViewConstructor")
private class MetricGlyph(context: Context, private val metric: ForecastMetric) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Hud.muted
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val drop =
        Path().apply {
            moveTo(12f, 3f)
            cubicTo(9f, 8f, 5f, 12f, 5f, 16f)
            cubicTo(5f, 24f, 19f, 24f, 19f, 16f)
            cubicTo(19f, 12f, 15f, 8f, 12f, 3f)
        }

    override fun onDraw(canvas: Canvas) {
        val scale = min(width, height) / 26f
        canvas.save()
        canvas.translate((width - 26 * scale) / 2, (height - 26 * scale) / 2)
        canvas.scale(scale, scale)
        when (metric) {
            ForecastMetric.TIME -> {
                canvas.drawCircle(13f, 13f, 9f, paint)
                canvas.drawLine(13f, 7f, 13f, 13f, paint)
                canvas.drawLine(13f, 13f, 17f, 15f, paint)
            }
            ForecastMetric.TEMPERATURE,
            ForecastMetric.HIGH,
            ForecastMetric.LOW -> {
                canvas.drawRoundRect(7f, 3f, 13f, 18f, 3f, 3f, paint)
                canvas.drawCircle(10f, 20f, 4f, paint)
                canvas.drawLine(10f, 10f, 10f, 20f, paint)
                if (metric != ForecastMetric.TEMPERATURE) {
                    val a = if (metric == ForecastMetric.HIGH) 6f else 18f
                    val b = if (metric == ForecastMetric.HIGH) 18f else 6f
                    canvas.drawLine(21f, a, 21f, b, paint)
                    canvas.drawLine(18f, (a + b) / 2, 21f, a, paint)
                    canvas.drawLine(24f, (a + b) / 2, 21f, a, paint)
                }
            }
            ForecastMetric.RAIN,
            ForecastMetric.PROBABILITY,
            ForecastMetric.HUMIDITY -> {
                canvas.drawPath(drop, paint)
                if (metric == ForecastMetric.PROBABILITY) {
                    canvas.drawLine(9f, 18f, 15f, 12f, paint)
                    canvas.drawCircle(9f, 12f, 1f, paint)
                    canvas.drawCircle(15f, 18f, 1f, paint)
                } else if (metric == ForecastMetric.HUMIDITY)
                    canvas.drawLine(8f, 17f, 16f, 17f, paint)
            }
            ForecastMetric.WIND,
            ForecastMetric.GUST -> {
                canvas.drawLine(3f, 9f, 17f, 9f, paint)
                canvas.drawArc(14f, 3f, 22f, 9f, -180f, 270f, false, paint)
                canvas.drawLine(3f, 14f, 22f, 14f, paint)
                canvas.drawLine(
                    3f,
                    19f,
                    if (metric == ForecastMetric.GUST) 20f else 13f,
                    19f,
                    paint,
                )
                if (metric == ForecastMetric.GUST) canvas.drawLine(17f, 22f, 22f, 19f, paint)
            }
            ForecastMetric.DIRECTION -> {
                canvas.drawLine(5f, 21f, 21f, 5f, paint)
                canvas.drawLine(12f, 5f, 21f, 5f, paint)
                canvas.drawLine(21f, 5f, 21f, 14f, paint)
            }
            ForecastMetric.SUN -> {
                canvas.drawCircle(13f, 13f, 5f, paint)
                for (i in 0..7) {
                    canvas.save()
                    canvas.rotate(i * 45f, 13f, 13f)
                    canvas.drawLine(13f, 2f, 13f, 5f, paint)
                    canvas.restore()
                }
            }
            ForecastMetric.SKY -> {
                canvas.drawRoundRect(3f, 12f, 23f, 21f, 4f, 4f, paint)
                canvas.drawArc(7f, 5f, 19f, 20f, 180f, 180f, false, paint)
            }
        }
        canvas.restore()
    }
}
