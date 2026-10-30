package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class RadarViewportTest {
    @Test
    fun decode_savedViewport_preservesFractionalCenterAndZoom() {
        val viewport = RadarViewport(0.999123456789, 0.347123456789, 9.375)
        assertEquals(viewport, RadarViewport.decode(viewport.encode(Place.MUNICH), Place.MUNICH))
    }

    @Test
    fun decode_sameCoordinatesWithRenamedPlace_restoresViewport() {
        val viewport = RadarViewport(0.2, 0.4, 8.5)
        assertEquals(
            viewport,
            RadarViewport.decode(viewport.encode(Place.MUNICH), Place.MUNICH.copy(label = "Home")),
        )
    }

    @Test
    fun decode_differentSelectedCoordinates_doesNotRestoreOldMap() {
        val encoded = RadarViewport(0.2, 0.4, 8.5).encode(Place.MUNICH)
        assertNull(RadarViewport.decode(encoded, Place("Berlin", 52.52, 13.405)))
    }

    @Test
    fun decode_missingCorruptOrOutOfBoundsState_fallsBackSafely() {
        val prefix = "1|${Place.MUNICH.lat}|${Place.MUNICH.lon}|"
        for (encoded in
            listOf(
                null,
                "",
                "broken",
                "2|${Place.MUNICH.lat}|${Place.MUNICH.lon}|0.5|0.5|7",
                prefix + "0.5|0.5",
                prefix + "NaN|0.5|7",
                prefix + "0.5|Infinity|7",
                prefix + "0.5|0.5|NaN",
                prefix + "-0.1|0.5|7",
                prefix + "1.0|0.5|7",
                prefix + "0.5|-0.1|7",
                prefix + "0.5|1.1|7",
                prefix + "0.5|0.5|1.9",
                prefix + "0.5|0.5|17.1",
            )) {
            assertNull("Invalid saved state: $encoded", RadarViewport.decode(encoded, Place.MUNICH))
        }
    }

    @Test
    fun decode_validWorldAndZoomBoundaries_restoresViewport() {
        for (viewport in listOf(RadarViewport(0.0, 0.0, 2.0), RadarViewport(0.999999, 1.0, 17.0))) {
            assertEquals(
                viewport,
                RadarViewport.decode(viewport.encode(Place.MUNICH), Place.MUNICH),
            )
        }
    }
}
