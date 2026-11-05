package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class ForecastLookupTest {
    private val point = Station("10865", "Munich", 48.138108, 11.574726, 515.0)

    private fun forecast(id: String = point.id, issue: Long = 100L) =
        Forecast(point.copy(id = id), issue, listOf(Hour(issue + 3600000, mapOf("TTT" to 280.0))))

    @Test
    fun sameStationLiveReading_skipsDiskAndNeverRegressesBeforeNetworkCompletes() {
        val retained = Reading(forecast(), Freshness.LIVE, 99L)
        val source =
            Source().apply {
                saved = CachedForecast(forecast(), 77)
                fetched = forecast()
            }
        val readings = mutableListOf<Reading<Forecast>>()
        ForecastLookup({ StationIndex(listOf(point)) }, source, { 1000 }).load(
            Place.MUNICH,
            retained,
        ) {
            readings += it
        }
        assertEquals(0, source.reads)
        assertEquals(1, source.requests)
        assertTrue(readings.all { it.state == Freshness.LIVE })
        assertTrue(readings.all { it.fetched!! >= 99L })
    }

    @Test
    fun offlineWithoutCache_reportsMissingForecastAndSkipsTransport() {
        val source = Source()
        val readings = mutableListOf<Reading<Forecast>>()
        ForecastLookup({ StationIndex(listOf(point)) }, source, { 1000 }).load(
            Place.MUNICH,
            online = false,
        ) {
            readings += it
        }
        assertNull(readings.single().data)
        assertEquals("Offline · no saved forecast", readings.single().message)
        assertEquals(0, source.requests)
    }

    @Test
    fun resolution_failedWeatherFetch_stillPublishesMatchingStationBeforeNetwork() {
        var resolved: StationHit? = null
        val source =
            Source().apply {
                failure = true
                complete = { assertEquals("10865", resolved?.station?.id) }
            }
        ForecastLookup({ StationIndex(listOf(point)) }, source, { 1000 })
            .load(
                Place.MUNICH,
                resolved = { resolved = it },
                publish = {},
            )
        assertEquals("10865", resolved?.station?.id)
        var current = true
        resolved = null
        ForecastLookup(
                {
                    current = false
                    StationIndex(listOf(point))
                },
                source,
                { 1000 },
            )
            .load(
                Place.MUNICH,
                isCurrent = { current },
                resolved = { resolved = it },
                publish = {},
            )
        assertNull(resolved)
    }

    private class Source : ForecastSource {
        var saved: CachedForecast? = null
        var fetched: Forecast? = null
        var failure = false
        var reads = 0
        var requests = 0
        var lastStation: String? = null
        var complete: (() -> Unit)? = null
        var historical: Forecast? = null
        var historyFailure = false
        var historyRequested = false

        override fun earlier(station: Station, dayStart: Long, online: Boolean): Forecast? {
            if (!online) return null
            historyRequested = true
            if (historyFailure) throw java.io.IOException()
            return historical
        }

        override fun cached(station: Station): CachedForecast? {
            reads++
            lastStation = station.id
            return saved
        }

        override fun fetch(station: Station): Forecast {
            requests++
            lastStation = station.id
            complete?.invoke()
            if (failure) throw java.io.IOException()
            return fetched!!
        }
    }

    @Test
    fun invalidLegacyZone_publishesTerminalErrorInsteadOfWedgingRefresh() {
        val source = Source()
        val readings = mutableListOf<Reading<Forecast>>()
        ForecastLookup({ StationIndex(listOf(point)) }, source, { 1000 }).load(
            Place.MUNICH.copy(zone = "PST")
        ) {
            readings += it
        }
        assertEquals(1, readings.size)
        assertEquals(Freshness.ERROR, readings.single().state)
        assertFalse(readings.single().refreshing)
        assertNull(readings.single().data)
        assertEquals(0, source.reads)
        assertEquals(0, source.requests)
    }

    @Test
    fun archivedForecast_isAddedAfterLatestPublication_andFailureKeepsLatestLive() {
        val now = java.time.Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()
        val midnight = java.time.Instant.parse("2026-10-30T23:00:00Z").toEpochMilli()
        val latest = Forecast(point, now - 3600000, listOf(Hour(now, mapOf("TTT" to 280.0))))
        val past = Hour(midnight, mapOf("TTT" to 275.0))
        for (failure in listOf(false, true)) {
            val source =
                Source().apply {
                    fetched = latest
                    historical = Forecast(point, midnight - 7200000, listOf(past))
                    historyFailure = failure
                }
            val readings = mutableListOf<Reading<Forecast>>()
            ForecastLookup({ StationIndex(listOf(point)) }, source, { now }).load(Place.MUNICH) {
                if (readings.isEmpty()) assertFalse(source.historyRequested)
                readings += it
            }
            assertTrue(source.historyRequested)
            assertEquals(Freshness.LIVE, readings.last().state)
            assertEquals(latest.issue, readings.last().data!!.issue)
            assertEquals(
                if (failure) emptyList<Hour>() else listOf(past),
                readings.last().data!!.earlier,
            )
        }
    }

    @Test
    fun outsideCoverageAndCatalogueFailure_clearPreviousDataAndSkipProductIO() {
        for (catalogue in
            listOf<() -> StationIndex>(
                { StationIndex(listOf(point.copy(lat = 0.0, lon = 0.0))) },
                { throw IllegalArgumentException() },
            )) {
            val source = Source()
            val readings = mutableListOf<Reading<Forecast>>()
            ForecastLookup(catalogue, source, { 1000 }).load(
                Place.MUNICH,
                Reading(forecast(), Freshness.LIVE, 999),
            ) {
                readings += it
            }
            assertNull(readings.last().data)
            assertEquals(Freshness.ERROR, readings.last().state)
            assertEquals(0, source.reads)
            assertEquals(0, source.requests)
        }
    }

    @Test
    fun offlineMatchingCache_usesItsOriginalReceipt_andDoesNotFetch() {
        val source = Source().apply { saved = CachedForecast(forecast(), 77) }
        val readings = mutableListOf<Reading<Forecast>>()
        ForecastLookup({ StationIndex(listOf(point)) }, source, { 1000 }).load(
            Place.MUNICH,
            online = false,
        ) {
            readings += it
        }
        assertEquals(Freshness.STALE, readings.last().state)
        assertEquals(77L, readings.last().fetched)
        assertEquals("10865", readings.last().data!!.station.id)
        assertEquals(0, source.requests)
    }

    @Test
    fun wrongStationCacheAndFetch_neverBecomeTheSelectedPlacesWeather() {
        val source =
            Source().apply {
                saved = CachedForecast(forecast("OTHER"), 77)
                fetched = forecast("OTHER")
            }
        val readings = mutableListOf<Reading<Forecast>>()
        ForecastLookup({ StationIndex(listOf(point)) }, source, { 1000 }).load(Place.MUNICH) {
            readings += it
        }
        assertEquals(Freshness.ERROR, readings.last().state)
        assertNull(readings.last().data)
    }

    @Test
    fun failedRefresh_retainsOnlyMatchingDataWithOriginalTimestamp() {
        val source =
            Source().apply {
                saved = CachedForecast(forecast(), 77)
                failure = true
            }
        val readings = mutableListOf<Reading<Forecast>>()
        ForecastLookup({ StationIndex(listOf(point)) }, source, { 1000 }).load(Place.MUNICH) {
            readings += it
        }
        assertEquals(Freshness.STALE, readings.last().state)
        assertEquals(100L, readings.last().data!!.issue)
        assertEquals(77L, readings.last().fetched)
    }

    @Test
    fun obsoleteNetworkCompletion_cannotPublishOverNewerChoice() {
        var current = true
        val source =
            Source().apply {
                fetched = forecast()
                complete = { current = false }
            }
        val readings = mutableListOf<Reading<Forecast>>()
        ForecastLookup({ StationIndex(listOf(point)) }, source, { 1000 }).load(
            Place.MUNICH,
            isCurrent = { current },
        ) {
            readings += it
        }
        assertTrue(readings.isEmpty())
    }

    @Test
    fun catalogueRevision_reResolvesSavedCoordinates_andSameStationCacheIsReusable() {
        val source =
            Source().apply {
                saved = CachedForecast(forecast(), 77)
                fetched = forecast()
            }
        val place =
            Place.MUNICH.copy(
                label = "Renamed",
                zone = "Pacific/Auckland",
                geocodeId = "open-meteo:1",
            )
        val readings = mutableListOf<Reading<Forecast>>()
        ForecastLookup({ StationIndex(listOf(point)) }, source, { 1000 }).load(
            place,
            online = false,
        ) {
            readings += it
        }
        assertNotNull(readings.last().data)
        source.saved = null
        source.fetched = forecast("NEW")
        ForecastLookup({ StationIndex(listOf(point.copy(id = "NEW"))) }, source, { 1000 }).load(
            place,
            readings.last(),
        ) {
            readings += it
        }
        assertEquals("NEW", source.lastStation)
        assertEquals("NEW", readings.last().data!!.station.id)
        assertEquals("Pacific/Auckland", place.zone)
    }
}
