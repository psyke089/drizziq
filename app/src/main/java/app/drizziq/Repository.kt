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
        "Drizziq/${BuildConfig.VERSION_NAME} (Android; https://github.com/psyke089/drizziq)"
    const val WEATHER = "https://opendata.dwd.de/weather/"

    internal fun allowed(url: URL): Boolean =
        url.protocol == "https" &&
            (Geocoding.allowed(url) ||
                url.host == "tile.openstreetmap.org" ||
                (url.host == "opendata.dwd.de" && url.path.startsWith("/weather/")))

    private fun checked(url: URL) {
        require(allowed(url)) { "Unapproved data endpoint" }
    }

    fun bytes(address: String, limit: Int = 32000000): ByteArray = downloads.track {
        request(address, limit)
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
                    read(c, limit, progress),
                    responseEtag,
                    responseModified,
                )
            } finally {
                c.disconnect()
            }
        }
        throw IllegalArgumentException("Too many redirects")
    }

    /**
     * Conditional GET that hands the body to [consume] while it is still arriving, so a caller can
     * act on early entries without buffering the whole response.
     */
    internal fun conditionalStream(
        address: String,
        limit: Int = 32000000,
        etag: String? = null,
        lastModified: String? = null,
        progress: ((Long, Long?) -> Unit)? = null,
        open: (URL) -> HttpURLConnection = AppPlatform::open,
        consume: (InputStream) -> Unit,
    ): StreamReply = downloads.track {
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
                    return@track StreamReply.NotModified(
                        responseEtag ?: etag,
                        responseModified ?: lastModified,
                    )
                }
                if (status in 300..399) {
                    url = URL(url, c.getHeaderField("Location") ?: error("Empty redirect"))
                    return@repeat
                }
                require(status == 200) { "Server returned HTTP $status" }
                val total = c.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
                progress?.invoke(0, total)
                val body = LimitedStream(c.inputStream, limit.toLong(), total, progress)
                try {
                    consume(body)
                } finally {
                    body.close()
                }
                progress?.invoke(body.count, total)
                return@track StreamReply.Ok(responseEtag, responseModified)
            } finally {
                c.disconnect()
            }
        }
        throw IllegalArgumentException("Too many redirects")
    }

    private class LimitedStream(
        input: InputStream,
        private val limit: Long,
        private val total: Long?,
        private val progress: ((Long, Long?) -> Unit)?,
    ) : FilterInputStream(input) {
        var count = 0L
            private set

        private var reported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) advance(it) }

        private fun advance(bytes: Int) {
            count += bytes
            require(count <= limit) { "Data response too large" }
            if (count - reported >= 65536 || (total != null && count == total)) {
                reported = count
                progress?.invoke(count, total)
            }
        }
    }

    private fun request(address: String, limit: Int): ByteArray =
        get(address) { connection -> read(connection, limit) }

    /** Read only the observed first member; closing the stream avoids fetching old forecasts. */
    internal fun firstTarEntry(
        address: String,
        open: (URL) -> HttpURLConnection = AppPlatform::open,
    ): Tar.Entry = downloads.track {
        get(address, open = open) { connection ->
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
        open: (URL) -> HttpURLConnection = AppPlatform::open,
        consume: (HttpURLConnection) -> T,
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
                }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("Empty redirect"))
                    return@repeat
                }
                require(status == 200) {
                    "Server returned HTTP $status"
                }
                return consume(connection)
            } finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }

    private fun read(
        c: HttpURLConnection,
        limit: Int,
        progress: ((Long, Long?) -> Unit)? = null,
    ): ByteArray =
        c.inputStream.use { input ->
            val total = c.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
            progress?.invoke(0, total)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(out.size() + n <= limit) { "Data response too large" }
                out.write(buffer, 0, n)
                progress?.invoke(out.size().toLong(), total)
            }
            out.toByteArray()
        }
}

/** Result of [Http.conditional]; a 304 deliberately carries no body. */
internal sealed class HttpReply {
    class Ok(val bytes: ByteArray, val etag: String?, val lastModified: String?) : HttpReply()

    class NotModified(val etag: String?, val lastModified: String?) : HttpReply()
}

/** Result of [Http.conditionalStream]; the body was consumed before this value was produced. */
internal sealed class StreamReply {
    class Ok(val etag: String?, val lastModified: String?) : StreamReply()

    class NotModified(val etag: String?, val lastModified: String?) : StreamReply()
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

    private data class WarningSnapshot(
        val place: Place,
        val warnings: List<DwdWarning>,
        val fetched: Long,
    )

    private var warningSnapshot: WarningSnapshot? = null
    private var warningRequest: Pair<Place, Long>? = null
    private var warningBusy = false
    // Access the nationwide archive only on the serial warning worker.
    private val warningWorker = Executors.newSingleThreadExecutor()
    private var warningArchive: Pair<ByteArray, Long>? = null

    internal fun warnings(now: Long): List<DwdWarning> =
        warningSnapshot
            ?.takeIf {
                it.place == place && now - it.fetched in 0..15 * 60_000L
            }
            ?.warnings
            .orEmpty()
            .filter { now < it.expires }

