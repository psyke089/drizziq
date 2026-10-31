package app.drizziq

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

/** All pictograms are drawn here; there are no downloaded icon or font assets. */
class WeatherGlyph(context: Context, var code: Int? = null, private val logo: Boolean = false) :
    View(context) {
    var emphasized = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width, height) / 100f
        canvas.save()
        canvas.translate((width - 100 * scale) / 2, (height - 100 * scale) / 2)
        canvas.scale(scale, scale)
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (emphasized) 3.2f else 2f
        p.color = Hud.cyan
        p.strokeCap = Paint.Cap.ROUND
        if (logo) {
            canvas.drawCircle(50f, 50f, 36f, p)
            p.alpha = 75
            canvas.drawCircle(50f, 50f, 24f, p)
            p.alpha = 255
            canvas.drawLine(50f, 9f, 50f, 22f, p)
            canvas.drawLine(50f, 78f, 50f, 91f, p)
            canvas.drawLine(9f, 50f, 22f, 50f, p)
            canvas.drawLine(78f, 50f, 91f, 50f, p)
            p.style = Paint.Style.FILL
            val path =
                Path().apply {
                    moveTo(50f, 30f)
                    cubicTo(45f, 40f, 36f, 49f, 36f, 59f)
                    cubicTo(36f, 76f, 64f, 76f, 64f, 59f)
                    cubicTo(64f, 49f, 55f, 40f, 50f, 30f)
                }
            canvas.drawPath(path, p)
        } else if (code != null) {
            val c = code!!
            if (c in 0..2) {
                p.color = Hud.amber
                val cx = if (c == 0) 50f else 36f
                val cy = if (c == 0) 45f else 36f
                canvas.drawCircle(cx, cy, 15f, p)
                for (i in 0..7) {
                    val a = i * Math.PI / 4
                    canvas.drawLine(
                        cx + (cos(a) * 22).toFloat(),
                        cy + (sin(a) * 22).toFloat(),
                        cx + (cos(a) * 29).toFloat(),
                        cy + (sin(a) * 29).toFloat(),
                        p,
                    )
                }
            }
            if (c != 0) {
                p.color = Hud.cyan
                val path =
                    Path().apply {
                        moveTo(25f, 65f)
                        cubicTo(10f, 64f, 10f, 44f, 27f, 43f)
                        cubicTo(29f, 19f, 65f, 20f, 69f, 42f)
                        cubicTo(90f, 39f, 96f, 64f, 77f, 65f)
                        close()
                    }
                canvas.drawPath(path, p)
                if (c >= 50)
                    for (x in listOf(34f, 50f, 66f)) {
                        if (c in 70..79 || c in 85..94) {
                            canvas.drawLine(x - 3, 77f, x + 3, 83f, p)
                            canvas.drawLine(x + 3, 77f, x - 3, 83f, p)
                        } else canvas.drawLine(x, 75f, x - 4, 85f, p)
                    }
                if (c in 40..49) {
                    canvas.drawLine(22f, 76f, 77f, 76f, p)
                    canvas.drawLine(30f, 84f, 70f, 84f, p)
                }
            }
        }
        canvas.restore()
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

class WindDirectionGlyph(context: Context, private val bearing: Double) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription =
            "Wind from ${windDirectionName(bearing)}, ${bearing.roundToInt()} degrees; arrow shows wind flow"
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val scale = min(width, height) / 100f
        c.save()
        c.translate((width - 100 * scale) / 2, (height - 100 * scale) / 2)
        c.scale(scale, scale)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f
        p.color = Hud.line
        c.drawCircle(50f, 54f, 32f, p)
        for (i in 0..3) {
            c.save()
            c.rotate(i * 90f, 50f, 54f)
            c.drawLine(50f, 22f, 50f, 28f, p)
            c.restore()
        }
        p.style = Paint.Style.FILL
        p.color = Hud.muted
        p.textSize = 16f
        p.textAlign = Paint.Align.CENTER
        c.drawText("N", 50f, 16f, p)
        c.save()
        c.rotate(bearing.toFloat(), 50f, 54f)
        p.color = Hud.cyan
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(50f, 32f, 50f, 70f, p)
        p.style = Paint.Style.FILL
        c.drawPath(
            Path().apply {
                moveTo(50f, 76f)
                lineTo(39f, 59f)
                lineTo(50f, 64f)
                lineTo(61f, 59f)
                close()
            },
            p,
        )
        c.restore()
        c.restore()
    }
}

class NavigationGlyph(context: Context, private val destination: String) : View(context) {
    var highlighted = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val scale = min(width, height) / 24f
        c.save()
        c.translate((width - 24 * scale) / 2, (height - 24 * scale) / 2)
        c.scale(scale, scale)
        p.color = if (highlighted) Hud.cyan else Hud.muted
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        when (destination) {
            "TODAY" -> {
                c.drawCircle(12f, 12f, 4f, p)
                for (i in 0..7) {
                    c.save()
                    c.rotate(i * 45f, 12f, 12f)
                    c.drawLine(12f, 2f, 12f, 5f, p)
                    c.restore()
                }
            }
            "WEEK" -> {
                c.drawRect(3f, 5f, 21f, 21f, p)
                c.drawLine(3f, 10f, 21f, 10f, p)
                c.drawLine(8f, 2f, 8f, 7f, p)
                c.drawLine(16f, 2f, 16f, 7f, p)
                for (y in listOf(14f, 18f)) for (x in listOf(7f, 12f, 17f)) c.drawPoint(x, y, p)
            }
            "RADAR" -> {
                c.drawCircle(12f, 12f, 9f, p)
                c.drawCircle(12f, 12f, 5f, p)
                c.drawLine(12f, 12f, 18.4f, 5.6f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(9f, 14f, 1.5f, p)
            }
            "PLACES" -> {
                c.drawPath(
                    Path().apply {
                        moveTo(12f, 22f)
                        cubicTo(9f, 18f, 4f, 13f, 4f, 9f)
                        cubicTo(4f, -1f, 20f, -1f, 20f, 9f)
                        cubicTo(20f, 13f, 15f, 18f, 12f, 22f)
                        close()
                    },
                    p,
                )
                c.drawCircle(12f, 9f, 2.8f, p)
            }
        }
        c.restore()
    }
}
