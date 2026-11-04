package app.drizziq

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class OverlayTest {
    private fun at(s: String) = Instant.parse(s).toEpochMilli()

    private val caps by lazy {
        OverlayModel.capabilities(
            javaClass.getResourceAsStream("/wms-capabilities-trimmed.xml")!!.readBytes()
        )
    }

    @Test
    fun tileBounds_webMercatorWorldAndAdjacentTiles() {
        val world = OverlayModel.bounds(0, 0, 0)
        assertEquals(-20037508.342789244, world[0], .01)
        assertEquals(20037508.342789244, world[2], .01)
        assertEquals(OverlayModel.bounds(3, 2, 3)[2], OverlayModel.bounds(3, 3, 3)[0], .001)
        assertThrows(IllegalArgumentException::class.java) { OverlayModel.bounds(3, 8, 0) }
    }

    @Test
    fun nestedStyleNames_doNotOverwriteLayerNames() {
        val xml =
            """
            <WMS_Capabilities xmlns="http://www.opengis.net/wms" version="1.3.0"><Capability><Layer>
              <Layer><Name>Icon_reg025_fd_sl_T2M</Name>
                <Style><Name>icon-only-style</Name><Title>Default</Title></Style>
                <Dimension name="reference_time" units="ISO8601">2026-11-03T00:00:00Z</Dimension>
                <Dimension name="time" units="ISO8601">2026-11-03T00:00:00Z/2026-11-03T02:00:00Z/PT1H</Dimension>
              </Layer>
              <Layer><Name>Satellite_worldmosaic_3km_world_ir108_3h</Name><Title>IR</Title>
                <Dimension name="time" units="ISO8601">2026-11-03T00:00:00Z/2026-11-03T06:00:00Z/PT3H</Dimension>
              </Layer>
            </Layer></Capability></WMS_Capabilities>
            """
                .trimIndent()
        val caps = OverlayModel.capabilities(xml.toByteArray())
        assertEquals(setOf(Overlay.TEMPERATURE, Overlay.CLOUDS), caps.keys)
        assertEquals(3, caps.getValue(Overlay.TEMPERATURE).times.size)
        assertEquals(3, caps.getValue(Overlay.CLOUDS).times.size)
    }

    @Test
    fun advertisedTimes_selectOnlyValidBucketsAndCoveringRuns() {
        val now = at("2026-11-03T10:25:00Z")
        val clouds = OverlayModel.select(Overlay.CLOUDS, at("2026-11-03T12:00:00Z"), now, caps)!!
        assertEquals(at("2026-11-03T09:00:00Z"), clouds.time)
        val temp = OverlayModel.select(Overlay.TEMPERATURE, at("2026-11-03T10:25:00Z"), now, caps)!!
        assertEquals(at("2026-11-03T10:00:00Z"), temp.time)
        assertEquals(
            at("2026-11-03T12:00:00Z"),
            OverlayModel.select(Overlay.TEMPERATURE, at("2026-11-03T12:59:00Z"), now, caps)!!.time,
        )
        assertNull(OverlayModel.select(Overlay.TEMPERATURE, at("2026-11-03T15:00:00Z"), now, caps))
        assertNull(OverlayModel.select(Overlay.TEMPERATURE, at("2026-11-03T20:00:00Z"), now, caps))
    }

    @Test
    fun temperatureSelection_omitsModelRunAndElevation() {
        val temp = OverlayModel.select(Overlay.TEMPERATURE, at("2026-11-03T10:25:00Z"), 0L, caps)!!
        assertFalse(OverlayModel.url(temp, 5, 17, 10).contains("REFERENCE_TIME"))
        assertFalse(OverlayModel.url(temp, 5, 17, 10).contains("ELEVATION"))
    }

    @Test
    fun resolvedTime_overridesStaleAdvertisedWindow() {
        val fallback = at("2026-11-03T09:00:00Z")
        val clouds =
            OverlayModel.select(
                Overlay.CLOUDS,
                at("2026-11-03T10:25:00Z"),
                0L,
                caps,
                resolved = fallback,
            )!!
        assertEquals(fallback, clouds.time)
    }

    @Test
    fun candidates_descendFromCurrentSlotAtOverlayCadence() {
        val now = at("2026-11-03T10:25:00Z")
        val clouds = OverlayModel.candidates(Overlay.CLOUDS, now)
        assertEquals(at("2026-11-03T09:00:00Z"), clouds.first())
        assertEquals(at("2026-11-03T06:00:00Z"), clouds[1])
        assertTrue(clouds.all { it <= now })
        assertTrue(clouds.size in 2..16)
        assertTrue(clouds.zipWithNext().all { (a, b) -> a - b == 3 * 3600_000L })
        val temp = OverlayModel.candidates(Overlay.TEMPERATURE, now)
        assertEquals(at("2026-11-03T10:00:00Z"), temp.first())
        assertTrue(temp.zipWithNext().all { (a, b) -> a - b == 3600_000L })
        assertTrue(temp.size <= 24)
    }

    @Test
    fun timeErrors_areClassifiedFromDwdMessages() {
        assertTrue(
            OverlayModel.timeUnavailable(
                "Could not find a match for 'time' value: '2026-10-28T12:00:00.000Z'"
            )
        )
        assertTrue(
            OverlayModel.timeUnavailable(
                "Could not read coverage data. No data available for NinJo NWP data request"
            )
        )
        assertFalse(OverlayModel.timeUnavailable("Invalid overlay image"))
        assertFalse(OverlayModel.timeUnavailable(null))
    }

    @Test
    fun urlAndKey_includeAllDimensionsAndRejectUnsafeEndpoints() {
        val selection = OverlaySelection(Overlay.TEMPERATURE, at("2026-11-03T10:00:00Z"))
        val url = OverlayModel.url(selection, 5, 16, 10)
        assertTrue(url.contains("LAYERS=Icon_reg025_fd_sl_T2M"))
        assertTrue(url.contains("TIME=2026-11-03T10%3A00%3A00.000Z"))
        assertTrue(OverlayModel.key(selection, 5, 16, 10).contains(Overlay.TEMPERATURE.layer))
        assertFalse(url.contains("ELEVATION"))
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
        assertEquals(
            "Could not find a match for 'time' value",
            OverlayModel.serviceError(
                "<ServiceExceptionReport><ServiceException>Could not find a match for &apos;time&apos; value</ServiceException></ServiceExceptionReport>"
                    .toByteArray()
            ),
        )
        assertNull(OverlayModel.serviceError(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)))
    }

    @Test
    fun resolvedBucket_usesResolutionAgeRatherThanWeatherAge() {
        val policy = OverlayRefreshPolicy()
        val now = at("2026-11-03T10:45:00Z")
        val bucket = at("2026-11-03T09:00:00Z")
        policy.resolve(Overlay.CLOUDS, bucket, now)
        assertEquals(bucket, policy.resolved(Overlay.CLOUDS, now + 29 * 60_000L))
        assertNull(policy.resolved(Overlay.CLOUDS, now + 30 * 60_000L))
    }

    @Test
    fun failedCapabilities_retryAfterOneMinuteAndSuccessRestoresHourlyCadence() {
        val policy = OverlayRefreshPolicy()
        val now = at("2026-11-03T10:45:00Z")
        assertTrue(policy.capabilitiesDue(now))
        policy.capabilitiesFailed(now)
        assertFalse(policy.capabilitiesDue(now + 59_999L))
        assertTrue(policy.capabilitiesDue(now + 60_000L))
        policy.capabilitiesSucceeded(now + 60_000L)
        assertFalse(policy.capabilitiesDue(now + 120_000L))
        assertTrue(policy.capabilitiesDue(now + 60_000L + 3600_000L))
    }
}
