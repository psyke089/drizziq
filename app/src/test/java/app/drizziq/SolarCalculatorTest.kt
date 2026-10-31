package app.drizziq

import java.time.*
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class SolarCalculatorTest {
    @Test
    fun calculate_usnoPublicCityReferences_matchesConventionalRiseAndSetWithinTwoMinutes() {
        // Fixed independent USNO references retrieved 2026-10-31; never queried by tests/app.
        val cases =
            listOf(
                listOf("2026-10-31", "48.137", "11.575", "Europe/Berlin", "06:57", "16:57"),
                listOf("2026-06-21", "48.137", "11.575", "Europe/Berlin", "05:13", "21:18"),
                listOf("2026-03-08", "40.7128", "-74.006", "America/New_York", "07:19", "18:55"),
                listOf("2026-12-21", "-33.8688", "151.2093", "Australia/Sydney", "05:41", "20:05"),
            )
        for (c in cases) {
            val date = LocalDate.parse(c[0])
            val zone = ZoneId.of(c[3])
            val day =
                SolarCalculator.calculate(
                    Place("Reference", c[1].toDouble(), c[2].toDouble(), c[3]),
                    date,
                )
            for ((actual, time) in listOf(day.sunrise to c[4], day.sunset to c[5])) {
                val expected =
                    date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
                assertNotNull("Missing event for $date", actual)
                assertTrue(
                    "Event differs from independent reference for $date",
                    abs(actual!! - expected) <= 120000,
                )
                assertEquals(date, Instant.ofEpochMilli(actual).atZone(zone).toLocalDate())
            }
        }
    }

    @Test
    fun calculate_polarDayAndNight_doNotInventEvents() {
        val p = Place("Tromso", 69.6492, 18.9553, "Europe/Oslo")
        val summer = SolarCalculator.calculate(p, LocalDate.parse("2026-06-21"))
        assertEquals(SolarState.POLAR_DAY, summer.state)
        assertNull(summer.sunrise)
        assertNull(summer.sunset)
        val winter = SolarCalculator.calculate(p, LocalDate.parse("2026-12-21"))
        assertEquals(SolarState.POLAR_NIGHT, winter.state)
        assertNull(winter.sunrise)
        assertNull(winter.sunset)
    }

    @Test
    fun calculate_dstAntimeridianAndFractionalZones_keepEventsInRequestedCivilDay() {
        for ((place, date) in
            listOf(
                Place("Berlin", 52.52, 13.405, "Europe/Berlin") to "2026-03-29",
                Place("Berlin", 52.52, 13.405, "Europe/Berlin") to "2026-10-25",
                Place("Kiritimati", 1.8721, -157.4278, "Pacific/Kiritimati") to "2026-01-01",
                Place("Delhi", 28.6139, 77.209, "Asia/Kolkata") to "2026-10-31",
            )) {
            val day = SolarCalculator.calculate(place, LocalDate.parse(date))
            assertNotNull(day.sunrise)
            assertNotNull(day.sunset)
            for (time in listOf(day.sunrise!!, day.sunset!!)) assertEquals(
                date,
                Instant.ofEpochMilli(time).atZone(ZoneId.of(place.zone)).toLocalDate().toString(),
            )
            assertTrue(day.sunrise!! < day.sunset!!)
        }
    }

    @Test
    fun calculate_skippedCivilDay_returnsNoLocalDay() {
        val day =
            SolarCalculator.calculate(
                Place("Apia", -13.833, -171.75, "Pacific/Apia"),
                LocalDate.parse("2011-12-30"),
            )
        assertEquals(SolarState.NO_LOCAL_DAY, day.state)
        assertNull(day.sunrise)
        assertNull(day.sunset)
    }
}
