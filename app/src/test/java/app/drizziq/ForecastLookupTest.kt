package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class ForecastLookupTest {
    private val point = Station("10865", "Munich", 48.138108, 11.574726, 515.0)

    private fun forecast(id: String = point.id, issue: Long = 100L) =
        Forecast(point.copy(id = id), issue, listOf(Hour(issue + 3600000, mapOf("TTT" to 280.0))))

    private class Source : ForecastSource {
        var saved: CachedForecast? = null
        var fetched: Forecast? = null
        var failure = false
        var reads = 0
        var requests = 0
        var lastStation: String? = null
        var complete: (() -> Unit)? = null

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
