package app.drizziq

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** Fits up to two lines, shrinking before Android would have to split a word. */
// The platform's Layout constant also works before it was moved to LineBreaker in API 29.
@SuppressLint("ViewConstructor", "WrongConstant")
internal class FittingPlaceTitle(context: Context) : TextView(context) {
    private val measuringPaint = TextPaint()
    private val textTop = HeaderTextTop(this, "H")

    init {
        setTextColor(Hud.ink)
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.TOP
        minimumHeight = context.dp(44)
        compoundDrawablePadding = context.dp(6)
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        ellipsize = TextUtils.TruncateAt.END
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available =
            MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
        var size = 28
        var lines = 2
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            measuringPaint.set(paint)
            while (size >= 16 && !fits(size, available)) size--
            if (size < 16) {
                size = 16
                lines = 1
            }
        }
        val pixels = pixelsAt(size)
        if (textSize != pixels) setTextSize(TypedValue.COMPLEX_UNIT_PX, pixels)
        textTop.align()
        if (maxLines != lines) maxLines = lines
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun fits(size: Int, width: Int): Boolean {
        if (width <= 0) return false
        val title = text
        measuringPaint.textSize = pixelsAt(size)
        val layout =
            StaticLayout.Builder.obtain(title, 0, title.length, measuringPaint, width)
                .setMaxLines(2)
                .build()
        if (
            layout.lineCount > 2 ||
                (0 until layout.lineCount).any { layout.getLineWidth(it) > width }
        )
            return false
        val end = layout.getLineEnd(layout.lineCount - 1)
        if (end < title.length) return false
        if (layout.lineCount == 1) return true
        val split = layout.getLineEnd(0)
        return split == 0 ||
            split == title.length ||
            title[split - 1].isWhitespace() ||
            title[split].isWhitespace()
    }

    private fun pixelsAt(size: Int): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            size.toFloat(),
            resources.displayMetrics,
        )
}
