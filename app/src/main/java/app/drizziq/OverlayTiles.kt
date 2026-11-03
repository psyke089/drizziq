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
    var notice: String? = null
        private set

    var selection: OverlaySelection? = null
        private set

    private val capFile = File(root, "capabilities.xml")

    init {
        workers.execute {
            stores.values.forEach { it.prune() }
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
        selection = capabilities?.let {
            OverlayModel.select(overlay, target, AppPlatform.now(), it)
        }
        if (previous != selection) {
            generation++
            waiting.clear()
        }
        notice =
            when {
                capabilities == null && !online -> "Overlay offline"
                capabilities == null -> "${overlay.name} · loading…"
                selection == null -> "No overlay for this time"
                !online -> "Cached overlay"
                else -> null
            }
        changed()
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
                val ttl = if (active.overlay == Overlay.CLOUDS) 6 * 3600000L else 2 * 3600000L
                if (online && (result == null || AppPlatform.now() - file.lastModified() > ttl)) {
                    val bytes = Http.bytes(OverlayModel.url(active, z, x, y), 2 * 1024 * 1024)
                    OverlayModel.serviceError(bytes)?.let { error(it) }
                    require(Png.dimensions(bytes) == (256 to 256)) { "Invalid overlay image" }
                    val image =
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("Invalid overlay image")
                    runCatching {
                        AtomicFiles.write(file, bytes)
                        store.wrote(file)
                    }
                    result = image
                    wasCached = false
                }
            } catch (e: Exception) {
                error = e.message ?: "DWD request failed"
            }
            val bitmap = result
            handler.post {
                if (!disposed && token == generation && selection == active) {
                    waiting.remove(key)
                    if (bitmap != null) memory.put(key, bitmap)
                    else failed[key] = AppPlatform.now() + if (error != null) 300000 else 60000
                    notice =
                        when {
                            bitmap != null && (!online || wasCached && error != null) ->
                                "Cached overlay"
                            bitmap == null && !online -> "Overlay offline"
                            error != null -> "Overlay unavailable · $error"
                            else -> null
                        }
                    changed()
                }
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
