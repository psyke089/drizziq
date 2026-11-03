package app.drizziq

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale
import kotlin.math.max

/** Programmatic-only native grid: one horizontal timeline keeps every weather row aligned. */
@SuppressLint("ViewConstructor")
class HourlyForecast(
    context: Context,
    private val columns: List<HourlyColumn>,
    zone: String,
    initialOffset: Int,
) : LinearLayout(context) {
    private val horizontal = HorizontalScrollView(context)
    private val highlightPaint =
        Paint().apply {
            color = Hud.cyan
            strokeWidth = context.dp(1).toFloat()
        }
    private var highlighted = -1
    private lateinit var ribbon: TemperatureRibbon
    private val track =
        object : LinearLayout(context) {
            init {
                orientation = VERTICAL
            }

            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                if (highlighted < 0) return
                val left = context.dp(56) * highlighted.toFloat()
                val right = left + context.dp(56)
                canvas.drawLine(left, 1f, right, 1f, highlightPaint)
                canvas.drawLine(left, height - 1f, right, height - 1f, highlightPaint)
            }
        }
    private val highlightTick =
        object : Runnable {
            override fun run() {
                updateHighlight()
                track.invalidate()
                postDelayed(this, 60000L - AppPlatform.now() % 60000L)
            }
        }
    private var initialScrollApplied = false
    private val requestedOffset = initialOffset
    val timelineOffset: Int
        get() = if (initialScrollApplied) horizontal.scrollX else requestedOffset

    init {
        orientation = HORIZONTAL
        contentDescription = "Hourly forecast, swipe horizontally for later hours"
        val rail =
            context.column().apply {
                tag = "hourly-legend"
                setBackgroundColor(Hud.legend)
            }
        horizontal.apply {
            isHorizontalScrollBarEnabled = true
            contentDescription = "Hourly forecast timeline"
            addView(track, LayoutParams(context.dp(56) * columns.size, -2))
        }
        addView(rail, LayoutParams(context.dp(40), -2))
        addView(horizontal, LayoutParams(0, -2, 1f))
        fun title(metric: ForecastMetric, height: Int, unit: String = "") {
            rail.addView(
                ForecastMetricLabel(context, metric, unit),
                LayoutParams(-1, context.dp(height)),
            )
        }
        fun cells(height: Int, tag: String? = null, build: (HourlyColumn, Int) -> View) {
            val row = context.row().apply { this.tag = tag }
            columns.forEachIndexed { i, column ->
                row.addView(
                    build(column, i).apply {
                        if (background == null)
                            setBackgroundColor(if (i % 2 == 0) Hud.panel else Hud.bg)
                    },
                    LayoutParams(context.dp(56), context.dp(height)),
                )
            }
            track.addView(row)
        }
        fun value(text: String, color: Int = Hud.ink) =
            context.label(text, 12f, color, true).apply { gravity = Gravity.CENTER }
        fun number(value: Double?, digits: Int) =
            value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.${digits}f", it) }
                ?: "—"
        title(ForecastMetric.TIME, 40)
        cells(40) { column, index ->
            val local =
                java.time.Instant.ofEpochMilli(column.time).atZone(java.time.ZoneId.of(zone))
            val repeated = local.zone.rules.getValidOffsets(local.toLocalDateTime()).size > 1
            value(
                    Times.format(
                        column.time,
                        if (repeated) "HH:mm\nz"
                        else if (index > 0 && local.toLocalTime() == java.time.LocalTime.MIDNIGHT)
                            "HH:mm\ndd MMM"
                        else "HH:mm",
                        zone,
                    ),
                    Hud.ink,
                )
                .apply {
                    tag = "hour-time:$index"
                    textSize = 12f
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                    contentDescription = Times.format(column.time, "EEEE dd MMM HH:mm z", zone)
                    if (column.hour != null && column.time < AppPlatform.now()) {
                        contentDescription = "$contentDescription, earlier DWD forecast"
                    }
                }
        }
        title(ForecastMetric.SKY, 42)
        cells(42) { column, index ->
            WeatherGlyph(context, column.hour?.condition, precipitation = column.hour?.get("RR1c"))
                .apply {
                    tag = "hour-icon:$index"
                    contentDescription =
                        weatherLabel(column.hour?.condition) ?: "No weather condition supplied"
                }
        }
        title(ForecastMetric.TEMPERATURE, 128, "°C")
        ribbon = TemperatureRibbon(context, columns)
        track.addView(ribbon, LayoutParams(context.dp(56) * columns.size, context.dp(128)))
        title(ForecastMetric.RAIN, 46, "mm")
        val rainMax =
            max(
                1.0,
                columns
                    .mapNotNull { it.hour?.get("RR1c")?.takeIf { n -> n.isFinite() && n >= 0 } }
                    .maxOrNull() ?: 1.0,
            )
        cells(46) { column, _ ->
            val rain = column.hour?.get("RR1c")?.takeIf { it.isFinite() && it >= 0 }
            FrameLayout(context).apply {
                rain?.let {
                    addView(
                        View(context).apply { setBackgroundColor(0x447CE6D2) },
                        FrameLayout.LayoutParams(
                            -1,
                            context.dp((it / rainMax * 38).toFloat()),
                            Gravity.BOTTOM,
                        ),
                    )
                }
                addView(value(number(rain, 1), Hud.cyan), FrameLayout.LayoutParams(-1, -1))
                contentDescription =
                    if (rain == null) "Rain unavailable"
                    else "Rain ${number(rain,1)} millimetres in the preceding hour"
            }
        }
        if (columns.any { it.hour?.precipitationProbability != null }) {
            title(ForecastMetric.PROBABILITY, 38, "%")
            cells(38, "hourly-probability") { column, _ ->
                val probability = column.hour?.precipitationProbability
                value(if (probability == null) "—" else "${number(probability,0)}%", Hud.cyan)
                    .apply {
                        contentDescription =
                            if (probability == null)
                                context.hudString(R.string.hourly_probability_unavailable)
                            else
                                context.hudString(
                                    R.string.hourly_probability_description,
                                    probability,
                                )
                    }
            }
        }
        title(ForecastMetric.DIRECTION, 40)
        cells(40) { column, _ ->
            val bearing = column.hour?.get("DD")?.takeIf { it.isFinite() && it in 0.0..360.0 }
            if (bearing == null) value("—", Hud.muted) else WindDirectionGlyph(context, bearing)
        }
        fun windCell(speed: Double?, name: String): View {
            val icon = windIconName(speed)
            return context.row().apply {
                gravity = Gravity.CENTER
                if (icon != null)
                    addView(
                        SvgIcon(context, icon).apply {
                            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                        },
                        LayoutParams(context.dp(18), context.dp(18)).apply {
                            rightMargin = context.dp(3)
                        },
                    )
                addView(value(if (icon == null) "—" else number(speed, 0)))
                contentDescription =
                    if (name == "Wind gust") {
                        if (speed == null || icon == null)
                            context.hudString(R.string.hourly_gust_unavailable)
                        else context.hudString(R.string.hourly_gust_description, speed)
                    } else
                        "$name ${if (icon == null) "unavailable"
                    else number(speed, 0) + " kilometres per hour"}"
            }
        }
        title(ForecastMetric.WIND, 40, "km/h")
        cells(40, "hourly-wind") { column, _ -> windCell(column.hour?.wind, "Wind") }
        if (columns.any { it.hour?.gust != null }) {
            title(ForecastMetric.GUST, 40, "km/h")
            cells(40, "hourly-gusts") { column, _ -> windCell(column.hour?.gust, "Wind gust") }
        }
        if (columns.any { it.hour?.humidity != null }) {
            title(ForecastMetric.HUMIDITY, 38, "%")
            cells(38) { column, _ ->
                val humidity = column.hour?.humidity
                value(number(humidity, 0), Hud.cyan).apply {
                    contentDescription =
                        if (humidity == null) "Relative humidity unavailable"
                        else "Relative humidity ${number(humidity, 0)} percent"
                }
            }
        }
        updateHighlight()
        horizontal.post {
            horizontal.scrollTo(initialOffset, 0)
            initialScrollApplied = true
        }
    }

    private fun updateHighlight() {
        val now = AppPlatform.now()
        val index = currentHourColumn(columns, now)
        val current = columns.getOrNull(index)
        val next =
            if (current != null && now >= current.time && now < current.time + 3600000L) index
            else -1
        if (highlighted == next) return
        highlighted = next
        for (r in 0 until track.childCount) {
            val row = track.getChildAt(r) as? LinearLayout ?: continue
            for (i in 0 until row.childCount) row.getChildAt(i)
                .setBackgroundColor(
                    if (i == next) Hud.currentHour else if (i % 2 == 0) Hud.panel else Hud.bg
                )
        }
        ribbon.highlighted = next
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        highlightTick.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(highlightTick)
        super.onDetachedFromWindow()
    }
}

