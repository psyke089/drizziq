package app.drizziq

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class OverlayTest {
    private fun at(s: String) = Instant.parse(s).toEpochMilli()

    @Test
    fun tileBounds_webMercatorWorldAndAdjacentTiles() {
        val world = OverlayModel.bounds(0, 0, 0)
        assertEquals(-20037508.342789244, world[0], .01)
        assertEquals(20037508.342789244, world[2], .01)
        assertEquals(OverlayModel.bounds(3, 2, 3)[2], OverlayModel.bounds(3, 3, 3)[0], .001)
        assertThrows(IllegalArgumentException::class.java) { OverlayModel.bounds(3, 8, 0) }
    }

    @Test
    fun advertisedTimes_selectOnlyValidBucketsAndCoveringRuns() {
        val caps =
            OverlayModel.capabilities(
                javaClass.getResourceAsStream("/wms-capabilities-trimmed.xml")!!.readBytes()
            )
        val now = at("2026-11-03T10:25:00Z")
        val clouds = OverlayModel.select(Overlay.CLOUDS, at("2026-11-03T12:00:00Z"), now, caps)!!
        assertEquals(at("2026-11-03T09:00:00Z"), clouds.time)
        assertNull(clouds.run)
        val temp = OverlayModel.select(Overlay.TEMPERATURE, at("2026-11-03T10:25:00Z"), now, caps)!!
        assertEquals(at("2026-11-03T10:00:00Z"), temp.time)
        assertEquals(at("2026-11-03T06:00:00Z"), temp.run)
        assertEquals(
            at("2026-11-03T12:00:00Z"),
            OverlayModel.select(Overlay.TEMPERATURE, at("2026-11-03T12:59:00Z"), now, caps)!!.time,
        )
        assertNull(OverlayModel.select(Overlay.TEMPERATURE, at("2026-11-03T13:00:00Z"), now, caps))
        assertNull(OverlayModel.select(Overlay.TEMPERATURE, at("2026-11-03T20:00:00Z"), now, caps))
    }

    @Test
    fun urlAndKey_includeAllDimensionsAndRejectUnsafeEndpoints() {
        val selection =
            OverlaySelection(
                Overlay.TEMPERATURE,
                at("2026-11-03T10:00:00Z"),
                at("2026-11-03T06:00:00Z"),
            )
        val url = OverlayModel.url(selection, 5, 16, 10)
        assertTrue(url.contains("REFERENCE_TIME=2026-11-03T06%3A00%3A00Z"))
        assertTrue(url.contains("ELEVATION=2.0"))
        assertTrue(url.contains("CRS=EPSG%3A3857"))
        assertNotEquals(
            OverlayModel.key(selection, 5, 16, 10),
            OverlayModel.key(selection, 5, 16, 11),
        )
        assertTrue(Http.allowed(java.net.URL("https://maps.dwd.de/geoserver/dwd/wms")))
        assertFalse(Http.allowed(java.net.URL("https://maps.dwd.de/geoserver/dwd/other")))
        assertFalse(Http.allowed(java.net.URL("http://maps.dwd.de/geoserver/dwd/wms")))
    }

    @Test
    fun wmsErrorsAndInvalidImages_areDistinguished() {
        assertEquals(
            "No data available",
            OverlayModel.serviceError(
                "<ServiceExceptionReport><ServiceException>No data available</ServiceException></ServiceExceptionReport>"
                    .toByteArray()
            ),
        )
        assertNull(OverlayModel.serviceError(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)))
    }
}
