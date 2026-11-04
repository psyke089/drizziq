package app.drizziq

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

/** A native, continuous Web Mercator viewport with an ODIM stereographic mesh. */
class RadarMap(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val crosshair =
        requireNotNull(context.getDrawable(IconAssets.resource("location-crosshair")))
            .mutate()
            .apply {
                alpha = 130
                val radius = context.dp(12)
                setBounds(-radius, -radius, radius, radius)
            }
    private val tiles = LruCache<String, Bitmap>(64)
    private val waiting = mutableSetOf<String>()
    private val failed = BoundedAccessMap<String, Long>(CachePolicy.FAILED_TILE_KEYS)
    private val executor = Executors.newFixedThreadPool(3)
    private val handler = Handler(Looper.getMainLooper())
    internal var productChanged: (() -> Unit)? = null
    private val presentation = MapPresentation()
    private var productToken = 0
    private var weatherImage: MapSnapshot? = null
    private val targets = mutableMapOf<MapProduct, Long?>()
    private val productZooms = mutableMapOf<MapProduct, Double>()
    private var pendingZoom = 7.0
    private var downloadsActive = false
    private val prepareImage = Runnable { loadProduct() }
    private val images =
        MapImages(
            context,
            { snapshot ->
                if (presentation.commit(snapshot.selection.product, productToken)) {
                    weatherImage = snapshot
                    zoom = pendingZoom
                    productZooms[displayedProduct] = zoom
                    describeViewport()
                    invalidate()
                    productChanged?.invoke()
                }
            },
            {
                invalidate()
                productChanged?.invoke()
            },
        )

    internal val displayedProduct
        get() = presentation.displayed

    internal val requestedProduct
        get() = presentation.requested

    internal val productSelection
        get() = weatherImage?.selection?.takeIf { displayedProduct != MapProduct.RADAR }

    internal val productReceivedAt
        get() = weatherImage?.receivedAt

    internal val productLoading
        get() =
            images.loading ||
                (requestedProduct != displayedProduct &&
                    images.notice?.contains("unavailable") != true)

    internal val productNotice
        get() = images.notice

    internal fun productTimes() = images.times(displayedProduct)

    internal fun product(product: MapProduct, force: Boolean = false) {
        productZooms[displayedProduct] = zoom
        productToken = presentation.request(product)
        images.cancel()
        handler.removeCallbacks(prepareImage)
        pendingZoom = (productZooms[product] ?: zoom).coerceAtMost(product.maxZoom)
        if (product == MapProduct.RADAR) {
            if (bitmap != null && presentation.commit(product, productToken)) {
                zoom = pendingZoom
                describeViewport()
                invalidate()
            }
        } else {
            visibleArea(pendingZoom)?.let { visible ->
                images.remembered(product, visible)?.let { snapshot ->
                    if (presentation.commit(product, productToken)) {
                        weatherImage = snapshot
                        zoom = pendingZoom
                        describeViewport()
                        invalidate()
                    }
                }
            }
            loadProduct(force)
        }
        productChanged?.invoke()
    }

    internal fun productTime(time: Long) {
        targets[requestedProduct] = time
        loadProduct()
    }

    internal fun activate(value: Boolean) {
        downloadsActive = value
        if (!value) {
            images.cancel()
            handler.removeCallbacks(prepareImage)
        } else if (requestedProduct != MapProduct.RADAR) loadProduct()
    }

    internal fun refreshProduct() {
        if (downloadsActive && requestedProduct != MapProduct.RADAR) loadProduct()
    }

    private fun visibleArea(atZoom: Double = zoom): MapArea? =
        if (width > 0 && height > 0) MapArea.viewport(x, y, atZoom, width, height) else null

    private fun loadProduct(force: Boolean = false) {
        if (
            !downloadsActive ||
                disposed ||
                requestedProduct == MapProduct.RADAR ||
                width == 0 ||
                height == 0
        )
            return
        pendingZoom =
            if (requestedProduct == displayedProduct) zoom
            else (productZooms[requestedProduct] ?: zoom).coerceAtMost(requestedProduct.maxZoom)
        images.request(
            requestedProduct,
            targets[requestedProduct],
            MapArea.viewport(x, y, pendingZoom, width, height, 1.15),
            online,
            force,
        )
    }

    private val folder = File(context.cacheDir, "tiles").apply { mkdirs() }
    private val storage by lazy {
        CacheStore(
            folder,
            CachePolicy.BASEMAP_BYTES,
            eligible = { it.endsWith(".png") },
            indexName = "access.tsv",
        )
    }
    private val tileFetcher by lazy {
        TileFetcher(
            folder = folder,
            receipts = TileReceipts(File(folder, "tile-receipts.tsv")),
            decode = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) },
            decodeFile = { file -> BitmapFactory.decodeFile(file.path) },
            fetch = { address, etag, lastModified ->
                Http.conditional(address, 1500000, etag, lastModified)
            },
            clock = AppPlatform::now,
            onRead = storage::read,
            onWrite = storage::wrote,
        )
    }
    private val viewportPreferences =
        context.getSharedPreferences("radar-viewport", Context.MODE_PRIVATE)
    private var placeInitialized = false
    private var disposed = false
    private var x = .532151
    private var y = .3471
    var zoom = 7.0
        private set

    var place = Place.MUNICH
        set(value) {
            val moved = field.lat != value.lat || field.lon != value.lon
            field = value
            if (!placeInitialized) {
                placeInitialized = true
                val saved =
                    RadarViewport.decode(
                        runCatching { viewportPreferences.getString("camera", null) }.getOrNull(),
                        value,
                    )
                if (saved == null) recenter()
                else {
                    x = saved.x
                    y = saved.y
                    zoom = saved.zoom
                    viewportChanged()
                }
            } else if (moved) {
                recenter()
                saveViewport()
            }
        }

    var online = true
    var frame: RadarFrame? = null
        private set

    private var bitmap: Bitmap? = null
    private var projectedGrid: RadarGrid? = null
    private val geographicMesh = DoubleArray(33 * 37 * 2)
    private val drawingMesh = FloatArray(33 * 37 * 2)
    private val tileRect = RectF()
    private val dark =
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -.17f,
                    -.17f,
                    -.17f,
                    0f,
                    112f,
                    -.18f,
                    -.18f,
                    -.18f,
                    0f,
                    130f,
                    -.16f,
                    -.16f,
                    -.16f,
                    0f,
                    149f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )
            )
        )
    private val scale =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    zoomBy(log2(detector.scaleFactor.toDouble()), detector.focusX, detector.focusY)
                    return true
                }
            },
        )
    private val gesture =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    dx: Float,
                    dy: Float,
                ): Boolean {
                    if (!scale.isInProgress) {
                        val world = world()
                        x = (x + dx / world + 1) % 1
                        y = (y + dy / world).coerceIn(0.0, 1.0)
                        viewportChanged()
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    zoomBy(1.0, e.x, e.y)
                    return true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    performClick()
                    return true
                }
            },
        )

    init {
        setBackgroundColor(Hud.bg)
        contentDescription =
            "Precipitation map. Drag to pan, pinch or double tap to zoom. Zoom and recenter actions are available to accessibility services."
        isFocusable = true
        recenter()
        executor.execute { storage.prune() }
    }

    private fun world() = 256.0 * 2.0.pow(zoom)

    private fun mercator(lat: Double, lon: Double): Pair<Double, Double> {
        val phi = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (lon + 180) / 360 to (1 - ln(tan(phi) + 1 / cos(phi)) / Math.PI) / 2
    }

    private fun screen(lat: Double, lon: Double): Pair<Float, Float> {
        val (mx, my) = mercator(lat, lon)
        val delta = ((mx - x + .5 + 1) % 1) - .5
        return (width / 2 + delta * world()).toFloat() to
            (height / 2 + (my - y) * world()).toFloat()
    }

    private fun describeViewport() {
        crosshair.setTint(if (displayedProduct == MapProduct.TEMPERATURE) Hud.bg else Hud.cyan)
        crosshair.alpha = if (displayedProduct == MapProduct.RADAR) 130 else 255
        val latitude = Math.toDegrees(atan(sinh(Math.PI * (1 - 2 * y))))
        contentDescription =
            "${if (displayedProduct == MapProduct.RADAR) "Precipitation" else displayedProduct.title} map. Center ${String.format(java.util.Locale.US,"%.6f, %.6f",latitude,x*360-180)}; zoom ${String.format(java.util.Locale.US,"%.2f",zoom)}. Drag to pan, pinch or double tap to zoom."
        invalidate()
    }

    private fun viewportChanged() {
        describeViewport()
        invalidate()
        handler.removeCallbacks(prepareImage)
        if (downloadsActive) handler.postDelayed(prepareImage, 250)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) viewportChanged()
    }

    fun recenter() {
        val c = mercator(place.lat, place.lon)
        x = ((c.first % 1) + 1) % 1
        y = c.second.coerceIn(0.0, 1.0)
        viewportChanged()
    }

    /**
     * Commit only at gesture/lifecycle boundaries so a force-stop cannot discard the last camera.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun saveViewport() {
        if (!placeInitialized) return
        viewportPreferences
            .edit()
            .putString("camera", RadarViewport(x, y, zoom).encode(place))
            .commit()
    }

    fun zoomBy(amount: Double, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        val before = world()
        val next = (zoom + amount).coerceIn(2.0, displayedProduct.maxZoom)
        zoom = next
        val after = world()
        x = (x + (focusX - width / 2) * (1 / before - 1 / after) + 1) % 1
        y = (y + (focusY - height / 2) * (1 / before - 1 / after)).coerceIn(0.0, 1.0)
        viewportChanged()
    }

    fun show(frame: RadarFrame, image: Bitmap) {
        if (projectedGrid != frame.grid) {
            var i = 0
            for (iy in 0..36) for (ix in 0..32) {
                val (lat, lon) =
                    RadarProjection.unproject(
                        frame.grid.left + ix * frame.grid.width * frame.grid.dx / 32,
                        frame.grid.top - iy * frame.grid.height * frame.grid.dy / 36,
                    )
                val (mx, my) = mercator(lat, lon)
                geographicMesh[i++] = mx
                geographicMesh[i++] = my
            }
            projectedGrid = frame.grid
        }
        this.frame = frame
        bitmap = image
        val switching = displayedProduct != MapProduct.RADAR
        if (
            requestedProduct == MapProduct.RADAR &&
                presentation.commit(MapProduct.RADAR, productToken)
        ) {
            if (switching) zoom = pendingZoom
            describeViewport()
        }
        invalidate()
        productChanged?.invoke()
    }

    fun reset() {
        frame = null
        bitmap = null
        invalidate()
    }

    // GestureDetector.onSingleTapUp calls performClick; a drag/pinch must not emit a click.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        scale.onTouchEvent(event)
        gesture.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            parent.requestDisallowInterceptTouchEvent(false)
            saveViewport()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(
        info: android.view.accessibility.AccessibilityNodeInfo
    ) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(4096, "Zoom in")
        )
        info.addAction(
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(8192, "Zoom out")
        )
        info.addAction(
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                RECENTER_ACTION,
                "Recenter on selected location",
            )
        )
    }

    override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean {
        when (action) {
            4096 -> zoomBy(1.0)
            8192 -> zoomBy(-1.0)
            RECENTER_ACTION -> recenter()
            else -> return super.performAccessibilityAction(action, args)
        }
        saveViewport()
        return true
    }

    private companion object {
        const val RECENTER_ACTION = 0x01000001
    }

    private fun request(key: String, z: Int, tx: Int, ty: Int) {
        if (
            disposed ||
                key in waiting ||
                (failed[key]?.let { AppPlatform.now() - it < 60000 } == true)
        )
            return
        waiting.add(key)
        executor.execute {
            val result = runCatching {
                tileFetcher.load(
                    key,
                    "https://tile.openstreetmap.org/$z/$tx/$ty.png",
                    online,
                )
            }
                .getOrNull()
            handler.post {
                if (!disposed) {
                    waiting.remove(key)
                    if (result != null) tiles.put(key, result) else failed[key] = AppPlatform.now()
                    invalidate()
                }
            }
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val z = floor(zoom).toInt()
        val count = 1 shl z
        val tile = world() / count
        val left = x * count - width / 2 / tile
        val top = y * count - height / 2 / tile
        p.colorFilter = dark
        p.alpha = 255
        p.style = Paint.Style.FILL
        for (iy in floor(top).toInt()..floor(top + height / tile).toInt()) for (ix in
            floor(left).toInt()..floor(left + width / tile).toInt()) {
            if (iy !in 0 until count) continue
            val nx = ((ix % count) + count) % count
            val key = "$z-$nx-$iy"
            val image = tiles.get(key)
            val rect = tileRect.apply {
                set(
                    ((ix - left) * tile).toFloat(),
                    ((iy - top) * tile).toFloat(),
                    ((ix - left + 1) * tile + 1).toFloat(),
                    ((iy - top + 1) * tile + 1).toFloat(),
                )
            }
            if (image != null) c.drawBitmap(image, null, rect, p)
            else {
                p.colorFilter = null
                p.color = Hud.panel
                c.drawRect(rect, p)
                p.style = Paint.Style.STROKE
                p.color = Hud.line
                c.drawRect(rect, p)
                p.style = Paint.Style.FILL
                p.colorFilter = dark
            }
            if (image == null) request(key, z, nx, iy)
        }
        p.colorFilter = null
        if (displayedProduct != MapProduct.RADAR)
            weatherImage?.let { snapshot ->
                val area = snapshot.area
                val world = world()
                val middle = (area.left + area.right) / 2
                val delta = ((middle - x + .5 + 1) % 1) - .5
                val left = width / 2 + (delta - (area.right - area.left) / 2) * world
                val top = height / 2 + (area.top - y) * world
                p.alpha = if (displayedProduct == MapProduct.SATELLITE) 255 else 220
                c.drawBitmap(
                    snapshot.bitmap,
                    null,
                    tileRect.apply {
                        set(
                            left.toFloat(),
                            top.toFloat(),
                            (left + (area.right - area.left) * world).toFloat(),
                            (top + (area.bottom - area.top) * world).toFloat(),
                        )
                    },
                    p,
                )
                p.alpha = 255
            }
        val f = frame
        val image = bitmap
        // Only the fully committed product is drawn.
        if (f != null && image != null && displayedProduct == MapProduct.RADAR) {
            val world = world()
            for (index in geographicMesh.indices step 2) {
                val delta = ((geographicMesh[index] - x + .5 + 1) % 1) - .5
                drawingMesh[index] = (width / 2 + delta * world).toFloat()
                drawingMesh[index + 1] =
                    (height / 2 + (geographicMesh[index + 1] - y) * world).toFloat()
            }
            p.alpha = 235
            c.drawBitmapMesh(image, 32, 36, drawingMesh, 0, null, 0, p)
            p.alpha = 255
        }
        val (px, py) = screen(place.lat, place.lon)
        c.save()
        c.translate(px, py)
        crosshair.draw(c)
        c.restore()
        p.style = Paint.Style.FILL
        p.alpha = 255
        p.textSize = context.dp(11).toFloat()
        p.typeface = Typeface.MONOSPACE
        if (displayedProduct == MapProduct.RADAR && f != null && !f.grid.contains(place)) {
            p.color = Hud.bg
            c.drawRect(0f, 0f, width.toFloat(), context.dp(34).toFloat(), p)
            p.color = Hud.amber
            p.textAlign = Paint.Align.CENTER
            c.drawText("Outside DWD radar coverage", width / 2f, context.dp(22).toFloat(), p)
            p.textAlign = Paint.Align.LEFT
        }
    }

    fun dispose() {
        disposed = true
        images.close()
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        tiles.evictAll()
        bitmap = null
    }
}

class RadarTimeline(context: Context) : View(context) {
    var times: List<Long> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var frames: List<RadarFrame> = emptyList()
        set(value) {
            field = value
            times = value.map { it.time }
            invalidate()
        }

    var selected = 0
        set(value) {
            field = value
            invalidate()
        }

    var onSelect: ((Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = "Radar timeline; swipe to scrub"
        isFocusable = true
        isClickable = true
    }

    private fun pos(t: Long): Float {
        if (times.size < 2) return width / 2f
        return context.dp(16) +
            (width - context.dp(32)) * (t - times.first()).toFloat() /
                (times.last() - times.first())
    }

    override fun onDraw(c: Canvas) {
        if (times.isEmpty()) return
        val y = height * .5f
        p.color = Hud.line
        p.strokeWidth = context.dp(2).toFloat()
        c.drawLine(pos(times.first()), y, pos(times.last()), y, p)
        times.forEachIndexed { i, time ->
            p.color = if (i <= selected) Hud.cyan else Hud.muted
            c.drawCircle(pos(time), y, context.dp(1).toFloat(), p)
        }
        val now = AppPlatform.now()
        if (now in times.first()..times.last()) {
            p.color = Hud.amber
            p.strokeWidth = context.dp(1).toFloat()
            val x = pos(now)
            c.drawLine(x, y - context.dp(7), x, y + context.dp(7), p)
        }
        p.color = Hud.cyan
        times.getOrNull(selected)?.let {
            c.drawCircle(pos(it), y, context.dp(5).toFloat(), p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled || times.isEmpty()) return false
        if (
            e.action == MotionEvent.ACTION_DOWN ||
                e.action == MotionEvent.ACTION_MOVE ||
                e.action == MotionEvent.ACTION_UP
        ) {
            val index = times.indices.minBy { abs(pos(times[it]) - e.x) }
            onSelect?.invoke(index)
            if (e.action == MotionEvent.ACTION_UP) performClick()
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(
        info: android.view.accessibility.AccessibilityNodeInfo
    ) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.addAction(
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                .ACTION_SCROLL_FORWARD
        )
        info.addAction(
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                .ACTION_SCROLL_BACKWARD
        )
        if (times.isNotEmpty())
            info.rangeInfo =
                android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                    android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                    0f,
                    (times.size - 1).toFloat(),
                    selected.toFloat(),
                )
    }

    override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean {
        val next =
            when (action) {
                4096 -> selected + 1
                8192 -> selected - 1
                else -> return super.performAccessibilityAction(action, args)
            }
        if (!isEnabled || times.isEmpty()) return false
        onSelect?.invoke(next.coerceIn(0, times.lastIndex))
        return true
    }
}

/** The discrete classes of DWD's icon_reg025_fd_sl_t2m_wmc_isoarea style. */
internal class TemperatureScale(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colours =
        intArrayOf(
            0xFFFFF7F3.toInt(),
            0xFFFDE0DD.toInt(),
            0xFFFCC5C0.toInt(),
            0xFFFA9FB5.toInt(),
            0xFFF768A1.toInt(),
            0xFFDD3497.toInt(),
            0xFFAE017E.toInt(),
            0xFF7A0177.toInt(),
            0xFF680099.toInt(),
            0xFF0570B0.toInt(),
            0xFF74A9CF.toInt(),
            0xFFBDC9E1.toInt(),
            0xFFEDF8FB.toInt(),
            0xFFC7E9B4.toInt(),
            0xFFFFFFB2.toInt(),
            0xFFFED976.toInt(),
            0xFFFEB24C.toInt(),
            0xFFFD8D3C.toInt(),
            0xFFFC4E2A.toInt(),
            0xFFE31A1C.toInt(),
            0xFFB10026.toInt(),
            0xFF4D0000.toInt(),
        )
    private val positions = intArrayOf(0, 5, 9, 13, 17, 21)
    private val labels = arrayOf("<−60", "−40", "−20", "0", "20", ">40 °C")

    init {
        contentDescription =
            "DWD temperature scale in Celsius, below minus sixty through above forty degrees; five-degree colour classes"
        paint.textSize = context.dp(9).toFloat()
        paint.typeface = Typeface.MONOSPACE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val step = width.toFloat() / colours.size
        for (i in colours.indices) {
            paint.color = colours[i]
            canvas.drawRect(i * step, 0f, (i + 1) * step, context.dp(7).toFloat(), paint)
        }
        paint.color = Hud.ink
        for (i in positions.indices) {
            paint.textAlign =
                when (i) {
                    0 -> Paint.Align.LEFT
                    positions.lastIndex -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
            val x =
                when (i) {
                    0 -> 0f
                    positions.lastIndex -> width.toFloat()
                    else -> (positions[i] + .5f) * step
                }
            canvas.drawText(labels[i], x, context.dp(24).toFloat(), paint)
        }
    }
}
