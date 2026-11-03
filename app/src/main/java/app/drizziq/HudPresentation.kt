package app.drizziq

internal fun hudFormat(pattern: String, vararg arguments: Any): String =
    String.format(java.util.Locale.US, pattern, *arguments)

private val temperatureStops =
    intArrayOf(
        0xff8ebbff.toInt(),
        0xff79d5ee.toInt(),
        0xff7ce6d2.toInt(),
        0xfff4bc72.toInt(),
        0xfff4778c.toInt(),
    )

/** Absolute Celsius; finite inputs have already been checked by the presentation model. */
internal fun temperatureColor(celsius: Double): Int {
    val position = ((celsius.coerceIn(-10.0, 30.0) + 10.0) / 10.0)
    val low = position.toInt().coerceAtMost(3)
    val fraction = position - low
    val a = temperatureStops[low]
    val b = temperatureStops[low + 1]
    var color = 0xff000000.toInt()
    var shift = 16
    while (shift >= 0) {
        val first = (a ushr shift) and 255
        val last = (b ushr shift) and 255
        color = color or ((first + (last - first) * fraction).toInt().coerceIn(0, 255) shl shift)
        shift -= 8
    }
    return color
}
