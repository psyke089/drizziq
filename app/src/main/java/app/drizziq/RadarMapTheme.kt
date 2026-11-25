package app.drizziq

import android.graphics.ColorMatrixColorFilter

internal enum class RadarMapTheme(
    val title: String,
    val preview: String,
    private val red: FloatArray,
    private val green: FloatArray,
    private val blue: FloatArray,
) {
    ORIGINAL(
        "Original",
        "map-original",
        floatArrayOf(-.17f, -.17f, -.17f, 0f, 112f),
        floatArrayOf(-.18f, -.18f, -.18f, 0f, 130f),
        floatArrayOf(-.16f, -.16f, -.16f, 0f, 149f),
    ),
    MEDIUM(
        "Medium contrast",
        "map-medium",
        channel(156, 26),
        channel(179, 39),
        channel(196, 55),
    ),
    HIGH(
        "High contrast",
        "map-high",
        channel(232, 16),
        channel(241, 28),
        channel(247, 42),
    );

    val filter by lazy {
        ColorMatrixColorFilter(red + green + blue + floatArrayOf(0f, 0f, 0f, 1f, 0f))
    }
}

/** Maps OSM luminance onto a dark HUD palette, keeping dark map features bright. */
private fun channel(black: Int, white: Int): FloatArray {
    val range = (white - black) / 255f
    return floatArrayOf(.2126f * range, .7152f * range, .0722f * range, 0f, black.toFloat())
}
