package app.drizziq

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ForecastPresentationTest {
    private val station = Station("10865", "MUENCHEN STADT", 48.1, 11.5, 515.0)

    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    private fun forecast(vararg hours: Hour) = Forecast(station, 0, hours.toList())

    @Test
    fun pressure_sourcePascals_convertsToHectopascalsAndOmitsInvalidValues() {
        val pressure = Hour(0, mapOf("PPPP" to 102900.0)).pressure
        assertNotNull(pressure)
        assertEquals(1029.0, pressure!!, 0.000001)
        assertNull(Hour(0, emptyMap()).pressure)
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(Hour(0, mapOf("PPPP" to invalid)).pressure)
        }
        val fixture =
            javaClass.getResourceAsStream("/munich-20261022.kmz")!!.use { Mosmix.parseKmz(it) }
        assertTrue(fixture.hours.any { it.pressure != null })
    }

    @Test
    fun earlierForecast_fillsMissingHoursWithoutReplacingLatestOrMixingStations() {
        val midnight = at("2026-10-30T23:00:00Z")
        val past = Hour(midnight, mapOf("TTT" to 280.0))
        val current = Hour(midnight + 12 * 3600000, mapOf("TTT" to 285.0))
        val latest = Forecast(station, midnight + 10 * 3600000, listOf(current))
        val old =
            Forecast(
                station,
                midnight - 2 * 3600000,
                listOf(past, current.copy(values = mapOf("TTT" to 270.0))),
            )
        val combined = latest.withEarlierHours(old)
        val columns = hourlyColumns(combined, current.time, "Europe/Berlin")
        assertSame(past, columns.first().hour)
        assertSame(current, columns[12].hour)
        assertEquals(latest.issue, combined.issue)
        assertEquals(latest.hours, combined.hours)
        assertSame(latest, latest.withEarlierHours(old.copy(station = station.copy(id = "OTHER"))))
        assertSame(latest, latest.withEarlierHours(old.copy(issue = latest.issue + 1)))
    }

    @Test
    fun currentHourColumn_usesContainingHourIncludingRepeatedDstHourAndBounds() {
        val columns = hourlyColumns(forecast(), at("2026-10-25T12:00:00Z"), "Europe/Berlin")
        assertEquals(2, currentHourColumn(columns, at("2026-10-25T00:30:00Z")))
        assertEquals(3, currentHourColumn(columns, at("2026-10-25T01:30:00Z")))
        assertEquals(0, currentHourColumn(columns, columns.first().time - 1))
        assertEquals(37, currentHourColumn(columns, columns.last().time + 3600000))
        assertEquals(0, currentHourColumn(emptyList(), 0))
    }

    @Test
    fun hourlyColumns_throughTomorrowNoon_keepsPastValuesGapsAndSourceInstants() {
        val start = at("2026-10-30T23:00:00Z")
        val first = Hour(start, mapOf("TTT" to 280.0))
        val columns =
            hourlyColumns(
                forecast(
                    Hour(start - 3600000, emptyMap()),
                    first,
                    Hour(start + 7200000, emptyMap()),
                    Hour(start + 86400000, mapOf("TTT" to 290.0)),
                ),
                at("2026-10-31T11:30:00Z"),
                "Europe/Berlin",
            )
        assertEquals(37, columns.size)
        assertEquals(start, columns.first().time)
        assertSame(first, columns.first().hour)
        assertNull(columns[1].hour)
        assertNull(columns[2].hour!!.temperature)
        assertEquals(start + 36 * 3600000, columns.last().time)
        assertEquals(
            290.0 - 273.15,
            columns.single { it.time == start + 86400000 }.hour!!.temperature!!,
            0.0,
        )
    }

    @Test
    fun hourlyColumns_missingWholeDay_hasExplicitUnknownsWithoutInventedWeather() {
        val now = at("2026-10-31T11:30:00Z")
        for (product in
            listOf(forecast(), forecast(Hour(at("2026-10-30T00:00:00Z"), emptyMap())))) {
            val columns = hourlyColumns(product, now, "Europe/Berlin")
            assertEquals(37, columns.size)
            assertTrue(columns.all { it.hour == null })
            assertEquals("00:00", Times.format(columns.first().time, "HH:mm", "Europe/Berlin"))
        }
    }

    @Test
    fun hourlyColumns_dstDays_keepRepeatedHoursThroughTomorrowNoon() {
        val fall = hourlyColumns(forecast(), at("2026-10-25T12:00:00Z"), "Europe/Berlin")
        assertEquals(38, fall.size)
        assertEquals(
            listOf("02:00 CEST", "02:00 CET"),
            fall
                // Tomorrow also contains 02:00; only this local date has the repeated hour.
                .filter { Times.day(it.time, "Europe/Berlin") == "2026-10-25" }
                .map { Times.format(it.time, "HH:mm z", "Europe/Berlin") }
                .filter { it.startsWith("02:") },
        )
        val spring = hourlyColumns(forecast(), at("2026-03-29T12:00:00Z"), "Europe/Berlin")
        assertEquals(36, spring.size)
        assertFalse(
            spring
                .filter { Times.day(it.time, "Europe/Berlin") == "2026-03-29" }
                .any { Times.format(it.time, "HH:mm", "Europe/Berlin") == "02:00" }
        )
    }

    @Test
    fun hourlyColumns_fractionalZone_retainsRealForecastTimesAndMidnightBoundary() {
        val time = at("2026-10-30T20:00:00Z")
        val value = Hour(time, mapOf("TTT" to 280.0))
        val columns = hourlyColumns(forecast(value), at("2026-10-31T02:00:00Z"), "Asia/Kolkata")
        assertEquals("00:00", Times.format(columns.first().time, "HH:mm", "Asia/Kolkata"))
        assertSame(value, columns.single { it.time == time }.hour)
        assertEquals("01:30", Times.format(time, "HH:mm", "Asia/Kolkata"))
        assertEquals(
            "2026-11-01 12:00",
            Times.format(columns.last().time, "yyyy-MM-dd HH:mm", "Asia/Kolkata"),
        )
        assertNull(columns.last().hour)
    }

    @Test
    fun hourlyColumns_noonUsesCivilTimeAcrossEitherDaysDstAndDateRollover() {
        for ((now, end) in
            listOf(
                "2026-03-28T12:00:00Z" to "2026-03-29T10:00:00Z",
                "2026-03-29T12:00:00Z" to "2026-03-30T10:00:00Z",
                "2026-10-24T12:00:00Z" to "2026-10-25T11:00:00Z",
                "2026-10-25T12:00:00Z" to "2026-10-26T11:00:00Z",
                "2026-11-01T00:00:00Z" to "2026-11-02T11:00:00Z",
            )) {
            val noon = Hour(at(end), mapOf("TTT" to 280.0))
            val after = noon.copy(time = noon.time + 3600000)
            val columns = hourlyColumns(forecast(noon, after), at(now), "Europe/Berlin")
            assertEquals(at(end), columns.last().time)
            assertSame(noon, columns.last().hour)
            assertFalse(columns.any { it.time == after.time })
            val missing = hourlyColumns(forecast(after), at(now), "Europe/Berlin")
            assertEquals(at(end), missing.last().time)
            assertNull(missing.last().hour)
        }
    }

    @Test
    fun hourlyFields_precipitationProbabilityAndOneHourGust_preserveUnitsAndMissingness() {
        val hour = Hour(0, mapOf("R101" to 37.0, "FX1" to 10.0, "FF" to 2.0, "FX3" to 50.0))
        assertEquals(37.0, hour.precipitationProbability!!, 0.0)
        assertEquals(36.0, hour.gust!!, 0.000001)
        assertEquals(7.2, hour.wind!!, 0.000001)
        for (bad in listOf(-1.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(
            Hour(0, mapOf("R101" to bad)).precipitationProbability
        )
        for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(
            Hour(0, mapOf("FX1" to bad)).gust
        )
        assertNull(Hour(0, mapOf("FX3" to 10.0)).gust)
        assertNull(Hour(0, emptyMap()).precipitationProbability)
        assertEquals(0.0, Hour(0, mapOf("R101" to 0.0, "FX1" to 0.0)).gust!!, 0.0)
        val fixture =
            javaClass.getResourceAsStream("/munich-20261022.kmz")!!.use { Mosmix.parseKmz(it) }
        assertTrue(fixture.hours.any { it.precipitationProbability != null && it.gust != null })
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
