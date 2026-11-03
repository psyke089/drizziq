package app.drizziq

import java.util.Locale

/** Local midnight through tomorrow's civil noon, preserving real source validity instants. */
data class HourlyColumn(val time: Long, val hour: Hour?)

fun currentHourColumn(columns: List<HourlyColumn>, now: Long): Int =
    columns.indexOfLast { it.time <= now }.coerceAtLeast(0)

fun hourlyColumns(forecast: Forecast, now: Long, zone: String): List<HourlyColumn> {
    val tz = java.time.ZoneId.of(zone)
    val date = java.time.Instant.ofEpochMilli(now).atZone(tz).toLocalDate()
    val start = date.atStartOfDay(tz).toInstant().toEpochMilli()
    val end = date.plusDays(1).atTime(12, 0).atZone(tz).toInstant().toEpochMilli()
    val byTime = (forecast.earlier + forecast.hours).associateBy { it.time }
    val times = sortedSetOf(start, end)
    var time = Math.floorDiv(start, 3600000L) * 3600000L + 3600000L
    while (time <= end) {
        times += time
        time += 3600000L
    }
    // Preserve real source validity instants in fractional-offset zones; never round/interpolate.
    (forecast.earlier + forecast.hours)
        .filter { it.time in start..end }
        .forEach { times += it.time }
    return times.map { HourlyColumn(it, byTime[it]) }
}

/** Device snapshots use the resolved forecast-point name; user labels keep their meaning. */
fun placeDisplayName(place: Place, station: Station?): String =
    if (place.label == "Current location")
        station?.name ?: String.format(Locale.US, "%.4f°, %.4f°", place.lat, place.lon)
    else place.label
