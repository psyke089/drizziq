package app.drizziq

import java.util.Locale

/** One UTC hour per column; absent source hours remain explicitly unknown. */
data class HourlyColumn(val time: Long, val hour: Hour?)

fun hourlyColumns(forecast: Forecast, now: Long): List<HourlyColumn> {
    val upcoming = forecast.hours.filter { it.time >= now }.sortedBy { it.time }
    if (upcoming.isEmpty()) return emptyList()
    val byTime = upcoming.associateBy { it.time }
    val first = upcoming.first().time
    return (0 until 24)
        .map { first + it * 3600000L }
        .takeWhile { it <= upcoming.last().time }
        .map { HourlyColumn(it, byTime[it]) }
}

/** Device snapshots use the resolved forecast-point name; user labels keep their meaning. */
fun placeDisplayName(place: Place, station: Station?): String =
    if (place.label == "Current location")
        station?.name ?: String.format(Locale.US, "%.4f°, %.4f°", place.lat, place.lon)
    else place.label