/** Prepared paths avoid draw-time allocations; missing hours break both line and fill. */
@SuppressLint("ViewConstructor")
private class TemperatureRibbon(context: Context, private val columns: List<HourlyColumn>) :
    View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val values = columns.map { it.hour?.temperature?.takeIf { v -> v.isFinite() } }
    private val labels = values.map { it?.let { v -> String.format(Locale.US, "%.0f°", v) } ?: "—" }
    private val points = Array<PointF?>(columns.size) { null }
    private val lines = mutableListOf<Path>()
    private val fills = mutableListOf<Path>()
    private var gradient: Shader? = null
    private var lineGradient: Shader? = null
    var highlighted = -1
        set(value) {
            field = value
            invalidate()
        }

    init {
        if (android.os.Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription =
            columns.indices.joinToString("; ") {
                "${Times.format(columns[it].time,"dd MMM HH:mm","UTC")} UTC: ${labels[it]} Celsius"
            }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        lines.clear()
        fills.clear()
        val valid = values.filterNotNull()
        val lo = (valid.minOrNull() ?: 0.0) - 2.0
        val range = max(4.0, (valid.maxOrNull() ?: 0.0) - lo + 2.0)
        for (i in values.indices) {
            points[i] =
                values[i]?.let {
                    PointF(
                        (i + .5f) * w / columns.size,
                        (h * .78 - (it - lo) / range * h * .56).toFloat(),
                    )
                }
        }
        var start = 0
        while (start < points.size) {
            if (points[start] == null) {
                start++
                continue
            }
            val first = points[start]!!
            val line = Path().apply { moveTo(first.x, first.y) }
            var end = start
            while (end + 1 < points.size && points[end + 1] != null) {
                val a = points[end]!!
                val b = points[++end]!!
                val dx = (b.x - a.x) / 2
                line.cubicTo(a.x + dx, a.y, b.x - dx, b.y, b.x, b.y)
            }
            lines += line
            fills +=
                Path(line).apply {
                    lineTo(points[end]!!.x, h.toFloat())
                    lineTo(first.x, h.toFloat())
                    close()
                }
            start = end + 1
        }
        fun y(value: Double) = (h * .78 - (value - lo) / range * h * .56).toFloat()
        val colors =
            intArrayOf(-10, 0, 10, 20, 30).map { temperatureColor(it.toDouble()) }.toIntArray()
        lineGradient =
            LinearGradient(0f, y(-10.0), 0f, y(30.0), colors, null, Shader.TileMode.CLAMP)
        gradient =
            ComposeShader(
                requireNotNull(lineGradient),
                LinearGradient(0f, 0f, 0f, h.toFloat(), 0x2effffff, 0, Shader.TileMode.CLAMP),
                PorterDuff.Mode.DST_IN,
            )
    }

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        for (i in columns.indices) {
            paint.color =
                if (i == highlighted) Hud.currentHour else if (i % 2 == 0) Hud.panel else Hud.bg
            canvas.drawRect(
                i * width.toFloat() / columns.size,
                0f,
                (i + 1) * width.toFloat() / columns.size,
                height.toFloat(),
                paint,
            )
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Hud.line
        for (i in columns.indices) canvas.drawLine(
            i * width.toFloat() / columns.size,
            0f,
            i * width.toFloat() / columns.size,
            height.toFloat(),
            paint,
        )
        paint.style = Paint.Style.FILL
        paint.shader = gradient
        fills.forEach { canvas.drawPath(it, paint) }
        paint.shader = null
        paint.shader = lineGradient
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(3).toFloat()
        lines.forEach { canvas.drawPath(it, paint) }
        paint.shader = null
        paint.color = Hud.ink
        paint.style = Paint.Style.FILL
        paint.textSize = context.dp(13).toFloat()
        paint.textAlign = Paint.Align.CENTER
        for (i in points.indices) {
            val point = points[i]
            if (point != null) {
                canvas.drawCircle(point.x, point.y, context.dp(2).toFloat(), paint)
                canvas.drawText(labels[i], point.x, point.y - context.dp(12), paint)
            } else {
                paint.color = Hud.muted
                canvas.drawText("—", (i + .5f) * width / columns.size, height * .5f, paint)
                paint.color = Hud.ink
            }
        }
    }
}
