package app.drizziq

import java.net.URL
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MapProductTest {
    private fun at(s: String) = Instant.parse(s).toEpochMilli()

    private val now = at("2026-11-03T10:25:00Z")
    private val area = MapArea.viewport(.53, .34, 7.0, 1080, 1200)

    private fun caps(product: MapProduct, dimensions: String): MapAvailability =
        MapWms.capabilities(
            """
        <WMS_Capabilities xmlns="http://www.opengis.net/wms"><Capability><Layer><Layer>
        <Name>${product.layer}</Name><Style><Name>not-the-layer</Name></Style>$dimensions
        </Layer></Layer></Capability></WMS_Capabilities>
        """
                .trimIndent()
                .toByteArray(),
            product,
            now,
        )

    private val satellite
        get() =
            caps(
                MapProduct.SATELLITE,
                """<Dimension name="time">2024-09-23T00:00:00Z/2026-11-03T10:00:00Z/PT10M</Dimension>""",
            )

    private val temperature
        get() =
            caps(
                MapProduct.TEMPERATURE,
                """
        <Dimension name="time">2026-11-02T00:00:00Z/2026-11-05T00:00:00Z/PT1H</Dimension>
        <Dimension name="REFERENCE_TIME">2026-11-02T06:00:00Z,2026-11-03T00:00:00Z,2026-11-03T06:00:00Z,2026-11-03T12:00:00Z</Dimension>
        """,
            )

    @Test
    fun satelliteSelection_honoursItsOwnHistoricalTimeline() {
        val selection =
            MapWms.select(MapProduct.SATELLITE, at("2026-11-03T06:00:00Z"), now, satellite)!!
        assertEquals(at("2026-11-03T06:00:00Z"), selection.time)
        assertNull(selection.run)
        assertTrue(
            MapWms.imageUrl(selection, area).startsWith("https://view.eumetsat.int/geoserver/wms?")
        )
    }

    @Test
    fun temperatureSelection_includesCoveringModelRun() {
        val selection = MapWms.select(MapProduct.TEMPERATURE, now, now, temperature)!!
        assertEquals(at("2026-11-03T10:00:00Z"), selection.time)
        assertEquals(at("2026-11-03T06:00:00Z"), selection.run)
        val url = MapWms.imageUrl(selection, area)
        assertTrue(url.contains("DIM_REFERENCE_TIME=2026-11-03T06%3A00%3A00Z"))
        assertFalse(url.contains("ELEVATION"))
        val older =
            MapWms.select(MapProduct.TEMPERATURE, at("2026-11-02T12:00:00Z"), now, temperature)!!
        assertEquals(at("2026-11-02T06:00:00Z"), older.run)
    }

    @Test
    fun missingRunsAndTimes_remainUnavailable() {
        assertNull(MapWms.select(MapProduct.TEMPERATURE, now, now, MapAvailability(listOf(now))))
        assertNull(MapWms.select(MapProduct.SATELLITE, now - 2 * 86400_000, now, satellite))
        assertThrows(IllegalArgumentException::class.java) {
            caps(
                MapProduct.SATELLITE,
                "<Dimension name=\"time\">2026-11-03T00:00:00Z/2026-11-03T01:00:00Z/PT0S</Dimension>",
            )
        }
    }

    @Test
    fun yearsOfSatelliteMetadata_expandOnlyRecentWindowAndNeverForecast() {
        assertTrue(satellite.times.size in 1..300)
        val times = MapWms.times(MapProduct.SATELLITE, satellite, now)
        assertEquals(37, times.size)
        assertTrue(times.all { it <= now })
        assertTrue(times.zipWithNext().all { (a, b) -> b - a == 600_000L })
        val withFuture = satellite.copy(times = satellite.times + (now + 600_000L))
        assertTrue(MapWms.times(MapProduct.SATELLITE, withFuture, now).all { it <= now })
        assertEquals(
            satellite.times.last(),
            MapWms.select(MapProduct.SATELLITE, now + 3600_000L, now, withFuture)!!.time,
        )
    }

    @Test
    fun temperatureTimeline_isHourlyNowThroughTomorrow() {
        val times = MapWms.times(MapProduct.TEMPERATURE, temperature, now)
        assertEquals(at("2026-11-03T10:00:00Z"), times.first())
        assertEquals(at("2026-11-04T10:00:00Z"), times.last())
        assertEquals(25, times.size)
    }

    @Test
    fun viewport_hasOverscanAndBoundedDecodeSize() {
        val image = MapArea.viewport(.53, .34, 7.0, 1080, 1200, 1.15)
        assertTrue(image.covers(area))
        assertTrue(image.width <= 1280 && image.height <= 1280)
        assertFalse(area.covers(image))
        val edge = MapArea.viewport(0.0, 0.0, 2.0, 1080, 2400, 1.15)
        assertEquals(0.0, edge.left, 0.0)
        assertEquals(0.0, edge.top, 0.0)
        assertTrue(edge.right <= 1 && edge.bottom <= 1)
    }

    @Test
    fun imageKeys_isolateViewportTimeProductAndModelRun() {
        val s = MapSelection(MapProduct.TEMPERATURE, now, now - 3600_000L)
        val key = MapWms.key(s, area)
        assertNotEquals(key, MapWms.key(s.copy(time = now + 3600_000L), area))
        assertNotEquals(key, MapWms.key(s.copy(run = now - 7200_000L), area))
        assertNotEquals(key, MapWms.key(s.copy(product = MapProduct.SATELLITE), area))
        assertNotEquals(key, MapWms.key(s, area.copy(width = area.width - 1)))
        assertFalse(key.contains("."))
    }

    @Test
    fun productSwitch_keepsDisplayedProductUntilReadyAndRejectsObsoleteCompletions() {
        val state = MapPresentation()
        val satellite = state.request(MapProduct.SATELLITE)
        assertEquals(MapProduct.RADAR, state.displayed)
        val temperature = state.request(MapProduct.TEMPERATURE)
        assertFalse(state.commit(MapProduct.SATELLITE, satellite))
        assertFalse(state.commit(MapProduct.SATELLITE, temperature))
        assertEquals(MapProduct.RADAR, state.displayed)
        assertTrue(state.commit(MapProduct.TEMPERATURE, temperature))
        val radar = state.request(MapProduct.RADAR)
        assertEquals(MapProduct.TEMPERATURE, state.displayed)
        assertTrue(state.commit(MapProduct.RADAR, radar))
        assertFalse(state.commit(MapProduct.TEMPERATURE, temperature))
    }

    @Test
    fun endpoints_allowOnlyApprovedPublicWmsPaths() {
        assertTrue(Http.allowed(URL(MapProduct.SATELLITE.endpoint)))
        assertTrue(Http.allowed(URL(MapProduct.TEMPERATURE.endpoint)))
        assertFalse(Http.allowed(URL("https://view.eumetsat.int/other")))
        assertFalse(Http.allowed(URL("http://view.eumetsat.int/geoserver/wms")))
        assertFalse(Http.allowed(URL("https://maps.dwd.de/geoserver/dwd/other")))
    }

    @Test
    fun serviceExceptions_areNotMistakenForImages() {
        val error =
            MapWms.serviceError(
                "<ServiceExceptionReport><ServiceException>Could not find a match for &apos;time&apos;</ServiceException></ServiceExceptionReport>"
                    .toByteArray()
            )
        assertEquals("Could not find a match for 'time'", error)
        assertTrue(MapWms.timeUnavailable(error))
        assertNull(MapWms.serviceError(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)))
        assertFalse(MapWms.timeUnavailable("Invalid map image"))
    }

    @Test
    fun xmlEntities_areRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MapWms.capabilities(
                "<!DOCTYPE xml [<!ENTITY external SYSTEM 'file:///etc/passwd'>]><xml/>"
                    .toByteArray(),
                MapProduct.SATELLITE,
                now,
            )
        }
    }
}
