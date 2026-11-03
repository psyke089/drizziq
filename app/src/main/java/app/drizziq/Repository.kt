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
                (url.host == "opendata.dwd.de" && url.path.startsWith("/weather/")) ||
                (url.host == "maps.dwd.de" && url.path == "/geoserver/dwd/wms"))

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
        open: (URL) -> HttpURLConnection = AppPlatform::open,
    ): HttpReply = downloads.track {
        conditionalRequest(address, limit, etag, lastModified, open)
    }

    private fun conditionalRequest(
        address: String,
        limit: Int,
        etag: String?,
        lastModified: String?,
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
                    read(c, limit, range = null, status = status),
                    responseEtag,
                    responseModified,
                )
            } finally {
                c.disconnect()
            }
        }
        throw IllegalArgumentException("Too many redirects")
    }

    private fun request(address: String, limit: Int, range: String?): ByteArray {
        var url = URL(address)
        repeat(4) {
            checked(url)
            val c =
                AppPlatform.open(url).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = false
                    setRequestProperty(
                        "User-Agent",
                        USER_AGENT,
                    )
                    if (range != null) setRequestProperty("Range", "bytes=$range")
                }
            try {
                val status = c.responseCode
                if (status in 300..399) {
                    url = URL(url, c.getHeaderField("Location") ?: error("Empty redirect"))
                    return@repeat
                }
                require(status == 200 || status == 206) { "Server returned HTTP $status" }
                return read(c, limit, range, status)
            } finally {
                c.disconnect()
            }
        }
        error("Too many redirects")
    }

    private fun read(
        c: HttpURLConnection,
        limit: Int,
        range: String?,
        status: Int,
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

    private fun decodesRadar(file: File): Boolean {
        val image = android.graphics.BitmapFactory.decodeFile(file.path) ?: return false
        image.recycle()
        return true
    }

    private fun loadRadarCache() {
        radarBusy = true
        workers.execute {
            runCatching {
                val manifest = File(directory, "radar.json")
                val time = manifest.lastModified()
                val cached = RadarCache.read(directory)
                cached.invalid.forEach { it.delete() }
                val sequence =
                    cached.sequence
                        ?: run {
                            manifest.delete()
                            error("Radar cache is invalid or incompatible")
                        }
                val undecodable = sequence.frames.map { File(it.file) }.filterNot(::decodesRadar)
                if (undecodable.isNotEmpty()) {
                    undecodable.forEach { it.delete() }
                    manifest.delete()
                    error("Radar cache contains an undecodable image")
                }
                Triple(sequence, time, manifest)
            }
                .getOrNull()
                ?.let { (seq, time, _) ->
                    post {
                        radar =
                            Reading(
                                seq,
                                if (online) Freshness.CACHED else Freshness.STALE,
                                time,
                                "Saved radar · checking DWD",
                            )
                        changed()
                    }
                }
            post {
                radarBusy = false
                refreshRadar()
            }
        }
    }

    fun refreshRadar() {
        if (radarBusy || closed.get()) return
        radarBusy = true
        radar =
            radar.copy(
                state = if (radar.data == null) Freshness.LOADING else radar.state,
                refreshing = true,
                message = "Receiving DWD radar",
            )
        changed()
        workers.execute {
            try {
                check(online) { "Offline" }
                AtomicFiles.cleanup(directory, AppPlatform.now())
                val frames = mutableListOf<RadarFrame>()
                fun decode(entry: Tar.Entry): RadarFrame {
                    val m =
                        Regex("composite_rv_(\\d{8})_(\\d{4})_(\\d{3})-hd5").matchEntire(entry.name)
                            ?: error("Unexpected DWD archive entry")
                    val issue =
                        java.text
                            .SimpleDateFormat("yyyyMMddHHmm", java.util.Locale.US)
                            .apply { timeZone = Times.utc }
                            .parse(m.groupValues[1] + m.groupValues[2])!!
                            .time
                    val lead = m.groupValues[3].toInt()
                    val dest = File(directory, "rv-$issue-$lead.png")
                    val pixels = Odim.decode(entry.bytes)
                    require(pixels.time == issue + lead * 60000L) { "DWD frame time mismatch" }
                    if (!RadarCache.reusable(dest, pixels.grid) || !decodesRadar(dest)) {
                        dest.delete()
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
                    }
                    return RadarFrame(pixels.time, issue, lead, pixels.grid, dest.path)
                }
                val base = "${Http.WEATHER}radar/composite/rv/"
                val archive = Http.bytes(base + "composite_rv_LATEST.tar")
                Tar.read(archive.inputStream()) { entry ->
                    if (entry.name.endsWith("-hd5")) frames.add(decode(entry))
                }
                require(frames.any { it.lead == 0 } && frames.any { it.lead > 0 }) {
                    "DWD radar archive has no complete timeline"
                }
                val issue = frames.first().issue
                require(frames.all { it.issue == issue }) { "Mixed radar issue times" }
                var historyComplete = true
                // Only the observed member is fetched from each preceding archive.
                for (back in 12 downTo 1) {
                    if (closed.get()) return@execute
                    if (!online) {
                        historyComplete = false
                        break
                    }
                    try {
                        val name =
                            "composite_rv_${Times.format(issue-back*300000L,"yyyyMMdd_HHmm")}.tar"
                        val header = Http.bytes(base + name, 512, "0-511")
                        val length = Tar.size(header)
                        val entryName =
                            header.copyOfRange(0, 100).toString(Charsets.US_ASCII).trimEnd('\u0000')
                        require(entryName.endsWith("_000-hd5"))
                        val observed =
                            decode(
                                Tar.Entry(
                                    entryName,
                                    Http.bytes(base + name, 24000000, "512-${511+length}"),
                                )
                            )
                        require(observed.time == issue - back * 300000L)
                        frames.add(observed)
                    } catch (_: Exception) {
                        historyComplete = false
                    }
                }
                val sequence =
                    RadarSequence(
                        frames.sortedBy { it.time }.distinctBy { it.time },
                        issue,
                        historyComplete,
                    )
                AtomicFiles.write(
                    File(directory, "radar.json"),
                    RadarCache.encode(sequence, historyComplete).toByteArray(),
                )
                val keep = sequence.frames.map { File(it.file).name }.toSet()
                directory
                    .listFiles()
                    ?.filter {
                        it.name.startsWith("rv-") &&
                            it.name !in keep &&
                            AppPlatform.now() - it.lastModified() > 3600000
                    }
                    ?.forEach { it.delete() }
                post {
                    radarBusy = false
                    val stale =
                        issueFreshness(
                            issue,
                            AppPlatform.now(),
                            RADAR_MAX_AGE,
                            online,
                            historyComplete,
                        ) == Freshness.STALE
                    radar =
                        Reading(
                            sequence,
                            if (stale) Freshness.STALE else Freshness.LIVE,
                            AppPlatform.now(),
                            when {
                                !historyComplete ->
                                    "Some observed frames unavailable · gaps are marked"
                                stale -> "DWD radar issue is old or connection is offline"
                                else -> null
                            },
                        )
                    changed()
                }
            } catch (e: Exception) {
                post {
                    radarBusy = false
                    radar = radar.failed("Refresh failed · ${e.message ?: "connection error"}")
                    changed()
                }
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
        handler.removeCallbacksAndMessages(null)
    }
}
