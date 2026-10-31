package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class ForecastPresentationTest {
    private val station = Station("10865", "MUENCHEN STADT", 48.1, 11.5, 515.0)

    private fun forecast(vararg hours: Hour) = Forecast(station, 0, hours.toList())

    @Test
    fun hourlyColumns_gapsAndMissingFields_remainUnknownWithin24Hours() {
        val start = 1_800_000_000_000L
        val columns =
            hourlyColumns(
                forecast(
                    Hour(start, mapOf("TTT" to 280.0)),
                    Hour(start + 7200000, emptyMap()),
                    Hour(start + 24 * 3600000, mapOf("TTT" to 290.0)),
                ),
                start,
            )
        assertEquals(24, columns.size)
        assertEquals(start, columns.first().time)
        assertNotNull(columns.first().hour)
        assertNull(columns[1].hour)
        assertNull(columns[2].hour!!.temperature)
        assertTrue(columns.none { it.hour?.temperature == 290.0 - 273.15 })
        assertEquals(23 * 3600000L, columns.last().time - columns.first().time)
    }

    @Test
    fun hourlyColumns_expiredOrEmptyForecast_doesNotInventHours() {
        assertTrue(hourlyColumns(forecast(), 100).isEmpty())
        assertTrue(hourlyColumns(forecast(Hour(99, emptyMap())), 100).isEmpty())
        assertEquals(1, hourlyColumns(forecast(Hour(100, emptyMap())), 100).size)
    }

    @Test
    fun hourlyColumns_dstRepeatedHour_retainsBothUtcInstants() {
        val start = java.time.Instant.parse("2026-10-25T00:00:00Z").toEpochMilli()
        val columns =
            hourlyColumns(
                forecast(Hour(start, emptyMap()), Hour(start + 3600000, emptyMap())),
                start,
            )
        assertEquals(2, columns.size)
        assertEquals("02:00 CEST", Times.format(columns[0].time, "HH:mm z", "Europe/Berlin"))
        assertEquals("02:00 CET", Times.format(columns[1].time, "HH:mm z", "Europe/Berlin"))
    }

    @Test
    fun placeDisplayName_deviceSnapshot_usesResolvedStationWithoutRenamingFavourite() {
        val device = Place("Current location", 48.1, 11.5)
        assertEquals("MUENCHEN STADT", placeDisplayName(device, station))
        assertNotEquals("Current location", placeDisplayName(device, null))
        assertEquals("My garden", placeDisplayName(device.copy(label = "My garden"), station))
        assertEquals("Current location", device.label)
    }
}
