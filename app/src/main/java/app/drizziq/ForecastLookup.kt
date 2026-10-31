package app.drizziq

data class CachedForecast(val forecast: Forecast, val received: Long)

interface ForecastSource {
    fun cached(station: Station): CachedForecast?

    fun fetch(station: Station): Forecast
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
        val cached = runCatching {
            source.cached(hit.station)
        }
            .getOrNull()
            ?.takeIf { it.forecast.station.id == hit.station.id }
        if (
            cached != null &&
                (retained.data == null || cached.forecast.issue >= retained.data!!.issue)
        ) {
            retained =
                Reading(
                    cached.forecast,
                    if (online) Freshness.CACHED else Freshness.STALE,
                    cached.received,
                    "Saved forecast · checking DWD",
                    online,
                )
            emit(retained)
        }
        if (!isCurrent()) return
        if (!online) {
            emit(retained.failed("Offline · last matching forecast retained"))
            return
        }
        try {
            val result = source.fetch(hit.station)
            require(result.station.id == hit.station.id) { "Wrong forecast point" }
            val now = clock()
            val state = issueFreshness(result.issue, now, FORECAST_MAX_AGE)
            emit(
                Reading(
                    result,
                    state,
                    now,
                    if (state == Freshness.STALE) "DWD issue is old" else null,
                )
            )
        } catch (error: Exception) {
            emit(retained.failed("Refresh failed · connection or data error"))
        }
    }
}
