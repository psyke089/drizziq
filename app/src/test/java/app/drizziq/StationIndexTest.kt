package app.drizziq

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class StationIndexTest {
    private val origin = Place("Origin", 0.0, 0.0)

    private fun station(id: String, lat: Double = 0.0, lon: Double = 0.0) =
        Station(id, "Point $id", lat, lon, null)

    @Test
    fun nearest_emptyIndex_returnsNull() {
        assertNull(StationIndex(emptyList()).nearest(origin))
        assertNull(StationIndex(emptyList()).nearestInCoverage(origin))
    }

    @Test
    fun nearest_exactDistanceTies_useOpaqueCaseSensitiveIdsRegardlessOfRowOrder() {
        val points =
            listOf(
                station("P0489"),
                station("X104"),
                station("01001"),
                station("P659"),
                station("a"),
                station("A"),
            )
        for (rows in listOf(points, points.reversed(), points.shuffled(java.util.Random(4)))) {
            val hit = StationIndex(rows).nearest(origin)!!
            assertEquals("01001", hit.station.id)
            assertEquals(0.0, hit.distanceKm, 0.0)
        }
        assertEquals(
            "A",
            StationIndex(listOf(station("a"), station("A"))).nearest(origin)!!.station.id,
        )
    }

    @Test
    fun nearest_nearlyEqualDistances_neverLetsLexicalOrderChooseFartherPoint() {
        val near = station("Z", 0.0, 0.1)
        val far = station("A", 0.0, 0.10000000001)
        assertEquals("Z", StationIndex(listOf(far, near)).nearest(origin)!!.station.id)
    }

    @Test
    fun nearestInCoverage_exactBoundaryIsInclusive_andInvalidRadiiThrow() {
        val index = StationIndex(listOf(station("T", 0.0, 0.4)))
        val km = index.nearest(origin)!!.distanceKm
        assertNotNull(index.nearestInCoverage(origin, km))
        assertNull(index.nearestInCoverage(origin, Math.nextDown(km)))
        assertNotNull(index.nearestInCoverage(origin, Math.nextUp(km)))
        assertNotNull(StationIndex(listOf(station("T"))).nearestInCoverage(origin, 0.0))
        for (radius in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                index.nearestInCoverage(origin, radius)
            }
        }
    }

    @Test
    fun constructor_invalidCatalogueRowsAndDuplicateIds_fail() {
        for (bad in
            listOf(
                station(""),
                station("bad-id"),
                station("T", 91.0),
                station("T", lon = Double.NaN),
                Station("T", " ", 0.0, 0.0, null),
            )) {
            assertThrows(IllegalArgumentException::class.java) { StationIndex(listOf(bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            StationIndex(listOf(station("T"), station("T", 1.0)))
        }
    }

    @Test
    fun nearest_polesAndAntimeridian_useClampedHaversine() {
        val seam = StationIndex(listOf(station("E", 0.0, 179.9), station("W", 0.0, -170.0)))
        assertEquals("E", seam.nearest(Place("Seam", 0.0, -179.9))!!.station.id)
        assertTrue(seam.nearest(Place("Seam", 0.0, -179.9))!!.distanceKm < 23)
        assertTrue(station("N", 90.0, 0.0).distance(Place("Pole", 90.0, 180.0)) < 0.000001)
        assertEquals(
            504.0,
            Station("B", "Berlin", 52.52, 13.405, null).distance(Place("Munich", 48.137, 11.575)),
            2.0,
        )
    }

    @Test
    fun bundledCatalogue_pinnedRevision_resolvesReferenceCoordinates() {
        val file =
            listOf(File("src/main/assets/stations.tsv"), File("app/src/main/assets/stations.tsv"))
                .first { it.exists() }
        val bytes = file.readBytes()
        assertEquals(
            "713df55d858359b021932d84b8dd9c2832b7d73b592e301b2f410c1d7a5b7f3c",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            },
        )
        val index = StationIndex.fromTsv(bytes.toString(Charsets.UTF_8))
        for ((p, id, km) in
            listOf(
                Triple(Place("Munich", 48.137, 11.575), "10865", 4.52),
                Triple(Place("Berlin", 52.52, 13.405), "10389", 0.87),
                Triple(Place("Hamburg", 53.551, 9.993), "P0489", 0.65),
            )) {
            val hit = index.nearestInCoverage(p)!!
            assertEquals(id, hit.station.id)
            assertEquals(km, hit.distanceKm, 0.02)
        }
        assertNull(index.nearestInCoverage(origin))
    }
}
