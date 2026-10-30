package app.drizziq

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

object Http {
    const val WEATHER = "https://opendata.dwd.de/weather/"

    private fun checked(url: URL) {
        require(
            url.protocol == "https" &&
                (url.host == "tile.openstreetmap.org" ||
                    (url.host == "opendata.dwd.de" && url.path.startsWith("/weather/")))
        ) {
            "Unapproved data endpoint"
        }
    }

    fun bytes(address: String, limit: Int = 32000000, range: String? = null): ByteArray {
        var url = URL(address)
        repeat(4) {
            checked(url)
            val c =
                (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = false
                    setRequestProperty(
                        "User-Agent",
                        "Drizziq/1.1.0 (Android; https://codeberg.org/k4r/drizziq)",
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
                // Servers may ignore Range. Select the requested bytes locally in that case.
                val start = range?.substringBefore('-')?.toInt() ?: 0
                val length = range?.substringAfter('-')?.toInt()?.let { it - start + 1 }
                return c.inputStream.use { input ->
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
            } finally {
                c.disconnect()
            }
        }
        error("Too many redirects")
    }
}

class PlaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    private fun json(p: Place) =
        JSONObject().put("label", p.label).put("lat", p.lat).put("lon", p.lon).put("zone", p.zone)

    private fun place(j: JSONObject) =
        Place(
            j.getString("label"),
            j.getDouble("lat"),
            j.getDouble("lon"),
            j.optString("zone", "Europe/Berlin"),
        )

    fun selected(): Place = runCatching {
        place(JSONObject(prefs.getString("selected", null)!!))
    }
        .getOrDefault(Place.MUNICH)

    fun select(p: Place) {
        check(prefs.edit().putString("selected", json(p).toString()).commit()) {
            "Location could not be saved"
        }
    }

    fun favourites(): List<Place> = runCatching {
        val a = JSONArray(prefs.getString("favourites", "[]"))
        (0 until a.length()).map { place(a.getJSONObject(it)) }
    }
        .getOrDefault(emptyList())

    fun save(p: Place) {
        val all = favourites().filterNot { it.lat == p.lat && it.lon == p.lon } + p
        write(all)
    }

    fun delete(p: Place) = write(favourites().filterNot { it == p })

    private fun write(all: List<Place>) {
        val a = JSONArray()
        all.forEach { a.put(json(it)) }
        check(prefs.edit().putString("favourites", a.toString()).commit()) {
            "Favourites could not be saved"
        }
    }
}

class Repository(private val context: Context, private val changed: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val closed = AtomicBoolean(false)
    private val directory = File(context.filesDir, "data").apply { mkdirs() }
    private val stations by lazy {
        context.assets.open("stations.tsv").bufferedReader().useLines { lines ->
            lines
                .map { line ->
                    val p = line.split('\t')
                    Station(p[0], p[1], p[2].toDouble(), p[3].toDouble(), p[4].toDoubleOrNull())
                }
                .toList()
        }
    }
    private val generation = RequestGeneration()
    private var radarBusy = false
    var place = Place.MUNICH
        private set

    var forecast = Reading<Forecast>()
        private set

    var radar = Reading<RadarSequence>()
        private set

    @Volatile
    var online = true
        private set

    private fun post(action: () -> Unit) {
        handler.post { if (!closed.get()) action() }
    }

    private fun atomic(file: File, bytes: ByteArray) {
        val temp = File(file.path + ".part")
        temp.writeBytes(bytes)
        check(temp.renameTo(file)) { "Cache write failed" }
    }

    fun connection(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        online =
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!online) {
            forecast = forecast.failed("Offline · last successful data retained")
            radar = radar.failed("Offline · last successful frames retained")
        }
        changed()
        return online
    }

    fun select(p: Place) {
        val retain = place == p && forecast.data != null
        place = p
        val token = generation.next()
        forecast =
            if (retain) forecast.copy(refreshing = true, message = "Checking DWD")
            else Reading(message = "Locating nearest DWD station", refreshing = true)
        changed()
        workers.execute {
            try {
                val station = stations.minBy { it.distance(p) }
                val cache = File(directory, "forecast-${station.id}.kmz")
                if (cache.exists())
                    runCatching { Mosmix.parseKmz(cache.inputStream()) }
                        .getOrNull()
                        ?.let { saved ->
                            post {
                                if (generation.isCurrent(token)) {
                                    forecast =
                                        Reading(
                                            saved,
                                            if (online) Freshness.CACHED else Freshness.STALE,
                                            cache.lastModified(),
                                            "Saved forecast · checking DWD",
                                            true,
                                        )
                                    changed()
                                }
                            }
                        }
                loadForecast(station, cache, token)
            } catch (e: Exception) {
                failForecast(token, e)
            }
        }
        if (radar.data == null && !radarBusy) loadRadarCache()
    }

