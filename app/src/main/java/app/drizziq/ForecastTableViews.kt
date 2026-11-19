package app.drizziq

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.util.Locale
import kotlin.math.abs

@SuppressLint("ViewConstructor") // Programmatic-only view requires formatted day text.
class WeekDayLabel(context: Context, weekday: String, date: String) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(4), 0, context.dp(4), 0)
        addView(
            context.label(weekday, 15f, Hud.cyan, true).apply {
                tag = "week-weekday"
                gravity = Gravity.CENTER
            }
        )
        addView(
            context.label(date, 10f, Hud.muted, true).apply {
                tag = "week-date"
                gravity = Gravity.CENTER
            }
        )
    }
}

@SuppressLint("ViewConstructor") // Programmatic-only view requires place data and callbacks.
class FavouriteCard(
    context: Context,
    place: Place,
    selected: Boolean,
    select: () -> Unit,
    delete: (() -> Unit)?,
) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(12), context.dp(8), context.dp(4), context.dp(8))
        isSelected = selected
        isClickable = true
        isFocusable = true
        val name = placeDisplayName(place, null)
        contentDescription = "Select favourite $name"
        if (selected) setBackgroundColor(0xFF102B35.toInt())
        setOnClickListener { select() }
        addView(
            SvgIcon(context, "nav-places").apply {
                tint = if (selected) Hud.cyan else Hud.muted
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(context.dp(20), context.dp(20)).apply { rightMargin = context.dp(12) },
        )

        val content = context.column()
        content.addView(context.label(name, 17f, if (selected) Hud.cyan else Hud.ink))
        content.space(4)
        content.addView(
            context.label(formatCoordinates(place.lat, place.lon), 10f, Hud.muted, true)
        )
        addView(content, LayoutParams(0, -2, 1f))

        minimumHeight = context.dp(64)
        if (delete != null) {
            val deleteButton =
                FrameLayout(context).apply {
                    tag = "favourite-delete"
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Delete favourite $name"

                    addView(
                        SvgIcon(context, "delete").apply {
                            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                        },
                        FrameLayout.LayoutParams(context.dp(19), context.dp(19), Gravity.CENTER),
                    )
                    setOnClickListener { delete() }
                }
            addView(
                deleteButton,
                LayoutParams(context.dp(48), context.dp(48)).apply { leftMargin = context.dp(12) },
            )
        }
    }
}

private fun formatCoordinates(lat: Double, lon: Double) =
    String.format(
        Locale.US,
        "%.4f°%s  %.4f°%s",
        abs(lat),
        if (lat >= 0) "N" else "S",
        abs(lon),
        if (lon >= 0) "E" else "W",
    )

/** The sun-card gap yields space before the forecast needs vertical scrolling. */
internal class ForecastBody(context: Context) : LinearLayout(context) {
    var sunGap: android.view.View? = null

    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val gap = sunGap
        if (gap == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        gap.layoutParams.height = 0
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val available = MeasureSpec.getSize(heightMeasureSpec)
        gap.layoutParams.height = (available - measuredHeight).coerceIn(0, context.dp(24))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
