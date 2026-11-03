package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class RadarProjectionTest {
    private val valid =
        "+proj=stere +lat_ts=60 +lat_0=90 +lon_0=10 +x_0=543196.83521776402 " +
            "+y_0=3622588.8619310022 +units=m +a=6378137 +b=6356752.3142451802 +no_defs"

    @Test
    fun checkedInRadarFixturesDeclareSupportedProjection() {
        for (name in listOf("rv-observed-20261022.h5", "rv-forecast-20261022.h5")) {
            val radar = Odim.decode(javaClass.getResourceAsStream("/$name")!!.readBytes())
            assertEquals(1100, radar.grid.width)
            assertEquals(1200, radar.grid.height)
            assertTrue(radar.grid.contains(Place.MUNICH))
        }
    }

    @Test
    fun supportedProjection_acceptsParameterOrderWhitespaceAndOptionalNoDefs() {
        RadarProjection.validate(valid)
        RadarProjection.validate(valid.removeSuffix(" +no_defs"))
        RadarProjection.validate(
            "  +b=6356752.3142451802   +a=6378137 +units=m +y_0=3622588.8619310022 " +
                "+x_0=543196.83521776402 +lon_0=10 +lat_0=90 +lat_ts=60 +proj=stere  "
        )
    }

    @Test
    fun missingRequiredParameter_isRejectedWithReadableError() {
        val required = listOf("proj", "lat_ts", "lat_0", "lon_0", "x_0", "y_0", "units", "a", "b")
        for (key in required) {
            val incomplete =
                valid.split(Regex("\\s+")).filterNot { it.startsWith("+$key=") }.joinToString(" ")
            assertRejected(incomplete, "+$key")
        }
    }

    @Test
    fun unsupportedParameterValue_isRejectedWithReadableError() {
        val wrong =
            mapOf(
                "+proj=stere" to "+proj=longlat",
                "+lat_ts=60" to "+lat_ts=59",
                "+lat_0=90" to "+lat_0=89",
                "+lon_0=10" to "+lon_0=9",
                "+x_0=543196.83521776402" to "+x_0=543196.8",
                "+y_0=3622588.8619310022" to "+y_0=3622588.8",
                "+units=m" to "+units=ft",
                "+a=6378137" to "+a=6378136",
                "+b=6356752.3142451802" to "+b=6356750",
            )
        for ((expected, replacement) in wrong) {
            assertRejected(valid.replace(expected, replacement), expected.substringBefore('='))
        }
    }

    @Test
    fun duplicateParameter_isRejectedWithReadableError() {
        assertRejected("$valid +lat_0=90", "+lat_0")
        assertRejected("$valid +no_defs", "+no_defs")
        assertRejected(valid.replace("+lat_0=90", "+lat_0 +lat_0=90"), "+lat_0")
    }

    @Test
    fun unsupportedProjectionVariant_isRejectedWithReadableError() {
        assertRejected("$valid +ellps=WGS84", "+ellps")
        assertRejected("$valid +k=0.9996", "+k")
        assertRejected(valid.replace("+no_defs", "+no_defs=1"), "+no_defs")
        assertRejected(valid.replace("+proj=stere", "proj=stere"), "proj=stere")
        assertRejected("", "+proj")
    }

    private fun assertRejected(projdef: String, parameter: String) {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                RadarProjection.validate(projdef)
            }
        val message = error.message.orEmpty()
        assertTrue("Missing readable reason in '$message'", message.contains("projection"))
        assertTrue("Missing '$parameter' in '$message'", message.contains(parameter))
    }
}
