package app.drizziq

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object Http {
    val downloads = DownloadTracker()
    val USER_AGENT =
        "Drizziq/${BuildConfig.VERSION_NAME} (Android; https://codeberg.org/k4r/drizziq)"
    const val WEATHER = "https://opendata.dwd.de/weather/"

    internal fun allowed(url: URL): Boolean =
        url.protocol == "https" &&
            (Geocoding.allowed(url) ||
                url.host == "tile.openstreetmap.org" ||
                (url.host == "opendata.dwd.de" && url.path.startsWith("/weather/")))

    private fun checked(url: URL) {
        require(allowed(url)) { "Unapproved data endpoint" }
    }

    fun bytes(address: String, limit: Int = 32000000, range: String? = null): ByteArray =
        downloads.track {
            request(address, limit, range)
        }

    /**
     * Conditional image/data GET. Persisted validators are only attached when present; a 304 is
     * returned without reading a body so callers can keep their cached representation.
     */
    internal fun conditional(
        address: String,
        limit: Int = 32000000,
        etag: String? = null,
        lastModified: String? = null,
        progress: ((Long, Long?) -> Unit)? = null,
        open: (URL) -> HttpURLConnection = AppPlatform::open,
    ): HttpReply = downloads.track {
        conditionalRequest(address, limit, etag, lastModified, progress, open)
    }

    private fun conditionalRequest(
        address: String,
        limit: Int,
        etag: String?,
        lastModified: String?,
        progress: ((Long, Long?) -> Unit)?,
        open: (URL) -> HttpURLConnection,
    ): HttpReply {
        var url = URL(address)
        repeat(4) {
            checked(url)
            val c =
                open(url).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    if (!etag.isNullOrEmpty()) setRequestProperty("If-None-Match", etag)
                    if (!lastModified.isNullOrEmpty()) {
                        setRequestProperty("If-Modified-Since", lastModified)
                    }
                }
            try {
                val status = c.responseCode
                val responseEtag = c.getHeaderField("ETag")?.takeIf { it.isNotEmpty() }
                val responseModified = c.getHeaderField("Last-Modified")?.takeIf { it.isNotEmpty() }
                if (status == 304) {
                    return HttpReply.NotModified(
                        responseEtag ?: etag,
                        responseModified ?: lastModified,
                    )
                }
                if (status in 300..399) {
                    url = URL(url, c.getHeaderField("Location") ?: error("Empty redirect"))
                    return@repeat
                }
                require(status == 200) { "Server returned HTTP $status" }
                return HttpReply.Ok(
                    read(c, limit, range = null, status = status, progress = progress),
                    responseEtag,
                    responseModified,
                )
            } finally {
                c.disconnect()
            }
        }
        throw IllegalArgumentException("Too many redirects")
    }

    private fun request(address: String, limit: Int, range: String?): ByteArray =
        get(address, range) { connection, status -> read(connection, limit, range, status) }

    /** Read only the observed first member; closing the stream avoids fetching old forecasts. */
    internal fun firstTarEntry(
        address: String,
        open: (URL) -> HttpURLConnection = AppPlatform::open,
    ): Tar.Entry = downloads.track {
        get(address, open = open) { connection, _ ->
            val input = connection.inputStream
            try {
                Tar.first(input)
            } finally {
                // Disconnect before close: some HTTP clients drain a closed stream for reuse.
                connection.disconnect()
                input.close()
            }
        }
    }

    private fun <T> get(
        address: String,
        range: String? = null,
        open: (URL) -> HttpURLConnection = AppPlatform::open,
        consume: (HttpURLConnection, Int) -> T,
    ): T {
        var url = URL(address)
        repeat(4) {
            checked(url)
            val connection =
                open(url).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    if (range != null) setRequestProperty("Range", "bytes=$range")
                }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("Empty redirect"))
                    return@repeat
                }
                require(status == 200 || (range != null && status == 206)) {
                    "Server returned HTTP $status"
                }
                return consume(connection, status)
            } finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }

    private fun read(
        c: HttpURLConnection,
        limit: Int,
        range: String?,
        status: Int,
        progress: ((Long, Long?) -> Unit)? = null,
    ): ByteArray =
        c.inputStream.use { input ->
            // Servers may ignore Range. Select the requested bytes locally in that case.
            val start = range?.substringBefore('-')?.toInt() ?: 0
            val length = range?.substringAfter('-')?.toInt()?.let { it - start + 1 }
            if (range != null && status == 200) {
                var n = 0
                while (n < start) {
                    val skipped = input.skip((start - n).toLong()).toInt()
                    if (skipped == 0) {
                        require(input.read() >= 0)
                        n++
                    } else n += skipped
                }
            }
            val total = c.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
            progress?.invoke(0, total)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (length == null || out.size() < length) {
                val n =
                    input.read(
                        buffer,
                        0,
                        if (length == null) buffer.size
                        else minOf(buffer.size, length - out.size()),
                    )
                if (n < 0) break
                require(out.size() + n <= limit) { "Data response too large" }
                out.write(buffer, 0, n)
                progress?.invoke(out.size().toLong(), total)
            }
            if (length != null) require(out.size() == length) { "Truncated DWD response" }
            out.toByteArray()
        }
}

