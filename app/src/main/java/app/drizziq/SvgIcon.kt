package app.drizziq

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/** Native vector rendering; artwork lives in artwork/icons, not in drawing code. */
@SuppressLint("ViewConstructor") // Programmatic icon requires an asset name.
open class SvgIcon(context: Context, name: String) : View(context) {
    private var artwork = requireNotNull(context.getDrawable(IconAssets.resource(name))).mutate()
    var tint: Int? = null
        set(value) {
            field = value
            artwork.setTintList(value?.let { android.content.res.ColorStateList.valueOf(it) })
            invalidate()
        }

    var iconName = name
        set(value) {
            if (field == value) return
            field = value
            artwork = requireNotNull(context.getDrawable(IconAssets.resource(value))).mutate()
            artwork.setTintList(tint?.let { android.content.res.ColorStateList.valueOf(it) })
            fitArtwork()
            invalidate()
        }

    private fun fitArtwork() {
        val w = artwork.intrinsicWidth.coerceAtLeast(1)
        val h = artwork.intrinsicHeight.coerceAtLeast(1)
        val scale = min(width.toFloat() / w, height.toFloat() / h)
        val dw = (w * scale).roundToInt()
        val dh = (h * scale).roundToInt()
        val x = (width - dw) / 2
        val y = (height - dh) / 2
        artwork.setBounds(x, y, x + dw, y + dh)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fitArtwork()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        artwork.draw(canvas)
    }
}
