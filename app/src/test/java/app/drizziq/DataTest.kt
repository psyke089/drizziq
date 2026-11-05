package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class DataTest {
    @Test
    fun dailyTotals_mergeEarlierHoursWithLatestWinsAndKeepGapChecks() {
        val date = java.time.LocalDate.parse("2026-10-25")
        val zone = "Europe/Berlin"
        val hours =
            hoursFor(date, zone).map {
                it.copy(values = mapOf("RR1c" to 1.0, "SunD1" to 60.0))
            }
        val forecast =
            Forecast(
                Station("t", "t", 0.0, 0.0, null),
                0L,
                hours.drop(10),
                hours.take(11).reversed().map {
                    it.copy(values = mapOf("RR1c" to 2.0, "SunD1" to 120.0))
                },
            )
        val day = forecast.days(hours.first().time, zone).single()
        assertTrue(day.complete)
        assertEquals(35.0, day.rain!!, 0.0)
        assertEquals(35.0 / 60, day.sun!!, 0.00001)
        val gap = forecast.copy(earlier = forecast.earlier.filter { it.time != hours[5].time })
        assertNull(gap.days(hours.first().time, zone).single().rain)
        val missing = forecast.copy(hours = forecast.hours.map { it.copy(values = emptyMap()) })
        assertNull(missing.days(hours.first().time, zone).single().sun)
    }

    @Test
    fun currentSummary_usesNearestValidityWithinOneHourIncludingFutureBoundary() {
        val first = Hour(3_600_000L, emptyMap())
        val next = Hour(7_200_000L, emptyMap())
        val forecast = Forecast(Station("t", "t", 0.0, 0.0, null), 0L, listOf(first, next))
        assertSame(first, forecast.current(0))
        assertNull(forecast.current(-1))
        assertSame(first, forecast.current(5_400_000))
        assertSame(next, forecast.current(5_400_001))
        assertNull(forecast.current(10_800_001))
    }

    @Test
    fun coordinatesRejectNonFiniteAndOutOfRange() {
        assertTrue(validCoordinates(-90.0, 180.0))
        assertFalse(validCoordinates(Double.NaN, 0.0))
        assertFalse(validCoordinates(48.0, Double.POSITIVE_INFINITY))
        assertFalse(validCoordinates(91.0, 11.0))
        assertFalse(validCoordinates(48.0, -181.0))
    }

    @Test
    fun liveDwdKmzRetainsTimesAndMissingHumidity() {
        val f = Mosmix.parseKmz(javaClass.getResourceAsStream("/munich-20261022.kmz")!!)
        assertEquals("10865", f.station.id)
        assertEquals(Times.parse("2026-10-22T15:00:00.000Z"), f.issue)
        assertTrue(f.hours.size >= 168)
        assertTrue(f.hours.take(24).all { it.temperature != null && it.wind != null })
        assertTrue(f.hours.all { it.humidity == null })
        assertTrue(f.station.distance(Place.MUNICH) < 6)
        assertEquals(7, f.days(f.hours.first().time, "Europe/Berlin").size)
    }

    @Test
    fun missingValuesAreAbsentAndNoCurrentValueIsExtrapolated() {
        val xml =
            """<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:d="urn:test"><Document><d:IssueTime>2026-10-22T15:00:00Z</d:IssueTime><d:TimeStep>2026-10-22T16:00:00Z</d:TimeStep><Placemark><name>10865</name><description>Munich</description><Point><coordinates>11.533,48.167,515</coordinates></Point><d:Forecast d:elementName="TTT"><d:value>-</d:value></d:Forecast><d:Forecast d:elementName="RR1c"><d:value>0.00</d:value></d:Forecast></Placemark></Document></kml>"""
        val f = Mosmix.parseXml(xml.byteInputStream())
        assertNull(f.hours.single().temperature)
        assertEquals(0.0, f.hours.single().get("RR1c")!!, 0.0)
        assertNull(f.current(Times.parse("2026-10-23T16:00:00Z")))
        assertNull(f.days(f.issue, "UTC").single().rain)
    }

    @Test
    fun dailyTotalsRespectThe25HourAutumnDay() {
        val start = Times.parse("2026-10-24T22:00:00Z")
        val hours =
            (1..25).map {
                Hour(start + it * 3600000L, mapOf("RR1c" to 1.0, "SunD1" to 60.0, "TTT" to 280.0))
            }
        val f = Forecast(Station("t", "t", 0.0, 0.0, null), start, hours)
        val day = f.days(start, "Europe/Berlin").single()
        assertTrue(day.complete)
        assertEquals(25.0, day.rain!!, 0.0001)
        val incomplete =
            f.copy(hours = hours.filterIndexed { i, _ -> i != 5 })
                .days(start, "Europe/Berlin")
                .single()
        assertFalse(incomplete.complete)
        assertNull(incomplete.rain)
    }

    @Test
    fun skippedAndRepeatedLocalMidnights_stillFormCompleteCorrectLengthDays() {
        val santiago = day("America/Santiago", "2026-09-06")
        assertTrue(santiago.complete)
        assertEquals(23, santiago.hours.size)
        assertEquals(23.0, santiago.rain!!, 0.0)

        val havana = day("America/Havana", "2026-11-01")
        assertTrue(havana.complete)
        assertEquals(25, havana.hours.size)
        assertEquals(25.0, havana.rain!!, 0.0)

        val berlinSpring = day("Europe/Berlin", "2026-03-29")
        assertTrue(berlinSpring.complete)
        assertEquals(23, berlinSpring.hours.size)

        val berlinAutumn = day("Europe/Berlin", "2026-10-25")
        assertTrue(berlinAutumn.complete)
        assertEquals(25, berlinAutumn.hours.size)
    }

    @Test
    fun incompleteSourceIntervals_neverProduceDailyTotals() {
        val date = java.time.LocalDate.parse("2026-10-25")
        val zone = "Europe/Berlin"
        val hours = hoursFor(date, zone)
        val forecast = Forecast(Station("t", "t", 0.0, 0.0, null), 0L, hours)
        val now = hours.first().time
        for (incomplete in
            listOf(
                hours.drop(1),
                hours.dropLast(1),
                hours.filterIndexed { i, _ -> i != 5 },
            )) {
            val day = forecast.copy(hours = incomplete).days(now, zone).single()
            assertFalse(day.complete)
            assertNull(day.rain)
        }
    }

    @Test
    fun times_formatRejectsIncompatibleLegacyZonesInsteadOfUsingGmt() {
        assertThrows(java.time.DateTimeException::class.java) {
            Times.format(0, "HH:mm", "PST")
        }
        assertThrows(java.time.DateTimeException::class.java) { Times.day(0, "PST") }
    }

    private fun hoursFor(
        date: java.time.LocalDate,
        zone: String,
        value: Double = 1.0,
    ): List<Hour> {
        val tz = java.time.ZoneId.of(zone)
        val start = date.atStartOfDay(tz).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(tz).toInstant().toEpochMilli()
        return generateSequence(start + 3600000L) { it + 3600000L }
            .takeWhile { it <= end }
            .map { Hour(it, mapOf("RR1c" to value)) }
            .toList()
    }

    private fun day(zone: String, date: String): Day {
        val hours = hoursFor(java.time.LocalDate.parse(date), zone)
        return Forecast(Station("t", "t", 0.0, 0.0, null), 0L, hours)
            .days(hours.first().time, zone)
            .single()
    }

    @Test
    fun currentOdimUint32DataAndProjectionDecode() {
        val radar =
            Odim.decode(javaClass.getResourceAsStream("/rv-observed-20261022.h5")!!.readBytes())
        assertEquals(1100, radar.grid.width)
        assertEquals(1200, radar.grid.height)
        assertEquals(Times.parse("2026-10-22T20:00:00Z"), radar.time)
        assertEquals(-500.0, radar.grid.left, .02)
        assertEquals(500.0, radar.grid.top, .02)
        assertTrue(radar.grid.contains(Place.MUNICH))
        assertFalse(radar.grid.contains(Place("New York", 40.7, -74.0)))
        assertTrue(radar.colors.any { it == 0 })
        assertTrue(radar.colors.any { it == RadarPalette.colors[1] })
        val (x, y) = RadarProjection.project(48.138108, 11.574726)
        val (lat, lon) = RadarProjection.unproject(x, y)
        assertEquals(48.138108, lat, .000001)
        assertEquals(11.574726, lon, .000001)
    }

    @Test
    fun forecastFrameUsesActualValidityTime() {
        val radar =
            Odim.decode(javaClass.getResourceAsStream("/rv-forecast-20261022.h5")!!.readBytes())
        assertEquals(Times.parse("2026-10-22T22:15:00Z"), radar.time)
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedTarIsRejected() {
        val header = ByteArray(512)
        "test".toByteArray().copyInto(header)
        "00000000010".toByteArray().copyInto(header, 124)
        Tar.read((header + byteArrayOf(1, 2)).inputStream()) {
            fail("Truncated entry was accepted")
        }
    }
}
