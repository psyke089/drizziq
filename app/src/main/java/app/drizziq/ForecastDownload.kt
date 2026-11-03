package app.drizziq

/**
 * Downloads, parses and validates one MOSMIX product before handing its raw bytes to the cache.
 *
 * The cache is best effort: a failed write must not discard a successfully downloaded product.
 * Download, parse and validation failures still propagate so callers can retain previous data.
 */
internal object ForecastDownload {
    fun latest(
        station: Station,
        download: () -> ByteArray,
        cache: (ByteArray) -> Unit,
    ): Forecast =
        load(
            download = download,
            cache = cache,
            validate = { it.station.id == station.id },
            invalid = "Wrong forecast point",
        )

    fun historical(
        station: Station,
        issue: Long,
        download: () -> ByteArray,
        cache: (ByteArray) -> Unit,
    ): Forecast =
        load(
            download = download,
            cache = cache,
            validate = { it.station.id == station.id && it.issue == issue },
            invalid = "Wrong historical forecast",
        )

    private fun load(
        download: () -> ByteArray,
        cache: (ByteArray) -> Unit,
        validate: (Forecast) -> Boolean,
        invalid: String,
    ): Forecast {
        val bytes = download()
        val result = Mosmix.parseKmz(bytes.inputStream())
        require(validate(result)) { invalid }
        runCatching { cache(bytes) }
        return result
    }
}
