package app.drizziq

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.*

enum class SolarState {
    NORMAL,
    POLAR_DAY,
    POLAR_NIGHT,
    NO_LOCAL_DAY,
}

data class SolarDay(val sunrise: Long?, val sunset: Long?, val state: SolarState)

/** Local, standard-horizon events: geometric solar centre crosses -0.833 degrees. */
object SolarCalculator {
    fun calculate(place: Place, date: LocalDate): SolarDay {
        val zone = ZoneId.of(place.zone)
        val localStart = date.atStartOfDay(zone)
        if (localStart.toLocalDate() != date) return SolarDay(null, null, SolarState.NO_LOCAL_DAY)
        val start = localStart.toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (end <= start) return SolarDay(null, null, SolarState.NO_LOCAL_DAY)
        fun height(time: Long) = elevation(time, place.lat, place.lon) + .833
        var rise: Long? = null
        var set: Long? = null
        var previousTime = start
        var previous = height(start)
        var maximum = previous
        var minimum = previous
        while (previousTime < end) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val time = minOf(end, previousTime + 60000)
            val value = height(time)
            maximum = max(maximum, value)
            minimum = min(minimum, value)
            if ((previous <= 0 && value > 0) || (previous >= 0 && value < 0)) {
                var low = previousTime
                var high = time
                val above = previous > 0
                while (high - low > 100) {
                    val mid = low + (high - low) / 2
                    if ((height(mid) > 0) == above) low = mid else high = mid
                }
                val event = low + (high - low) / 2
                if (event >= start && event < end) {
                    if (value > previous && rise == null) rise = event
                    if (value < previous && set == null) set = event
                }
            }
            previous = value
            previousTime = time
        }
        val state =
            when {
                rise != null || set != null -> SolarState.NORMAL
                minimum >= 0 -> SolarState.POLAR_DAY
                maximum <= 0 -> SolarState.POLAR_NIGHT
                else -> SolarState.NORMAL
            }
        return SolarDay(rise, set, state)
    }

    // NOAA/Meeus solar coordinates, evaluated at each UTC instant; longitude is east-positive.
    private fun elevation(time: Long, latitude: Double, longitude: Double): Double {
        val t = (time / 86400000.0 + 2440587.5 - 2451545.0) / 36525.0
        fun rad(d: Double) = Math.toRadians(d)
        val l0 = ((280.46646 + t * (36000.76983 + t * .0003032)) % 360 + 360) % 360
        val m = 357.52911 + t * (35999.05029 - .0001537 * t)
        val e = .016708634 - t * (.000042037 + .0000001267 * t)
        val c =
            sin(rad(m)) * (1.914602 - t * (.004817 + .000014 * t)) +
                sin(rad(2 * m)) * (.019993 - .000101 * t) +
                sin(rad(3 * m)) * .000289
        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + c - .00569 - .00478 * sin(rad(omega))
        val epsilon =
            23 +
                (26 + (21.448 - t * (46.815 + t * (.00059 - t * .001813))) / 60) / 60 +
                .00256 * cos(rad(omega))
        val declination = asin(sin(rad(epsilon)) * sin(rad(lambda)))
        val y = tan(rad(epsilon / 2)).pow(2)
        val equation =
            4 *
                Math.toDegrees(
                    y * sin(rad(2 * l0)) - 2 * e * sin(rad(m)) +
                        4 * e * y * sin(rad(m)) * cos(rad(2 * l0)) -
                        .5 * y * y * sin(rad(4 * l0)) -
                        1.25 * e * e * sin(rad(2 * m))
                )
        val minutes = Math.floorMod(time, 86400000L) / 60000.0
        val solarMinutes = ((minutes + equation + 4 * longitude) % 1440 + 1440) % 1440
        val hourAngle = rad(solarMinutes / 4 - 180)
        val lat = rad(latitude)
        return Math.toDegrees(
            asin(
                (sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle))
                    .coerceIn(-1.0, 1.0)
            )
        )
    }
}
