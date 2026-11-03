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
    delete: () -> Unit,
) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        pad(12)
        isSelected = selected
        isClickable = true
        isFocusable = true
        contentDescription = "Select favourite ${place.label}"
        background =
            panel(
                if (selected) 0xFF102B35.toInt() else Hud.panel,
                if (selected) Hud.cyan else Hud.line,
                context.dp(16).toFloat(),
            )
        setOnClickListener { select() }

        val content = context.column()
        content.addView(context.label(place.label, 17f))
        content.space(4)
        content.addView(
            context.label(formatCoordinates(place.lat, place.lon), 10f, Hud.muted, true)
        )
        addView(content, LayoutParams(0, -2, 1f))

        val deleteButton =
            FrameLayout(context).apply {
                tag = "favourite-delete"
                isClickable = true
                isFocusable = true
                contentDescription = "Delete favourite ${place.label}"
                background = panel(Hud.bg, Hud.line, context.dp(10).toFloat())
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
            LayoutParams(context.dp(42), context.dp(42)).apply { leftMargin = context.dp(12) },
        )
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
