package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class WeatherIconsTest {
    @Test
    fun weatherIcons_remainingWmoConditions_useExistingFamiliesAndKeepUnknownFallback() {
        for ((code, family) in
            mapOf(
                4 to "foggy",
                17 to "thunderstorm",
                18 to "windy-strong",
                20 to "drizzle",
                21 to "rain-medium",
                22 to "snow-medium",
                27 to "snow-grains",
                31 to "windy-strong",
                38 to "snow-heavy",
                44 to "foggy",
                46 to "foggy",
                52 to "drizzle-light",
                54 to "drizzle",
                59 to "rain-heavy",
                62 to "rain-light",
                64 to "rain-medium",
                72 to "snow-light",
                74 to "snow-medium",
                78 to "snow-grains",
                87 to "snow-grains",
                90 to "snow-grains",
                91 to "rain-light",
                94 to "sleet-heavy",
                98 to "thunderstorm-heavy",
            )) {
            assertEquals("code $code", "weather-$family", weatherIconName(code))
        }
        for (code in 0..99) assertNotEquals("code $code", "weather-unknown", weatherIconName(code))
        assertEquals("weather-unknown", weatherIconName(-1))
        assertEquals("weather-unknown", weatherIconName(100))
    }

    @Test
    fun weatherIcons_explicitIntensityAndFreezingMixedConditions_keepDwdMeaning() {
        val expected =
            mapOf(
                0 to "sunny",
                1 to "mainly-clear",
                2 to "partly-cloudy",
                3 to "cloudy",
                45 to "foggy",
                49 to "rime-fog",
                51 to "drizzle-light",
                53 to "drizzle",
                55 to "drizzle-heavy",
                56 to "freezing-drizzle-light",
                57 to "freezing-drizzle-heavy",
                61 to "rain-light",
                63 to "rain-medium",
                65 to "rain-heavy",
                66 to "freezing-rain-light",
                67 to "freezing-rain-heavy",
                68 to "sleet-light",
                69 to "sleet-heavy",
                71 to "snow-light",
                73 to "snow-medium",
                75 to "snow-heavy",
                80 to "rain-shower-light",
                81 to "rain-shower-medium",
                82 to "rain-shower-heavy",
                83 to "sleet-shower-light",
                84 to "sleet-shower-heavy",
                85 to "snow-shower-light",
                86 to "snow-shower-heavy",
                95 to "thunderstorm",
                96 to "thunderstorm-hail-light",
                97 to "thunderstorm-heavy",
                99 to "thunderstorm-hail-heavy",
            )
        expected.forEach { (code, icon) ->
            assertEquals("code $code", "weather-$icon", weatherIconName(code))
        }
        assertEquals("weather-rain-light", weatherIconName(61, 20.0))
        assertEquals("weather-rain-heavy", weatherIconName(65, 0.0))
        assertEquals("weather-freezing-rain-heavy", weatherIconName(67, 20.0))
        assertEquals("weather-snow-grains", weatherIconName(77))
        assertEquals("weather-rime-fog", weatherIconName(48))
        assertEquals("weather-unknown", weatherIconName(null))
        assertEquals("weather-unknown", weatherIconName(999))
    }

    @Test
    fun weatherIcons_genericAndAmbiguousCodes_useHourlyAmountAtThresholds() {
        for ((amount, intensity) in
            listOf(
                0.0 to "light",
                2.49 to "light",
                2.5 to "medium",
                7.49 to "medium",
                7.5 to "heavy",
            )) {
            assertEquals("weather-rain-$intensity", weatherIconName(60, amount))
        }
        assertEquals("weather-drizzle-light", weatherIconName(50, 0.09))
        assertEquals("weather-drizzle", weatherIconName(50, 0.1))
        assertEquals("weather-drizzle-heavy", weatherIconName(50, 0.5))
        assertEquals("weather-snow-light", weatherIconName(70, 0.49))
        assertEquals("weather-snow-medium", weatherIconName(70, 0.5))
        assertEquals("weather-snow-heavy", weatherIconName(70, 1.5))
        assertEquals("weather-rain-shower-heavy", weatherIconName(81, 7.5))
        assertEquals("weather-thunderstorm-heavy", weatherIconName(95, 7.5))
        for (amount in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals("weather-rain-medium", weatherIconName(60, amount))
            assertEquals("weather-rain-shower-medium", weatherIconName(81, amount))
            assertEquals("weather-thunderstorm", weatherIconName(95, amount))
        }
    }

    @Test
    fun windIcons_speedInKmh_changesAt30And40AndPreservesMissingness() {
        assertEquals("weather-windy-light", windIconName(0.0))
        assertEquals("weather-windy-light", windIconName(29.9))
        assertEquals("weather-windy-medium", windIconName(30.0))
        assertEquals("weather-windy-strong", windIconName(40.0))
        for (bad in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(
            windIconName(bad)
        )
    }
}
