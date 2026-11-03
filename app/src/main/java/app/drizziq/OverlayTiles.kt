package app.drizziq

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.pow

/** Visible WMS tiles only; errors never interfere with the underlying radar map. */
internal class OverlayTiles(private val context: Context, private val changed: () -> Unit) {
    private val workers = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private val memory =
        object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    private val waiting = mutableSetOf<String>()
    private val failed = BoundedAccessMap<String, Long>(256)
    private val root = File(context.cacheDir, "overlays").apply { mkdirs() }
    private val stores =
        Overlay.entries.associateWith { overlay ->
            CacheStore(
                File(root, overlay.name).apply { mkdirs() },
                32L * 1024 * 1024,
                { it.endsWith(".png") },
                indexName = "access.tsv",
            )
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var generation = 0
    private var disposed = false
    private var loadingCapabilities = false
    private var capabilitiesChecked = 0L
    private var capabilities: Map<Overlay, OverlayAvailability>? = null
    private var selected: Overlay? = null
    private var target = 0L
    private var online = true
    private val resolved = mutableMapOf<Overlay, Long>()
    private val resolveBackoff = mutableMapOf<Overlay, Long>()
    private val unavailable = mutableMapOf<Overlay, String>()
    private var resolving = false
    private var rendered = 0
    var notice: String? = null
        private set

    var selection: OverlaySelection? = null
        private set

    /** Layer the user asked for, even before its first tile resolves. */
    val layer: Overlay?
        get() = selected

    private val capFile = File(root, "capabilities.xml")

    init {
        workers.execute {
            stores.forEach { (overlay, store) ->
                store.prune { file, now -> now - file.lastModified() > overlay.ttl }
            }
            if (capFile.isFile)
                runCatching { OverlayModel.capabilities(capFile.readBytes()) }
                    .getOrNull()
                    ?.let { parsed ->
                        handler.post {
                            if (!disposed && capabilities == null) {
                                capabilities = parsed
                                capabilitiesChecked = capFile.lastModified()
                                update()
                            }
                        }
                    }
        }
    }

    fun set(overlay: Overlay?, frameTime: Long, connected: Boolean) {
        val changedInput = selected != overlay || target != frameTime || online != connected
        selected = overlay
        target = frameTime
        online = connected
        if (!changedInput) return
        update()
    }

    private fun update() {
        val overlay = selected
        if (overlay == null) {
            generation++
            selection = null
            notice = null
            changed()
            return
        }
        val previous = selection
        resolved[overlay]?.let {
            if (AppPlatform.now() - it > 30 * 60_000L) resolved.remove(overlay)
        }
        selection = capabilities?.let {
            OverlayModel.select(overlay, target, AppPlatform.now(), it, resolved[overlay])
        }
        if (previous != selection) {
            generation++
            waiting.clear()
            rendered = 0
        }
        notice =
            when {
                capabilities == null && !online -> "Overlay offline"
                capabilities == null -> "${overlay.name} · loading…"
                resolving -> "${overlay.name} · checking latest…"
                selection == null -> unavailable[overlay] ?: "No overlay for this time"
                !online -> "Cached overlay"
                else -> null
            }
        changed()
        if (online && capabilities != null && selection == null) resolve(overlay)
        if (
            online &&
                !loadingCapabilities &&
                (capabilitiesChecked == 0L || AppPlatform.now() - capabilitiesChecked > 3600000L)
        ) {
            loadingCapabilities = true
            capabilitiesChecked = AppPlatform.now()
            workers.execute {
                val result = runCatching {
                    val bytes =
                        Http.bytes(
                            "https://maps.dwd.de/geoserver/dwd/wms?service=WMS&version=1.3.0&request=GetCapabilities",
                            4 * 1024 * 1024,
                        )
                    val parsed = OverlayModel.capabilities(bytes)
                    require(parsed.isNotEmpty()) { "No advertised overlay dimensions" }
                    runCatching { AtomicFiles.write(capFile, bytes) }
                    parsed
                }
                handler.post {
                    if (!disposed) {
                        loadingCapabilities = false
                        if (result.isSuccess) {
                            capabilities = result.getOrThrow()
                            update()
                        } else {
                            notice =
                                "Overlay unavailable · ${result.exceptionOrNull()?.message ?: "DWD"}"
                            changed()
                        }
                    }
                }
            }
        }
    }

    /** DWD sometimes advertises timestamps it no longer serves; probe recent slots once. */
    private fun resolve(overlay: Overlay): Boolean {
        if (resolving || disposed || !online) return false
        val now = AppPlatform.now()
        if ((resolveBackoff[overlay] ?: 0L) > now) return false
        resolving = true
        notice = "${overlay.name} · checking latest…"
        changed()
        workers.execute {
            var found: OverlaySelection? = null
            for (time in OverlayModel.candidates(overlay, AppPlatform.now())) {
                if (disposed) break
                found =
                    runCatching {
                        val probe = OverlaySelection(overlay, time)
                        val bytes = Http.bytes(OverlayModel.url(probe, 5, 17, 10), 2 * 1024 * 1024)
                        if (OverlayModel.serviceError(bytes) != null) return@runCatching null
                        require(Png.dimensions(bytes) == (256 to 256))
                        probe
                    }
                        .getOrNull()
                if (found != null) break
            }
            val result = found
            handler.post {
                if (disposed) return@post
                resolving = false
                if (result != null) {
                    resolved[overlay] = result.time
                    resolveBackoff.remove(overlay)
                    unavailable.remove(overlay)
                    notice = null
                } else {
                    resolveBackoff[overlay] = AppPlatform.now() + 600_000L
                    unavailable[overlay] = "Overlay unavailable · DWD has no current data"
                    notice = unavailable[overlay]
                }
                update()
            }
        }
        return true
    }

    fun draw(
        canvas: Canvas,
        centerX: Double,
        centerY: Double,
        zoom: Double,
        width: Int,
        height: Int,
    ) {
        val active = selection ?: return
        val z = minOf(floor(zoom).toInt(), active.overlay.maxZoom)
        val count = 1 shl z
        val tileSize = 256.0 * 2.0.pow(zoom - z)
        val left = centerX * count - width / 2 / tileSize
        val top = centerY * count - height / 2 / tileSize
        paint.alpha = active.overlay.alpha
        for (iy in floor(top).toInt()..floor(top + height / tileSize).toInt()) {
            if (iy !in 0 until count) continue
            for (ix in floor(left).toInt()..floor(left + width / tileSize).toInt()) {
                val x = ((ix % count) + count) % count
                val key = OverlayModel.key(active, z, x, iy)
                val image = memory.get(key)
                if (image != null)
                    canvas.drawBitmap(
                        image,
                        null,
                        RectF(
                            ((ix - left) * tileSize).toFloat(),
                            ((iy - top) * tileSize).toFloat(),
                            ((ix - left + 1) * tileSize + 1).toFloat(),
                            ((iy - top + 1) * tileSize + 1).toFloat(),
                        ),
                        paint,
                    )
                else request(active, key, z, x, iy)
            }
        }
        paint.alpha = 255
    }

    /** DWD nodes disagree on available buckets, so retry time-specific rejections. */
    private fun download(active: OverlaySelection, z: Int, x: Int, y: Int): ByteArray {
        val url = OverlayModel.url(active, z, x, y)
        var attempt = 0
        while (true) {
            try {
                val bytes = Http.bytes(url, 2 * 1024 * 1024)
                val message = OverlayModel.serviceError(bytes)
                if (message == null) return bytes
                if (attempt >= 2 || !OverlayModel.timeUnavailable(message)) error(message)
            } catch (e: java.io.IOException) {
                if (attempt >= 1) throw e
            }
            attempt++
            Thread.sleep(300L * attempt)
        }
    }

    private fun errorSummary(error: String): String =
        when {
            error.contains("ssl", true) || error.contains("decrypt", true) -> "connection problem"
            else -> error.lineSequence().first().take(120)
        }

    private fun request(active: OverlaySelection, key: String, z: Int, x: Int, y: Int) {
        if (disposed || key in waiting || failed[key]?.let { AppPlatform.now() < it } == true)
            return
        waiting.add(key)
        val token = generation
        workers.execute {
            var error: String? = null
            var result: Bitmap? = null
            var wasCached = false
            val store = stores.getValue(active.overlay)
            val file = File(File(root, active.overlay.name), "$key.png")
            try {
                if (file.isFile) {
                    result =
                        BitmapFactory.decodeFile(file.path)?.takeIf {
                            it.width == 256 && it.height == 256
                        }
                    if (result == null) file.delete()
                    else {
                        store.read(file)
                        wasCached = true
                    }
                }
                val ttl = active.overlay.ttl
                if (online && (result == null || AppPlatform.now() - file.lastModified() > ttl)) {
                    val bytes = download(active, z, x, y)
                    require(Png.dimensions(bytes) == (256 to 256)) { "Invalid overlay image" }
                    val image =
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("Invalid overlay image")
                    runCatching {
                        AtomicFiles.write(file, bytes)
                        store.wrote(file) { candidate, now ->
                            now - candidate.lastModified() > active.overlay.ttl
                        }
                    }
                    result = image
                    wasCached = false
                }
            } catch (e: Exception) {
                error = e.message ?: "DWD request failed"
            }
            val bitmap = result
            handler.post {
                if (disposed) return@post
                val staleTime = bitmap == null && OverlayModel.timeUnavailable(error)
                if (token == generation && selection == active) {
                    waiting.remove(key)
                    if (bitmap != null) memory.put(key, bitmap)
                    else failed[key] = AppPlatform.now() + if (error != null) 300000 else 60000
                    notice =
                        when {
                            staleTime -> "${active.overlay.name} · checking latest…"
                            bitmap != null && (!online || wasCached && error != null) ->
                                "Cached overlay"
                            bitmap == null && !online -> "Overlay offline"
                            error != null -> "Overlay unavailable · ${errorSummary(error)}"
                            else -> null
                        }
                    changed()
                }
                if (staleTime && selection == active && !resolve(active.overlay))
                    notice =
                        unavailable[active.overlay]
                            ?: "Overlay unavailable · DWD has no current data"
                else if (staleTime) changed()
            }
        }
    }

    fun close() {
        disposed = true
        generation++
        workers.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        memory.evictAll()
    }
}
