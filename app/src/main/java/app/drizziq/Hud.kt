package app.drizziq

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*
import kotlin.math.*

object Hud {
    val bg = Color.rgb(8, 15, 25)
    val panel = Color.rgb(15, 26, 40)
    val line = Color.rgb(35, 53, 72)
    val ink = Color.rgb(232, 241, 247)
    val muted = Color.rgb(147, 167, 188)
    val cyan = Color.rgb(124, 230, 210)
    val amber = Color.rgb(244, 188, 114)
    val red = Color.rgb(244, 119, 140)

    fun color(state: Freshness) =
        when (state) {
            Freshness.LIVE -> cyan
            Freshness.STALE -> amber
            Freshness.ERROR -> red
            else -> muted
        }
}

fun Context.dp(n: Float) = (n * resources.displayMetrics.density).roundToInt()

fun Context.dp(n: Int) = dp(n.toFloat())

fun panel(fill: Int = Hud.panel, stroke: Int = Hud.line, radius: Float = 16f) =
    GradientDrawable().apply {
        setColor(fill)
        setStroke(1, stroke)
        cornerRadius = radius
    }

fun Context.label(text: String, size: Float = 14f, color: Int = Hud.ink, mono: Boolean = false) =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        typeface =
            if (mono) Typeface.create("monospace", Typeface.NORMAL)
            else Typeface.create("sans-serif", Typeface.NORMAL)
    }

fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.row() =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

fun View.pad(n: Int) {
    val d = context.dp(n)
    setPadding(d, d, d, d)
}

fun LinearLayout.space(height: Int) =
    addView(View(context), LinearLayout.LayoutParams(1, context.dp(height)))

fun Context.action(title: String, description: String = title, click: () -> Unit) =
    Button(this).apply {
        text = title
        contentDescription = description
        textSize = 11f
        letterSpacing = .08f
        setTextColor(Hud.cyan)
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        background = panel(Hud.panel, Hud.line, dp(10).toFloat())
        setOnClickListener { click() }
    }

/** Weather artwork comes from editable SVGs compiled to native Android vectors. */
@SuppressLint("ViewConstructor") // Kotlin-only view; never inflated from XML or layout tools.
class WeatherGlyph(
    context: Context,
    code: Int? = null,
    private val logo: Boolean = false,
    precipitation: Double? = null,
) : SvgIcon(context, if (logo) "logo" else weatherIconName(code, precipitation)) {
    fun bind(hour: Hour?) {
        if (!logo) iconName = weatherIconName(hour?.condition, hour?.get("RR1c"))
    }
}

/** Bearing names describe where the wind comes from; the arrow shows its flow. */
fun windDirectionName(bearing: Double): String =
    listOf(
        "N",
        "NNE",
        "NE",
        "ENE",
        "E",
        "ESE",
        "SE",
        "SSE",
        "S",
        "SSW",
        "SW",
        "WSW",
        "W",
        "WNW",
        "NW",
        "NNW",
    )[((bearing / 22.5).roundToInt() % 16 + 16) % 16]

/** The SVG compass stays fixed while its separate SVG arrow follows the bearing. */
@SuppressLint("ViewConstructor") // Kotlin-only view requires an explicit wind bearing.
class WindDirectionGlyph(context: Context, private val bearing: Double) : View(context) {
    private val compass =
        requireNotNull(context.getDrawable(IconAssets.resource("wind-compass"))).mutate()
    private val arrow =
        requireNotNull(context.getDrawable(IconAssets.resource("wind-arrow"))).mutate()

    init {
        compass.setBounds(0, 0, 100, 100)
        arrow.setBounds(0, 0, 100, 100)
        contentDescription =
            "Wind from ${windDirectionName(bearing)}, ${bearing.roundToInt()} degrees; arrow shows wind flow"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width, height) / 100f
        canvas.save()
        canvas.translate((width - 100 * scale) / 2, (height - 100 * scale) / 2)
        canvas.scale(scale, scale)
        compass.draw(canvas)
        canvas.rotate(bearing.toFloat(), 50f, 54f)
        arrow.draw(canvas)
        canvas.restore()
    }
}

@SuppressLint("ViewConstructor") // Kotlin-only view requires a navigation destination.
class NavigationGlyph(context: Context, destination: String) :
    SvgIcon(context, "nav-${destination.lowercase()}") {
    var highlighted = false
        set(value) {
            field = value
            tint = if (value) Hud.cyan else Hud.muted
        }
}
