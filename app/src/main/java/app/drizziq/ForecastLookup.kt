package app.drizziq

data class CachedForecast(val forecast: Forecast, val received: Long)

interface ForecastSource {
    fun cached(station: Station): CachedForecast?

    fun fetch(station: Station): Forecast

    fun earlier(station: Station, dayStart: Long, online: Boolean): Forecast? = null
}

/** Worker-side resolution and cache policy, independent of Android and the UI owner. */
class ForecastLookup(
    private val catalogue: () -> StationIndex,
    private val source: ForecastSource,
    private val clock: () -> Long,
) {
    fun load(
        place: Place,
        previous: Reading<Forecast> = Reading(),
        online: Boolean = true,
        isCurrent: () -> Boolean = { true },
        resolved: (StationHit?) -> Unit = {},
        publish: (Reading<Forecast>) -> Unit,
    ) {
        fun emit(reading: Reading<Forecast>) {
            if (isCurrent()) publish(reading)
        }
        if (!isCurrent()) return
        val zone = Zones.canonical(place.zone)
        if (zone == null) {
            resolved(null)
            emit(
                Reading(
                    state = Freshness.ERROR,
                    message = "Invalid time zone · choose another place",
                )
            )
            return
        }
        val index =
            try {
                catalogue()
            } catch (error: Exception) {
                if (isCurrent()) resolved(null)
                emit(
                    Reading(
                        state = Freshness.ERROR,
                        message = "Forecast station catalogue unavailable",
                    )
                )
                return
            }
        val hit = index.nearestInCoverage(place)
        if (!isCurrent()) return
        resolved(hit)
        if (hit == null) {
            emit(Reading(state = Freshness.ERROR, message = "No DWD forecast point within 50 km"))
            return
        }
        var retained = previous.takeIf { it.data?.station?.id == hit.station.id } ?: Reading()
        val dayStart =
            java.time.Instant.ofEpochMilli(clock())
                .atZone(java.time.ZoneId.of(zone))
                .toLocalDate()
                .atStartOfDay(java.time.ZoneId.of(zone))
                .toInstant()
                .toEpochMilli()
        fun withHistory(forecast: Forecast, fetch: Boolean): Forecast {
            var result = retained.data?.let { forecast.withEarlierHours(it) } ?: forecast
            if ((result.earlier + result.hours).any { it.time <= dayStart }) return result
            val earlier = runCatching { source.earlier(hit.station, dayStart, fetch) }.getOrNull()
            if (earlier != null) result = result.withEarlierHours(earlier)
            return result
        }
        val cached = runCatching {
            if (retained.data == null) source.cached(hit.station) else null
        }
            .getOrNull()
            ?.takeIf { it.forecast.station.id == hit.station.id }
        if (
            cached != null &&
                (retained.data == null || cached.forecast.issue > retained.data!!.issue)
        ) {
            retained =
                Reading(
                    withHistory(cached.forecast, false),
                    if (online) Freshness.CACHED else Freshness.STALE,
                    cached.received,
                    "Saved forecast · checking DWD",
                    online,
                )
            emit(retained)
        }
        if (!isCurrent()) return
        if (!online) {
            emit(retained.offline("forecast"))
            return
        }
        try {
            val result = source.fetch(hit.station)
            require(result.station.id == hit.station.id) { "Wrong forecast point" }
            val now = clock()
            val state = issueFreshness(result.issue, now, FORECAST_MAX_AGE)
            val reading =
                Reading(
                    withHistory(result, false),
                    state,
                    now,
                    if (state == Freshness.STALE) "DWD issue is old" else null,
                    refreshing = true,
                )
            emit(reading)
            if (!isCurrent()) return
            val enriched = withHistory(reading.data!!, true)
            emit(reading.copy(data = enriched, refreshing = false))
        } catch (error: Exception) {
            emit(retained.failed("Refresh failed · connection or data error"))
        }
    }
}
