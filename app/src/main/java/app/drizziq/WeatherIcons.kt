package app.drizziq

/** DWD intensity codes take precedence; amounts only disambiguate generic/combined codes. */
fun weatherIconName(code: Int?, precipitation: Double? = null): String {
    val amount = precipitation?.takeIf { it.isFinite() && it >= 0 }
    fun level(light: Double, heavy: Double): String =
        when {
            amount == null -> "medium"
            amount < light -> "light"
            amount < heavy -> "medium"
            else -> "heavy"
        }
    val name =
        when (code) {
            0 -> "sunny"
            1 -> "mainly-clear"
            2 -> "partly-cloudy"
            3 -> "cloudy"
            45 -> "foggy"
            48,
            49 -> "rime-fog"
            50 ->
                when (level(0.1, 0.5)) {
                    "medium" -> "drizzle"
                    else -> "drizzle-${level(0.1, 0.5)}"
                }
            51 -> "drizzle-light"
            53 -> "drizzle"
            55 -> "drizzle-heavy"
            56 -> "freezing-drizzle-light"
            57 -> "freezing-drizzle-heavy"
            60 -> "rain-${level(2.5, 7.5)}"
            61 -> "rain-light"
            63 -> "rain-medium"
            65 -> "rain-heavy"
            66 -> "freezing-rain-light"
            67 -> "freezing-rain-heavy"
            68 -> "sleet-light"
            69 -> "sleet-heavy"
            70 -> "snow-${level(0.5, 1.5)}"
            71 -> "snow-light"
            73 -> "snow-medium"
            75 -> "snow-heavy"
            77 -> "snow-grains"
            80 -> "rain-shower-light"
            81 -> if (amount != null && amount >= 7.5) "rain-shower-heavy" else "rain-shower-medium"
            82 -> "rain-shower-heavy"
            83 -> "sleet-shower-light"
            84 -> "sleet-shower-heavy"
            85 -> "snow-shower-light"
            86 -> "snow-shower-heavy"
            95 -> if (amount != null && amount >= 7.5) "thunderstorm-heavy" else "thunderstorm"
            96 -> "thunderstorm-hail-light"
            97 -> "thunderstorm-heavy"
            99 -> "thunderstorm-hail-heavy"
            else -> "unknown"
        }
    return "weather-$name"
}

/** Speed is the supplied mean wind in km/h, not the gust or a substitute condition. */
fun windIconName(speed: Double?): String? {
    if (speed == null || !speed.isFinite() || speed < 0) return null
    return "weather-windy-" +
        when {
            speed < 30 -> "light"
            speed < 40 -> "medium"
            else -> "strong"
        }
}
