package app.drizziq

/** Camera position in normalized Web Mercator coordinates, independent of screen size. */
internal data class RadarViewport(val x: Double, val y: Double, val zoom: Double) {
    init {
        require(x.isFinite() && x >= 0.0 && x < 1.0)
        require(y.isFinite() && y in 0.0..1.0)
        require(zoom.isFinite() && zoom in 2.0..17.0)
    }

    fun encode(place: Place): String =
        listOf("1", place.lat, place.lon, x, y, zoom).joinToString("|")

    companion object {
        fun decode(encoded: String?, place: Place): RadarViewport? {
            val fields = encoded?.split('|') ?: return null
            if (
                fields.size != 6 ||
                    fields[0] != "1" ||
                    fields[1].toDoubleOrNull() != place.lat ||
                    fields[2].toDoubleOrNull() != place.lon
            )
                return null
            return runCatching {
                RadarViewport(fields[3].toDouble(), fields[4].toDouble(), fields[5].toDouble())
            }
                .getOrNull()
        }
    }
}