    internal fun refreshWarnings(now: Long, force: Boolean = false) {
        if (!online || warningBusy || closed.get()) return
        val selected = place
        if (
            !force &&
                warningRequest?.let {
                    it.first == selected && now - it.second in 0 until 5 * 60_000L
                } == true
        )
            return
        warningRequest = selected to now
        warningBusy = true
        warningWorker.execute {
            val result = runCatching {
                val (bytes, fetched) =
                    warningArchive?.takeIf {
                        !force && now - it.second in 0 until 5 * 60_000L
                    }
                        ?: (Http.bytes(
                            Http.WEATHER + DwdWarnings.PATH,
                            DwdWarnings.DOWNLOAD_LIMIT,
                        ) to AppPlatform.now())
                val alerts = DwdWarnings.at(bytes, selected, AppPlatform.now())
                warningArchive = bytes to fetched
                WarningSnapshot(selected, alerts, fetched)
            }
            post {
                warningBusy = false
                if (place == selected) result.getOrNull()?.let { warningSnapshot = it }
                changed()
            }
        }
    }

    private var radarBusy = false
    private val radarWorker = Executors.newSingleThreadExecutor()
    private val radarGeneration = RequestGeneration()
    private val frameBitmaps = LinkedHashMap<String, Bitmap>()
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

    /**
     * Hands the bitmap produced for a frame to the UI so the PNG is not decoded a second time.
     * Evicted entries are only dropped: the view may still be drawing that bitmap.
     */
    fun takeRadarBitmap(path: String): Bitmap? =
        synchronized(frameBitmaps) { frameBitmaps.remove(path) }

    private fun keepBitmap(path: String, bitmap: Bitmap) {
        synchronized(frameBitmaps) {
            frameBitmaps[path] = bitmap
            while (frameBitmaps.size > 3) frameBitmaps.remove(frameBitmaps.keys.first())
        }
    }

    fun connection(): Boolean {
        online = AppPlatform.online(context)
        if (!online) {
            forecast = forecast.offline("forecast")
            radar = radar.offline("radar")
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
                            if (!online && reading.data != null) reading.offline("forecast")
                            else reading
                        changed()
                    }
                }
            }
        }
    }

    fun refreshForecast() {
        if (forecast.refreshing) return
        select(place)
    }

    private fun postRadar(token: Int, action: () -> Unit) {
        post { if (radarGeneration.isCurrent(token)) action() }
    }

    fun showRadar() {
        if (radarBusy || closed.get()) return
        if (radar.data == null) loadRadarCache()
        else if (online && radar.needsRefresh(AppPlatform.now(), 300_000L)) refreshRadar()
    }

    private fun readRadarCache(): RadarSnapshot? {
        val read = RadarCache.read(directory)
        read.removeInvalid()
        return read.snapshot
    }

    private fun loadRadarCache() {
        radarBusy = true
        val token = radarGeneration.next()
        radarWorker.execute {
            val saved = runCatching { readRadarCache() }.getOrNull()
            postRadar(token) {
                if (saved != null) {
                    radar =
                        Reading(
                            saved.sequence,
                            if (online) Freshness.CACHED else Freshness.STALE,
                            saved.downloadedAt,
                            if (online) "Saved radar · checking DWD"
                            else "Offline · showing saved radar",
                        )
                    changed()
                }
                radarBusy = false
                if (online) refreshRadar()
                else {
                    radar = radar.offline("radar")
                    radarProgress = RadarProgress()
                    changed()
                }
            }
        }
    }

    /**
     * Cancels in-flight radar work when the window stops without touching saved frames; opening the
     * radar again starts a fresh refresh.
     */
    fun pauseRadar() {
        if (!radarBusy) return
        radarGeneration.next()
        radarBusy = false
        radarProgress = radarProgress.copy(phase = RadarPhase.IDLE)
        radar = radar.copy(refreshing = false)
        changed()
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
                val saved = runCatching { readRadarCache() }.getOrNull()
                val base = "${Http.WEATHER}radar/composite/rv/"
                var lastProgressPhase: RadarPhase? = null
                var lastProgressPost = 0L
                val result =
                    RadarRefresh(
                            latest = { cached, progress, consume ->
                                Http.conditionalStream(
                                    base + "composite_rv_LATEST.tar",
                                    etag = cached?.etag,
                                    lastModified = cached?.lastModified,
                                    progress = progress,
                                    consume = consume,
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
                                } catch (error: Exception) {
                                    bitmap.recycle()
                                    throw error
                                }
                                keepBitmap(dest.path, bitmap)
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
                                val at = AppPlatform.now()
                                val urgent =
                                    value.phase != lastProgressPhase ||
                                        value.phase != RadarPhase.DOWNLOAD
                                if (urgent || at - lastProgressPost >= 250) {
                                    lastProgressPhase = value.phase
                                    lastProgressPost = at
                                    postRadar(token) {
                                        radarProgress = value
                                        changed()
                                    }
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
                // A cache write failure must not discard successfully received radar frames, and
                // pruning must wait until the manifest that references them is safely replaced.
                val manifest = runCatching { RadarCache.save(directory, result) }
                if (manifest.isSuccess) {
                    val keep = result.sequence.frames.map { File(it.file).name }.toSet()
                    runCatching { RadarCache.prune(directory, keep) }
                }
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
        synchronized(frameBitmaps) { frameBitmaps.clear() }
        workers.shutdownNow()
        warningWorker.shutdownNow()
        radarGeneration.next()
        radarWorker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }
}
