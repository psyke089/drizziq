package app.drizziq

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** All fixture state is private to instrumentation, including the clock and every HTTP response. */
internal object PhoneFixtures {
    val now = Times.parse("2026-10-22T20:05:00Z")
    private lateinit var isolated: File
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    val unexpected = Collections.synchronizedList(mutableListOf<String>())
    val requests = AtomicInteger()
    @Volatile var radarArchive: ByteArray? = null
    @Volatile var radarHistoryGate: java.util.concurrent.CountDownLatch? = null
    @Volatile var forecastFailure = false
    @Volatile var forecastGate: java.util.concurrent.CountDownLatch? = null

    fun context(base: Context): Context =
        object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(isolated, "files").apply { mkdirs() }

            override fun getCacheDir(): File = File(isolated, "cache").apply { mkdirs() }

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                preferences.add(name)
                return base.getSharedPreferences("phone-fixture-$name", mode)
            }
        }

    fun install(base: Context, assets: AssetManager, preserve: Boolean) {
        isolated = File(base.cacheDir, "phone-fixture")
        if (!preserve) {
            isolated.deleteRecursively()
            for (name in
                listOf("places", "radar-viewport", "viewport-exercise", "location-permission")) {
                base.getSharedPreferences("phone-fixture-$name", 0).edit().clear().commit()
            }
        }
        isolated.mkdirs()
        unexpected.clear()
        requests.set(0)
        forecastFailure = false
        forecastGate = null
        radarArchive = null
        radarHistoryGate = null
        AppPlatform.clock = { now }
        AppPlatform.contexts = ::context
        AppPlatform.connected = true
        val kmz = assets.open("munich-20261022.kmz").use { it.readBytes() }
        val geocoding = assets.open("geocoding-v1-de-muenchen.json").use { it.readBytes() }
        val tile =
            android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
        tile.eraseColor(Hud.panel)
        val output = java.io.ByteArrayOutputStream()
        tile.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        tile.recycle()
        val png = output.toByteArray()
        seedRadar(context(base), assets)
        AppPlatform.connections = { url ->
            requests.incrementAndGet()
            val bytes: ByteArray
            val status: Int
            when {
                url.host == "tile.openstreetmap.org" -> {
                    bytes = png
                    status = 200
                }
                url.host == "geocoding-api.open-meteo.com" -> {
                    bytes =
                        if (url.query.contains("name=Berlin", true))
                            assets.open("geocoding-v1-de-berlin.json").use { it.readBytes() }
                        else geocoding
                    status = 200
                }
                url.host == "opendata.dwd.de" && url.path.endsWith(".kmz") -> {
                    forecastGate?.await(15, java.util.concurrent.TimeUnit.SECONDS)
                    bytes = kmz
                    status = if (forecastFailure) 503 else 200
                }
                url.host == "opendata.dwd.de" &&
                    url.path.startsWith("/weather/radar/composite/rv/") -> {
                    val archive = radarArchive
                    if (archive != null && url.path.endsWith("LATEST.tar")) {
                        bytes = archive
                        status = 200
                    } else {
                        radarHistoryGate?.await(20, java.util.concurrent.TimeUnit.SECONDS)
                        bytes = byteArrayOf()
                        status = 503
                    }
                }
                else -> {
                    unexpected.add("${url.host}${url.path}")
                    error("Unexpected fixture HTTP request: ${url.host}${url.path}")
                }
            }
            object : HttpURLConnection(url) {
                override fun connect() {}

                override fun disconnect() {}

                override fun usingProxy() = false

                override fun getResponseCode() = status

                override fun getInputStream() = ByteArrayInputStream(bytes)

                override fun getHeaderField(name: String?): String? =
                    if (name.equals("Content-Length", true)) bytes.size.toString() else null
            }
        }
    }

    private fun seedRadar(context: Context, assets: AssetManager) {
        val dir = File(context.filesDir, "data").apply { mkdirs() }
        val decoded = Odim.decode(assets.open("rv-observed-20261022.h5").use { it.readBytes() })
        val bitmap =
            android.graphics.Bitmap.createBitmap(
                decoded.colors,
                decoded.grid.width,
                decoded.grid.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        val frameFile = File(dir, "rv-fixture.png")
        frameFile.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        val issue = decoded.time
        // Synthetic player timeline with genuine decoded DWD bitmap pixels.
        val frames =
            (-12..24).map { step ->
                val origin = if (step < 0) issue + step * 300000L else issue
                val file = File(dir, "rv-$origin-${maxOf(0, step * 5)}.png")
                if (!file.isFile) frameFile.copyTo(file)
                RadarFrame(
                    issue + step * 300000L,
                    if (step < 0) issue + step * 300000L else issue,
                    maxOf(0, step * 5),
                    decoded.grid,
                    file.path,
                )
            }
        val sequence = RadarSequence(frames, issue, true)
        File(dir, "radar.json").writeText(RadarCache.encode(sequence, true))
    }
}
