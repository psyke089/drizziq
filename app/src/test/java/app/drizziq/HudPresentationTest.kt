package app.drizziq

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class HudPresentationTest {
    @Test
    fun englishHudNumbers_doNotFollowGermanDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                "Temperature -5.7 degrees; 48.1381 N",
                hudFormat("Temperature %.1f degrees; %.4f N", -5.7, 48.1381),
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun temperatureColor_interpolatesAbsoluteCelsiusAndClampsExtremes() {
        assertEquals(0xff8ebbff.toInt(), temperatureColor(-100.0))
        assertEquals(0xff79d5ee.toInt(), temperatureColor(0.0))
        assertEquals(0xff7be4d4.toInt(), temperatureColor(9.0))
        assertEquals(0xfff4778c.toInt(), temperatureColor(100.0))
        assertEquals(0xff7ce6d2.toInt(), temperatureColor(10.0))
    }
}
