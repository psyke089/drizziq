package app.drizziq

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.*

internal data class MapSnapshot(
    val selection: MapSelection,
    val area: MapArea,
    val bitmap: Bitmap,
    val receivedAt: Long,
)

/** Complete viewport images: an incomplete request never becomes a visible frame. */
internal class MapImages(
    context: Context,
    private val ready: (MapSnapshot) -> Unit,
    private val changed: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val workers =
        ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    private val root = File(context.cacheDir, "map-products-v1").apply { mkdirs() }
    private val stores =
        listOf(MapProduct.SATELLITE, MapProduct.TEMPERATURE).associateWith { p ->
            CacheStore(File(root, p.name), 32L * 1024 * 1024, { it.endsWith(".png") })
        }
    private val availability = ConcurrentHashMap<MapProduct, Pair<MapAvailability, Long>>()
    private val retained = mutableMapOf<MapProduct, MapSnapshot>()
    private val memory =
        object : LruCache<String, MapSnapshot>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: MapSnapshot) = value.bitmap.byteCount
        }
    private var job: Download? = null
    private var serial = 0
    private var closed = false
    var loading = false
        private set

    var notice: String? = null
        private set

    private var lastRequest: Triple<MapProduct, Long?, MapArea>? = null
    private var checkedAt = 0L
    private var lastOnline: Boolean? = null

    fun times(product: MapProduct): List<Long> =
        availability[product]?.first?.let { MapWms.times(product, it, AppPlatform.now()) }.orEmpty()

    fun remembered(product: MapProduct, visible: MapArea): MapSnapshot? =
        retained[product]?.takeIf { it.area.covers(visible) }

    /** Explicit targets are never replaced by a different valid time on failure. */
    fun request(
        product: MapProduct,
        target: Long?,
        area: MapArea,
        online: Boolean,
        force: Boolean = false,
    ) {
        if (closed) return
        val input = Triple(product, target, area)
        if (
            !force &&
                input == lastRequest &&
                online == lastOnline &&
                (loading || AppPlatform.now() - checkedAt < 60_000L)
        )
            return
        cancel()
        lastRequest = input
        lastOnline = online
        checkedAt = AppPlatform.now()
        val token = serial
        val known = availability[product]?.first
        val selection = known?.let {
            MapWms.select(product, target ?: AppPlatform.now(), AppPlatform.now(), it)
        }
        val cached = selection?.let { memory.get(MapWms.key(it, area)) }
        if (cached != null) {
            retained[product] = cached
            ready(cached)
            if (
                !force &&
                    availability[product]?.second?.let {
                        AppPlatform.now() - it < product.cadence
                    } == true
            ) {
                notice = if (online) null else "Saved image · offline"
                changed()
                return
            }
        }
        loading = true
        notice = "Loading ${product.title.lowercase()}…"
        changed()
        val download = Download()
        job = download
        download.future = workers.submit {
            var usedSaved = false
            val result = runCatching {
                download.check()
                val capFile = File(root, "${product.name}-capabilities.xml")
                var caps = availability[product]
                if (caps == null && capFile.isFile) {
                    caps =
                        runCatching {
                            MapWms.capabilities(
                                capFile.readBytes(),
                                product,
                                AppPlatform.now(),
                            ) to capFile.lastModified()
                        }
                            .getOrNull()
                    caps?.let { availability[product] = it }
                }
                if (
                    online &&
                        (force ||
                            caps == null ||
                            AppPlatform.now() - caps.second >= product.cadence)
                ) {
                    try {
                        val bytes = download.bytes(product.capabilitiesUrl, 4 * 1024 * 1024)
                        val parsed = MapWms.capabilities(bytes, product, AppPlatform.now())
                        caps = parsed to AppPlatform.now()
                        availability[product] = requireNotNull(caps)
                        runCatching { AtomicFiles.write(capFile, bytes) }
                    } catch (e: Exception) {
                        download.check()
                        if (caps == null) throw e
                        usedSaved = true
                    }
                }
                download.check()
                val available = caps?.first ?: error("No saved product information")
                val selection =
                    MapWms.select(
                        product,
                        target ?: AppPlatform.now(),
                        AppPlatform.now(),
                        available,
                    ) ?: error("No image for this time")
                val key = MapWms.key(selection, area)
                val file = File(File(root, product.name), "$key.png")
                val saved = readImage(file, selection, area)
                if (saved != null) {
                    usedSaved = usedSaved || !online
                    return@runCatching saved
                }
                require(online) { "Image unavailable offline" }
                var actual = selection
                var bytes: ByteArray? = null
                // Latest satellite requests may step back a few advertised slots. A user-selected
                // historical time and every temperature request must keep their requested time.
                val candidates =
                    if (product == MapProduct.SATELLITE && target == null)
                        available.times
                            .filter { it <= selection.time }
                            .takeLast(4)
                            .reversed()
                            .map { MapSelection(product, it) }
                    else listOf(selection)
                for (candidate in candidates) {
                    actual = candidate
                    repeat(2) { attempt ->
                        if (bytes == null) {
                            val response =
                                download.bytes(MapWms.imageUrl(candidate, area), 8 * 1024 * 1024)
                            val error = MapWms.serviceError(response)
                            if (error == null) bytes = response
                            else if (!MapWms.timeUnavailable(error))
                                error("Provider could not render image")
                            else if (attempt == 1 && candidates.size == 1)
                                error("No image for this time")
                        }
                    }
                    if (bytes != null) break
                }
                val body = requireNotNull(bytes) { "No current satellite image" }
                download.check()
                require(Png.dimensions(body) == (area.width to area.height)) { "Invalid map image" }
                val bitmap =
                    BitmapFactory.decodeByteArray(body, 0, body.size) ?: error("Invalid map image")
                val received = AppPlatform.now()
                val actualFile = File(File(root, product.name), "${MapWms.key(actual,area)}.png")
                runCatching {
                    AtomicFiles.write(actualFile, body)
                    actualFile.setLastModified(received)
                    stores.getValue(product).wrote(actualFile)
                    AtomicFiles.write(
                        File(root, "${product.name}-last.tsv"),
                        listOf(
                                actual.time,
                                actual.run ?: "-",
                                area.left,
                                area.top,
                                area.right,
                                area.bottom,
                                area.width,
                                area.height,
                            )
                            .joinToString("\t")
                            .toByteArray(),
                    )
                }
                MapSnapshot(actual, area, bitmap, received)
            }
                .recoverCatching { error ->
                    download.check()
                    // Retain a geographically matching successful image across process restarts.
                    if (target != null) throw error
                    val fields =
                        File(root, "${product.name}-last.tsv").readText().trim().split('\t')
                    require(fields.size == 8)
                    val selection =
                        MapSelection(
                            product,
                            fields[0].toLong(),
                            fields[1].takeUnless { it == "-" }?.toLong(),
                        )
                    val savedArea =
                        MapArea(
                            fields[2].toDouble(),
                            fields[3].toDouble(),
                            fields[4].toDouble(),
                            fields[5].toDouble(),
                            fields[6].toInt(),
                            fields[7].toInt(),
                        )
                    require(savedArea.covers(area))
                    usedSaved = true
                    readImage(
                        File(File(root, product.name), "${MapWms.key(selection,savedArea)}.png"),
                        selection,
                        savedArea,
                    ) ?: throw error
                }
            handler.post {
                if (!closed && token == serial) {
                    loading = false
                    if (result.isSuccess) {
                        val snapshot = result.getOrThrow()
                        retained[product] = snapshot
                        memory.put(MapWms.key(snapshot.selection, snapshot.area), snapshot)
                        notice =
                            if (usedSaved || !online)
                                "Saved image · ${if (online) "refresh unavailable" else "offline"}"
                            else null
                        ready(snapshot)
                    } else
                        notice =
                            "${product.title} unavailable${if (!online) " offline" else " · tap to retry"}"
                    changed()
                }
            }
        }
    }

    private fun readImage(file: File, selection: MapSelection, area: MapArea): MapSnapshot? {
        if (!file.isFile) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth != area.width || options.outHeight != area.height) {
            file.delete()
            return null
        }
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        stores.getValue(selection.product).read(file)
        return MapSnapshot(selection, area, bitmap, file.lastModified())
    }

    fun cancel() {
        serial++
        job?.cancel()
        job = null
        workers.queue.clear()
        loading = false
        notice = null
        lastRequest = null
    }

    fun close() {
        closed = true
        cancel()
        workers.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        memory.evictAll()
        retained.clear()
    }

    private class Download {
        @Volatile private var cancelled = false
        @Volatile private var connection: HttpURLConnection? = null
        var future: Future<*>? = null

        fun check() {
            if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException()
        }

        fun cancel() {
            cancelled = true
            future?.cancel(true)
            // Disconnect can block in Android's network stack; never do it on the UI thread.
            val active = connection
            if (active != null) CompletableFuture.runAsync { active.disconnect() }
        }

        fun bytes(address: String, limit: Int): ByteArray {
            check()
            val reply =
                Http.conditional(
                    address,
                    limit,
                    open = { url ->
                        AppPlatform.open(url).also {
                            connection = it
                            check()
                        }
                    },
                )
            check()
            connection = null
            return (reply as? HttpReply.Ok)?.bytes ?: error("Unexpected empty response")
        }
    }
}