/** Result of [Http.conditional]; a 304 deliberately carries no body. */
internal sealed class HttpReply {
    class Ok(val bytes: ByteArray, val etag: String?, val lastModified: String?) : HttpReply()

    class NotModified(val etag: String?, val lastModified: String?) : HttpReply()
}

class Repository(private val context: Context, private val changed: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val closed = AtomicBoolean(false)
    private val directory = File(context.filesDir, "data").apply { mkdirs() }
    private val stationIndex by lazy {
        runCatching {
            context.assets.open("stations.tsv").bufferedReader().use {
                StationIndex.fromTsv(it.readText())
            }
        }
    }
    private val generation = RequestGeneration()
    private var radarBusy = false
    private val radarWorker = Executors.newSingleThreadExecutor()
    private val radarGeneration = RequestGeneration()
    internal var radarProgress = RadarProgress()
        private set

    var place = Place.MUNICH
        private set

    var forecastPoint: StationHit? = null
        private set

    var forecast = Reading<Forecast>()
        private set

    var radar = Reading<RadarSequence>()
        private set

    @Volatile
    var online = true
        private set

    private val forecastCache by lazy {
        CacheStore(
            directory,
            CachePolicy.FORECAST_BYTES,
            eligible = { name ->
                name.endsWith(".kmz") &&
                    (name.startsWith("forecast-") || name.startsWith("MOSMIX_L_"))
            },
            indexName = "forecast-access.tsv",
        )
    }

    private fun historyExpiry(file: File, now: Long): Boolean =
        file.name.startsWith("MOSMIX_L_") && now - file.lastModified() > CachePolicy.HISTORY_MS

    private fun post(action: () -> Unit) {
        handler.post { if (!closed.get()) action() }
    }

    fun connection(): Boolean {
        online = AppPlatform.online(context)
        if (!online) {
            forecast = forecast.failed("Offline · last successful data retained")
            radar = radar.failed("Offline · last successful frames retained")
        }
        changed()
        return online
    }

    private val forecastLookup by lazy {
        ForecastLookup(
            { stationIndex.getOrThrow() },
            object : ForecastSource {
                override fun cached(station: Station): CachedForecast? {
                    val file = File(directory, "forecast-${station.id}.kmz")
                    if (!file.exists()) return null
                    val forecast = file.inputStream().use { Mosmix.parseKmz(it) }
                    forecastCache.read(file)
                    return CachedForecast(forecast, file.lastModified())
                }

                override fun fetch(station: Station): Forecast {
                    check(online) { "Offline" }
                    return ForecastDownload.latest(
                        station,
                        download = {
                            Http.bytes(
                                "${Http.WEATHER}local_forecasts/mos/MOSMIX_L/single_stations/${station.id}/kml/MOSMIX_L_LATEST_${station.id}.kmz",
                                3000000,
                            )
                        },
                    ) { bytes ->
                        val file = File(directory, "forecast-${station.id}.kmz")
                        AtomicFiles.write(file, bytes)
                        forecastCache.wrote(file, ::historyExpiry)
                    }
                }

                override fun earlier(station: Station, dayStart: Long, online: Boolean): Forecast? {
                    // MOSMIX L issues at 03/09/15/21 UTC; use the last run covering midnight.
                    val hour = 3600000L
                    val issue =
                        Math.floorDiv(dayStart - hour - 3 * hour, 6 * hour) * 6 * hour + 3 * hour
                    val name = "MOSMIX_L_${Times.format(issue, "yyyyMMddHH")}_${station.id}.kmz"
                    val file = File(directory, name)
                    if (file.exists()) {
                        val cached = runCatching {
                            file.inputStream().use { Mosmix.parseKmz(it) }
                        }
                            .getOrNull()
                        if (cached?.station?.id == station.id && cached.issue == issue) {
                            forecastCache.read(file)
                            return cached
                        }
                    }
                    if (!online || !this@Repository.online || closed.get()) return null
                    return ForecastDownload.historical(
                        station,
                        issue,
                        download = {
                            Http.bytes(
                                "${Http.WEATHER}local_forecasts/mos/MOSMIX_L/single_stations/${station.id}/kml/$name",
                                3000000,
                            )
                        },
                    ) { bytes ->
                        AtomicFiles.write(file, bytes)
                        forecastCache.wrote(file, ::historyExpiry)
                    }
                }
            },
            { AppPlatform.now() },
        )
    }

    fun select(p: Place) {
        val previous = forecast
        val retain = place == p && previous.data != null
        if (place != p) forecastPoint = null
        place = p
        val token = generation.next()
        forecast =
            if (retain) previous.copy(refreshing = true, message = "Checking DWD")
            else Reading(message = "Locating nearest DWD station", refreshing = true)
        changed()
        workers.execute {
            forecastCache.prune(::historyExpiry)
            forecastLookup.load(
                p,
                if (retain) previous else Reading(),
                online,
                { generation.isCurrent(token) && !closed.get() },
                resolved = { hit ->
                    post {
                        if (generation.isCurrent(token)) {
                            forecastPoint = hit
                            changed()
                        }
                    }
                },
            ) { reading ->
                post {
                    if (generation.isCurrent(token)) {
                        forecast =
                            if (!online && reading.data != null)
                                reading.failed("Offline · last matching forecast retained")
                            else reading
                        changed()
                    }
                }
            }
        }
        if (radar.data == null && !radarBusy) loadRadarCache()
    }

    fun refreshForecast() {
        if (forecast.refreshing) return
        select(place)
    }

    private fun postRadar(token: Int, action: () -> Unit) {
        post { if (radarGeneration.isCurrent(token)) action() }
    }

    private fun loadRadarCache() {
        radarBusy = true
        val token = radarGeneration.next()
        radarWorker.execute {
            val saved = runCatching { RadarCache.read(directory).snapshot }.getOrNull()
            postRadar(token) {
                if (saved != null) {
                    radar =
                        Reading(
                            saved.sequence,
                            if (online) Freshness.CACHED else Freshness.STALE,
                            saved.downloadedAt,
                            "Saved radar · checking DWD",
                        )
                    changed()
                }
                radarBusy = false
                refreshRadar()
            }
        }
    }

    fun refreshRadar() {
        if (radarBusy || closed.get()) return
        radarBusy = true
        val token = radarGeneration.next()
        radarProgress = RadarProgress(phase = RadarPhase.DOWNLOAD)
        radar =
            radar.copy(
                state = if (radar.data == null) Freshness.LOADING else radar.state,
                refreshing = true,
                message = "Receiving DWD radar",
            )
        changed()
        radarWorker.execute {
            try {
                check(online) { "Offline" }
                AtomicFiles.cleanup(directory, AppPlatform.now())
                val saved = runCatching { RadarCache.read(directory).snapshot }.getOrNull()
                val base = "${Http.WEATHER}radar/composite/rv/"
                val result =
                    RadarRefresh(
                            latest = { cached, progress ->
                                Http.conditional(
                                    base + "composite_rv_LATEST.tar",
                                    etag = cached?.etag,
                                    lastModified = cached?.lastModified,
                                    progress = progress,
                                )
                            },
                            observed = { issue ->
                                check(online) { "Offline" }
                                Http.firstTarEntry(
                                    base +
                                        "composite_rv_${Times.format(issue, "yyyyMMdd_HHmm")}.tar"
                                )
                            },
                            decode = { entry ->
                                val member = RadarMember.parse(entry.name)
                                val pixels = Odim.decode(entry.bytes)
                                require(pixels.time == member.time) { "DWD frame time mismatch" }
                                val dest = File(directory, "rv-${member.issue}-${member.lead}.png")
                                val bitmap =
                                    Bitmap.createBitmap(
                                        pixels.colors,
                                        pixels.grid.width,
                                        pixels.grid.height,
                                        Bitmap.Config.ARGB_8888,
                                    )
                                try {
                                    val out = ByteArrayOutputStream()
                                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                                    AtomicFiles.write(dest, out.toByteArray())
                                } finally {
                                    bitmap.recycle()
                                }
                                RadarFrame(
                                    pixels.time,
                                    member.issue,
                                    member.lead,
                                    pixels.grid,
                                    dest.path,
                                )
                            },
                            now = AppPlatform::now,
                            running = { !closed.get() && radarGeneration.isCurrent(token) },
                            progress = { value ->
                                postRadar(token) {
                                    radarProgress = value
                                    changed()
                                }
                            },
                            publish = { sequence ->
                                postRadar(token) {
                                    radar =
                                        radar.copy(
                                            data = sequence,
                                            state =
                                                issueFreshness(
                                                    sequence.issue,
                                                    AppPlatform.now(),
                                                    RADAR_MAX_AGE,
                                                    online,
                                                    sequence.historyComplete,
                                                ),
                                            refreshing = true,
                                        )
                                    changed()
                                }
                            },
                        )
                        .load(saved)
                if (!radarGeneration.isCurrent(token) || closed.get()) return@execute
                // A cache write failure must not discard successfully received radar frames.
                runCatching { RadarCache.save(directory, result) }
                val keep = result.sequence.frames.map { File(it.file).name }.toSet()
                directory
                    .listFiles()
                    ?.filter {
                        it.name.startsWith("rv-") &&
                            it.name.endsWith(".png") &&
                            it.name !in keep &&
                            AppPlatform.now() - it.lastModified() > 3_600_000
                    }
                    ?.forEach { it.delete() }
                postRadar(token) {
                    radarBusy = false
                    val sequence = result.sequence
                    val state =
                        issueFreshness(
                            sequence.issue,
                            AppPlatform.now(),
                            RADAR_MAX_AGE,
                            online,
                            sequence.historyComplete,
                        )
                    radar =
                        Reading(
                            sequence,
                            state,
                            result.downloadedAt,
                            when {
                                !sequence.historyComplete ->
                                    "Some observed frames unavailable · gaps are marked"
                                state == Freshness.STALE ->
                                    "DWD radar issue is old or connection is offline"
                                else -> null
                            },
                        )
                    changed()
                }
            } catch (error: Exception) {
                postRadar(token) {
                    radarBusy = false
                    radarProgress = radarProgress.copy(phase = RadarPhase.FAILED)
                    radar = radar.failed("Refresh failed · ${error.message ?: "connection error"}")
                    changed()
                }
            }
        }
    }

    fun clearRadarCache() {
        if (closed.get() || radarProgress.phase == RadarPhase.CLEARING) return
        val token = radarGeneration.next()
        radarBusy = true
        radarProgress = RadarProgress(phase = RadarPhase.CLEARING)
        radar = radar.copy(refreshing = true, message = "Clearing radar cache")
        changed()
        // Serialize deletion after the cancelled worker, so it cannot recreate deleted files.
        radarWorker.execute {
            val error = runCatching { RadarCache.clear(directory) }.exceptionOrNull()
            postRadar(token) {
                radarBusy = false
                radarProgress =
                    RadarProgress(phase = if (error == null) RadarPhase.IDLE else RadarPhase.FAILED)
                radar =
                    if (error == null) Reading(message = "Radar cache cleared")
                    else radar.failed("Radar cache could not be cleared")
                changed()
            }
        }
    }

    fun age() {
        val now = AppPlatform.now()
        forecast =
            forecast.aged(
                forecast.data?.issue,
                now,
                FORECAST_MAX_AGE,
                "DWD forecast issue is over 9 hours old",
            )
        radar =
            radar.aged(
                radar.data?.issue,
                now,
                RADAR_MAX_AGE,
                "DWD radar issue is over 20 minutes old",
            )
    }

    fun close() {
        closed.set(true)
        workers.shutdownNow()
        radarGeneration.next()
        radarWorker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }
}
