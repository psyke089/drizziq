package app.drizziq

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
    @Volatile private var paused = false
    @Volatile private var viewX = .5
    @Volatile private var viewY = .5
    @Volatile private var viewZoom = 2.0
    @Volatile private var viewWidth = 0
    @Volatile private var viewHeight = 0
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

    @Volatile var online = true
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

    private fun viewportChanged() {
        viewX = x
        viewY = y
        viewZoom = zoom
        viewWidth = width
        viewHeight = height
        val latitude = Math.toDegrees(atan(sinh(Math.PI * (1 - 2 * y))))
        contentDescription =
            "Precipitation map. Center ${String.format(java.util.Locale.US,"%.6f, %.6f",latitude,x*360-180)}; zoom ${String.format(java.util.Locale.US,"%.2f",zoom)}. Drag to pan, pinch or double tap to zoom."
        invalidate()
    }

    fun recenter() {
        val c = mercator(place.lat, place.lon)
        x = ((c.first % 1) + 1) % 1
        y = c.second.coerceIn(0.0, 1.0)
        viewportChanged()
    }

    fun focusOnPlace() {
        zoom = maxOf(zoom, 9.0)
        recenter()
        saveViewport()
    }

    /**
     * Commit at gesture and lifecycle boundaries: a force-stop has no lifecycle callback, so a
     * deferred write would silently discard the last camera.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun saveViewport() {
        if (!placeInitialized) return
        viewportPreferences
            .edit()
            .putString("camera", RadarViewport(x, y, zoom).encode(place))
            .commit()
    }

    fun flushViewport() = saveViewport()

    fun setPaused(value: Boolean) {
        paused = value
        if (!value) invalidate()
    }

    fun zoomBy(amount: Double, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        val before = world()
        val next = (zoom + amount).coerceIn(2.0, 17.0)
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
        invalidate()
    }

    fun reset() {
        frame = null
        bitmap = null
        invalidate()
    }

    // GestureDetector.onSingleTapUp calls performClick; a drag/pinch must not emit a click.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
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
        const val MAX_WAITING_TILES = 16
    }

    private fun request(key: String, z: Int, tx: Int, ty: Int) {
        if (
            disposed ||
                paused ||
                key in waiting ||
                waiting.size >= MAX_WAITING_TILES ||
                (failed[key]?.let { AppPlatform.now() - it < 60000 } == true)
        )
            return
        waiting.add(key)
        executor.execute {
            if (disposed || paused || !isVisible(z, tx, ty)) {
                handler.post { waiting.remove(key) }
                return@execute
            }
            val result = runCatching {
                tileFetcher.load(
                    key,
                    "https://tile.openstreetmap.org/$z/$tx/$ty.png",
                    online,
                    needed = { !disposed && !paused && isVisible(z, tx, ty) },
                    onCached = { cached ->
                        handler.post {
                            if (!disposed && !paused) {
                                tiles.put(key, cached)
                                invalidate()
                            }
                        }
                    },
                )
            }
                .getOrNull()
            handler.post {
                if (!disposed) {
                    waiting.remove(key)
                    if (result != null) {
                        tiles.put(key, result)
                    } else if (!paused) {
                        failed[key] = AppPlatform.now()
                    }
                    invalidate()
                }
            }
        }
    }

    /** Bounds check without allocations; reads a snapshot, so it is safe from worker threads. */
    private fun isVisible(z: Int, tx: Int, ty: Int): Boolean {
        val w = viewWidth
        val h = viewHeight
        if (w <= 0 || h <= 0) return true
        val count = 1 shl z
        val size = 256.0 * 2.0.pow(viewZoom) / count
        val left = viewX * count - w / 2 / size
        val top = viewY * count - h / 2 / size
        return tx >= floor(left - 1) &&
            tx <= floor(left + w / size + 1) &&
            ty >= floor(top - 1) &&
            ty <= floor(top + h / size + 1)
    }

    /** Draws an already loaded coarser tile while the requested zoom level is still arriving. */
    private fun fallback(key: String): Pair<Bitmap, Rect>? {
        val parts = key.split('-')
        if (parts.size != 3) return null
        var z = parts[0].toIntOrNull() ?: return null
        var tx = parts[1].toIntOrNull() ?: return null
        var ty = parts[2].toIntOrNull() ?: return null
        val childX = tx
        val childY = ty
        var factor = 1
        for (step in 1..3) {
            z -= 1
            tx /= 2
            ty /= 2
            factor *= 2
            if (z < 2) return null
            val parent = tiles.get("$z-$tx-$ty") ?: continue
            val w = parent.width / factor
            val h = parent.height / factor
            if (w <= 0 || h <= 0) continue
            val left = (childX and (factor - 1)) * w
            val top = (childY and (factor - 1)) * h
            return parent to Rect(left, top, left + w, top + h)
        }
        return null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
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
            if (image != null) {
                c.drawBitmap(image, null, rect, p)
            } else {
                val coarse = fallback(key)
                if (coarse != null) {
                    c.drawBitmap(coarse.first, coarse.second, rect, p)
                } else {
                    p.colorFilter = null
                    p.color = Hud.panel
                    c.drawRect(rect, p)
                    p.style = Paint.Style.STROKE
                    p.color = Hud.line
                    c.drawRect(rect, p)
                    p.style = Paint.Style.FILL
                    p.colorFilter = dark
                }
                request(key, z, nx, iy)
            }
        }
        p.colorFilter = null
        val f = frame
        val image = bitmap
        if (f != null && image != null) {
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
        if (f != null && !f.grid.contains(place)) {
            p.color = Hud.bg
            c.drawRect(0f, 0f, width.toFloat(), context.dp(34).toFloat(), p)
            p.color = Hud.amber
            p.textAlign = Paint.Align.CENTER
            c.drawText("Outside DWD radar coverage", width / 2f, context.dp(22).toFloat(), p)
            p.textAlign = Paint.Align.LEFT
        }
    }

    fun dispose() {
        flushViewport()
        disposed = true
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        tiles.evictAll()
        bitmap = null
    }
}

