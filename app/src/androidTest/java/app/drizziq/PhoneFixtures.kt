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
    @Volatile var mapFailure = false
    @Volatile var mapGate: java.util.concurrent.CountDownLatch? = null
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
        mapFailure = false
        mapGate = null
        forecastGate = null
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
                url.host == "maps.dwd.de" && url.query.contains("GetCapabilities", true) -> {
                    bytes =
                        """<WMS_Capabilities xmlns="http://www.opengis.net/wms"><Capability><Layer>
                    <Layer><Name>Icon_reg025_fd_sl_T2M</Name><Dimension name="REFERENCE_TIME">2026-10-22T12:00:00Z,2026-10-22T18:00:00Z</Dimension><Dimension name="time">2026-10-22T00:00:00Z/2026-10-23T12:00:00Z/PT1H</Dimension></Layer>
                    <Layer><Name>Satellite_worldmosaic_3km_world_ir108_3h</Name><Dimension name="time">2026-10-22T00:00:00Z/2026-10-22T18:00:00Z/PT3H</Dimension></Layer>
                    </Layer></Capability></WMS_Capabilities>"""
                            .toByteArray()
                    status = 200
                }
                url.host == "view.eumetsat.int" && url.query.contains("GetCapabilities", true) -> {
                    bytes =
                        """<WMS_Capabilities xmlns="http://www.opengis.net/wms"><Capability><Layer><Layer><Name>mtg_fd:rgb_geocolour</Name><Dimension name="time">2024-09-23T00:00:00Z/2026-10-22T20:00:00Z/PT10M</Dimension></Layer></Layer></Capability></WMS_Capabilities>"""
                            .toByteArray()
                    status = 200
                }
                (url.host == "maps.dwd.de" || url.host == "view.eumetsat.int") &&
                    url.query.contains("GetMap", true) -> {
                    mapGate?.await(15, java.util.concurrent.TimeUnit.SECONDS)
                    val params =
                        url.query.split('&').associate {
                            it.substringBefore('=').uppercase() to
                                java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
                        }
                    val image =
                        android.graphics.Bitmap.createBitmap(
                            params.getValue("WIDTH").toInt(),
                            params.getValue("HEIGHT").toInt(),
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                    image.eraseColor(
                        if (url.host == "maps.dwd.de") 0xFFFFFFB3.toInt() else 0xFF30495F.toInt()
                    )
                    val encoded = java.io.ByteArrayOutputStream()
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, encoded)
                    image.recycle()
                    bytes = encoded.toByteArray()
                    status = if (mapFailure) 503 else 200
                }
                url.host == "opendata.dwd.de" && url.path.endsWith(".kmz") -> {
                    forecastGate?.await(15, java.util.concurrent.TimeUnit.SECONDS)
                    bytes = kmz
                    status = if (forecastFailure) 503 else 200
                }
                url.host == "opendata.dwd.de" &&
                    url.path.startsWith("/weather/radar/composite/rv/") -> {
                    // Radar presentation is supplied by a versioned local bitmap fixture, not
                    // public HTTP.
                    bytes = byteArrayOf()
                    status = 503
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

                override fun getHeaderField(name: String?): String? = null
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
                val file = File(dir, "rv-fixture-$step.png")
                if (!file.isFile) frameFile.copyTo(file)
                RadarFrame(
                    issue + step * 300000L,
                    issue,
                    maxOf(0, step * 5),
                    decoded.grid,
                    file.path,
                )
            }
        val sequence = RadarSequence(frames, issue, true)
        File(dir, "radar.json").writeText(RadarCache.encode(sequence, true))
    }
}
