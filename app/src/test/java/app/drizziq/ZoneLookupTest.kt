package app.drizziq

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ZoneLookupTest {
    private val lookup =
        ZoneLookup(
            File("src/main/assets/timezones.tree").readText(),
            File("src/main/assets/timezones.txt").readLines(),
        )

    @Test
    fun bundledTree_resolvesKnownCitiesIncludingFractionalOffsetZones() {
        for ((place, zone) in
            listOf(
                Place.MUNICH to "Europe/Berlin",
                Place("New York", 40.7, -74.0) to "America/New_York",
                Place("Kathmandu", 27.7, 85.3) to "Asia/Kathmandu",
                Place("Sydney", -33.87, 151.21) to "Australia/Sydney",
            )) assertEquals(zone, lookup.at(place.lat, place.lon))
    }

    @Test
    fun polesAndDateline_stayInsideTreeAndRejectInvalidInput() {
        assertEquals("Etc/GMT", lookup.at(90.0, 180.0))
        for (lat in listOf(-90.0, 0.0, 89.0)) for (lon in listOf(-180.0, 180.0)) assertTrue(
            Zones.valid(lookup.at(lat, lon))
        )
        for ((lat, lon) in listOf(91.0 to 0.0, 0.0 to 181.0, Double.NaN to 0.0)) assertThrows(
            IllegalArgumentException::class.java
        ) {
            lookup.at(lat, lon)
        }
    }
}