class RadarTimeline(context: Context) : View(context) {
    var sequence: RadarSequence? = null
        set(value) {
            field = value
            invalidate()
        }

    private val frames: List<RadarFrame>
        get() = sequence?.frames.orEmpty()

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
        return context.dp(16) + (width - context.dp(32)) * (sequence?.position(t) ?: .5f)
    }

    override fun onDraw(c: Canvas) {
        val issue = sequence?.issue ?: return
        val y = height * .5f
        p.color = Hud.line
        p.strokeWidth = context.dp(2).toFloat()
        c.drawLine(pos(issue - 3_600_000), y, pos(issue + 7_200_000), y, p)
        // Boundary between the observed history and the forecast part of the timeline.
        p.color = Hud.cyan
        p.strokeWidth = context.dp(1).toFloat()
        c.drawLine(pos(issue), y - context.dp(7), pos(issue), y + context.dp(7), p)
        frames.forEachIndexed { i, f ->
            p.color = if (i <= selected) Hud.cyan else Hud.muted
            p.style = if (f.lead == 0) Paint.Style.FILL else Paint.Style.STROKE
            p.strokeWidth = context.dp(1).toFloat()
            c.drawCircle(pos(f.time), y, context.dp(if (f.lead == 0) 2f else 1.5f).toFloat(), p)
        }
        p.style = Paint.Style.FILL
        val now = AppPlatform.now()
        if (now in issue - 3_600_000..issue + 7_200_000) {
            p.color = Hud.amber
            p.strokeWidth = context.dp(1).toFloat()
            val x = pos(now)
            c.drawLine(x, y - context.dp(7), x, y + context.dp(7), p)
        }
        p.color = Hud.cyan
        frames.getOrNull(selected)?.let {
            c.drawCircle(pos(it.time), y, context.dp(5).toFloat(), p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (frames.isEmpty()) return false
        if (
            e.action == MotionEvent.ACTION_DOWN ||
                e.action == MotionEvent.ACTION_MOVE ||
                e.action == MotionEvent.ACTION_UP
        ) {
            val index = frames.indices.minBy { abs(pos(frames[it].time) - e.x) }
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
        if (frames.isNotEmpty()) info.rangeInfo = frameRange()
    }

    @Suppress("DEPRECATION") // The constructor was added in API 30.
    private fun frameRange(): android.view.accessibility.AccessibilityNodeInfo.RangeInfo =
        if (android.os.Build.VERSION.SDK_INT >= 30)
            android.view.accessibility.AccessibilityNodeInfo.RangeInfo(
                android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                0f,
                (frames.size - 1).toFloat(),
                selected.toFloat(),
            )
        else
            android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                0f,
                (frames.size - 1).toFloat(),
                selected.toFloat(),
            )

    override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean {
        val next =
            when (action) {
                4096 -> selected + 1
                8192 -> selected - 1
                else -> return super.performAccessibilityAction(action, args)
            }
        if (frames.isEmpty()) return false
        onSelect?.invoke(next.coerceIn(0, frames.lastIndex))
        return true
    }
}