    private fun failForecast(token: Int, e: Exception) = post {
        if (generation.isCurrent(token)) {
            forecast = forecast.failed("Refresh failed · ${e.message ?: "connection error"}")
            changed()
        }
    }

    private fun loadForecast(station: Station, cache: File, token: Int) {
        check(online) { "Offline" }
        val bytes =
            Http.bytes(
                "${Http.WEATHER}local_forecasts/mos/MOSMIX_L/single_stations/${station.id}/kml/MOSMIX_L_LATEST_${station.id}.kmz",
                3000000,
            )
        val result = Mosmix.parseKmz(bytes.inputStream())
        require(result.station.id == station.id) { "Wrong forecast station" }
        atomic(cache, bytes)
        post {
            if (generation.isCurrent(token)) {
                val state =
                    issueFreshness(
                        result.issue,
                        System.currentTimeMillis(),
                        FORECAST_MAX_AGE,
                        online,
                    )
                forecast =
                    Reading(
                        result,
                        state,
                        System.currentTimeMillis(),
                        if (state == Freshness.STALE) "DWD issue is old or connection is offline"
                        else null,
                    )
                changed()
            }
        }
    }

    fun refreshForecast() {
        if (forecast.refreshing) return
        select(place)
    }

    private fun loadRadarCache() {
        radarBusy = true
        workers.execute {
            runCatching {
                val file = File(directory, "radar.json")
                val j = JSONObject(file.readText())
                val a = j.getJSONArray("frames")
                val frames =
                    (0 until a.length()).map { i ->
                        val f = a.getJSONObject(i)
                        val grid =
                            RadarGrid(
                                f.getInt("w"),
                                f.getInt("h"),
                                f.getDouble("left"),
                                f.getDouble("top"),
                                f.getDouble("dx"),
                                f.getDouble("dy"),
                            )
                        val path = File(directory, f.getString("file"))
                        require(path.exists())
                        RadarFrame(
                            f.getLong("time"),
                            f.getLong("issue"),
                            f.getInt("lead"),
                            grid,
                            path.path,
                        )
                    }
                require(frames.isNotEmpty())
                Triple(
                    RadarSequence(frames, j.getLong("issue"), j.getBoolean("historyComplete")),
                    file.lastModified(),
                    file,
                )
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
                    if (!dest.exists()) {
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
                            atomic(dest, out.toByteArray())
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
                val a = JSONArray()
                sequence.frames.forEach { f ->
                    a.put(
                        JSONObject()
                            .put("time", f.time)
                            .put("issue", f.issue)
                            .put("lead", f.lead)
                            .put("file", File(f.file).name)
                            .put("w", f.grid.width)
                            .put("h", f.grid.height)
                            .put("left", f.grid.left)
                            .put("top", f.grid.top)
                            .put("dx", f.grid.dx)
                            .put("dy", f.grid.dy)
                    )
                }
                atomic(
                    File(directory, "radar.json"),
                    JSONObject()
                        .put("issue", issue)
                        .put("historyComplete", historyComplete)
                        .put("frames", a)
                        .toString()
                        .toByteArray(),
                )
                val keep = sequence.frames.map { File(it.file).name }.toSet()
                directory
                    .listFiles()
                    ?.filter {
                        it.name.startsWith("rv-") &&
                            it.name !in keep &&
                            System.currentTimeMillis() - it.lastModified() > 3600000
                    }
                    ?.forEach { it.delete() }
                post {
                    radarBusy = false
                    val stale =
                        issueFreshness(
                            issue,
                            System.currentTimeMillis(),
                            RADAR_MAX_AGE,
                            online,
                            historyComplete,
                        ) == Freshness.STALE
                    radar =
                        Reading(
                            sequence,
                            if (stale) Freshness.STALE else Freshness.LIVE,
                            System.currentTimeMillis(),
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
        val now = System.currentTimeMillis()
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
