package app.drizziq

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Exercises real native UI on an attached device, without external test libraries. */
class PhoneExercise : Instrumentation() {
    private val suitePhases =
        setOf(
            "smoke",
            "navigation",
            "lifecycle",
            "forecast",
            "radar",
            "places-search",
            "viewport-save",
            "viewport-restore",
        )
    private val focusedPhases =
        setOf(
            "static-assets",
            "hourly-refresh",
            "day-forecast",
            "header-weather",
            "forecast-status",
            "radar-corruption",
        )
    private val integrationPhases = setOf("places-integration")
    private val fullPhases = setOf("main", "persistence", "offline", "stopped-network", "restore")
    private val diagnosticPhases =
        setOf("recenter-probe", "geocoding-probe", "station-benchmark", "radar-gestures")
    private val knownPhases =
        suitePhases +
            focusedPhases +
            integrationPhases +
            fullPhases +
            diagnosticPhases +
            setOf("fixture-forecast", "fixture-states", "fixture-ui")
    private var phase = "main"
    private var fixture = false
    private val appContext
        get() = if (fixture) PhoneFixtures.context(targetContext) else targetContext

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        phase = arguments?.getString("phase") ?: "main"
        fixture = arguments?.getString("fixture") == "true"
        if (fixture)
            PhoneFixtures.install(targetContext, context.assets, phase == "viewport-restore")
        else AppPlatform.reset()
        start()
    }

    private fun nodes(
        node: AccessibilityNodeInfo? = uiAutomation.rootInActiveWindow
    ): List<AccessibilityNodeInfo> =
        if (node == null) emptyList()
        else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun waitFor(description: String, timeout: Long = 90000, test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (test()) return
            SystemClock.sleep(250)
        }
        // UI can contain a device-position snapshot; do not dump coordinates or queries to logs.
        error("Timed out: $description")
    }

    /** Runs a command through the instrumentation's shell identity and returns its output. */
    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                uiAutomation.executeShellCommand(command)
            )
            .bufferedReader()
            .use { it.readText() }

    private fun has(text: String) =
        nodes().any {
            it.text?.toString()?.contains(text) == true ||
                it.contentDescription?.toString()?.contains(text) == true
        }

    private fun click(text: String) {
        fun find() =
            nodes().firstOrNull {
                it.text?.toString()?.equals(text, true) == true ||
                    it.contentDescription?.toString()?.equals(text, true) == true
            }
        waitFor("button $text", 10000) { find() != null }
        val node = find() ?: error("Button disappeared: $text")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Click failed: $text" }
        SystemClock.sleep(350)
    }

    private fun enter(label: String, value: String) {
        val node = nodes().first { it.contentDescription?.toString() == label && it.isEditable }
        check(
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value,
                    )
                },
            )
        )
    }

    private fun submitSearch() {
        val editor =
            nodes().first { it.isEditable && it.contentDescription?.toString() == "CITY NAME" }
        if (!editor.isFocused) check(editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
        waitForIdleSync()
    }

    private fun drag(x1: Float, y1: Float, x2: Float, y2: Float) {
        val start = SystemClock.uptimeMillis()
        for (i in 0..20) {
            val event =
                MotionEvent.obtain(
                    start,
                    SystemClock.uptimeMillis(),
                    when (i) {
                        0 -> 0
                        20 -> 1
                        else -> 2
                    },
                    x1 + (x2 - x1) * i / 20,
                    y1 + (y2 - y1) * i / 20,
                    0,
                )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            uiAutomation.injectInputEvent(event, true)
            event.recycle()
            SystemClock.sleep(15)
        }
    }

    private fun pinch(x: Float, y: Float, reverse: Boolean = false) {
        val start = SystemClock.uptimeMillis()
        val props =
            Array(2) { i ->
                MotionEvent.PointerProperties().apply {
                    id = i
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
        fun send(action: Int, count: Int, distance: Float) {
            val coords =
                Array(count) { i ->
                    MotionEvent.PointerCoords().apply {
                        this.x = x + (if (i == 0) -distance else distance)
                        this.y = y
                        pressure = 1f
                        size = 1f
                    }
                }
            val event =
                MotionEvent.obtain(
                    start,
                    SystemClock.uptimeMillis(),
                    action,
                    count,
                    props,
                    coords,
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0,
                )
            uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        val startDistance = appContext.dp(if (reverse) 110 else 32).toFloat()
        val endDistance = appContext.dp(if (reverse) 32 else 110).toFloat()
        send(MotionEvent.ACTION_DOWN, 1, startDistance)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2, startDistance)
        for (i in 1..20) {
            send(MotionEvent.ACTION_MOVE, 2, startDistance + (endDistance - startDistance) * i / 20)
            SystemClock.sleep(15)
        }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl 8), 2, endDistance)
        send(MotionEvent.ACTION_UP, 1, endDistance)
    }

    private fun radarMapNode() =
        nodes().first {
            it.contentDescription?.toString()?.startsWith("Precipitation map") == true
        }

    private fun radarGestures() {
        val bounds = Rect().apply { radarMapNode().getBoundsInScreen(this) }
        fun zoom() =
            Regex("zoom ([0-9]+(?:\\.[0-9]+)?)")
                .find(radarMapNode().contentDescription.toString())!!
                .groupValues[1]
                .toDouble()
        // Saved camera state can start at maximum zoom. Leave headroom before asserting pinch-in.
        while (zoom() > 10.0) {
            val previous = zoom()
            check(radarMapNode().performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
            waitFor("zoom headroom for gesture test", 3000) { zoom() < previous }
        }
        val original = radarMapNode().contentDescription.toString()
        drag(
            bounds.centerX().toFloat(),
            bounds.centerY().toFloat(),
            bounds.centerX() + 100f,
            bounds.centerY() + 80f,
        )
        waitFor("one-finger pan moves the map", 3000) {
            radarMapNode().contentDescription.toString() != original
        }
        val beforeZoom = zoom()
        pinch(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        waitFor("spreading fingers zooms in", 3000) { zoom() > beforeZoom }
        val zoomed = zoom()
        pinch(bounds.centerX().toFloat(), bounds.centerY().toFloat(), reverse = true)
        waitFor("pinching fingers zooms out", 3000) { zoom() < zoomed }
        val beforeDoubleTap = zoom()
        repeat(2) {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event =
                    MotionEvent.obtain(
                        time,
                        SystemClock.uptimeMillis(),
                        action,
                        bounds.centerX().toFloat(),
                        bounds.centerY().toFloat(),
                        0,
                    )
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                uiAutomation.injectInputEvent(event, true)
                event.recycle()
            }
            SystemClock.sleep(70)
        }
        waitFor("double tap zoom", 3000) { zoom() > beforeDoubleTap }
    }

    private fun contentHasTemperature(activity: android.app.Activity): Boolean {
        var found = false
        runOnMainSync {
            fun views(root: android.view.View): List<android.view.View> =
                listOf(root) +
                    if (root is android.view.ViewGroup)
                        (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
                    else emptyList()
            val scroll =
                views(activity.window.decorView)
                    .filterIsInstance<android.widget.ScrollView>()
                    .first { it.visibility == android.view.View.VISIBLE }
            found =
                views(scroll).any {
                    it.contentDescription?.toString()?.startsWith("Temperature ") == true
                }
        }
        return found
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(phase in knownPhases) { "Unknown phase: $phase" }
            result.putString("phase", phase)
            val activity =
                startActivitySync(
                    Intent(appContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            waitFor("initial app") {
                var focused = false
                runOnMainSync { focused = activity.hasWindowFocus() }
                focused && has("PLACES")
            }
            when (phase) {
                "smoke" -> {
                    waitFor("Today forecast") {
                        has("Temperature ") && has("Hourly forecast timeline")
                    }
                    click("Week")
                    waitFor("Week forecast") {
                        has("Maximum temperature") && has("Minimum temperature")
                    }
                    click("Radar")
                    waitFor("radar frames") { has("OBSERVED") && has("frames") }
                    fun timeline() = nodes().first { it.className == "android.widget.SeekBar" }
                    val before = timeline().rangeInfo.current
                    check(timeline().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    waitFor("timeline selection") { timeline().rangeInfo.current != before }
                    val paused = timeline().rangeInfo.current
                    click("Play or pause radar animation")
                    waitFor("radar playback", 10000) { timeline().rangeInfo.current != paused }
                    click("Play or pause radar animation")
                    click("Places")
                    check(has("FAVOURITES"))
                    click("SEARCH CITY")
                    enter("CITY NAME", "München")
                    waitFor("city results") {
                        nodes().any {
                            it.contentDescription?.toString()?.startsWith("Select city result ") ==
                                true
                        }
                    }
                    click("Close dialog")
                    check(has("FAVOURITES"))
                    click("Today")
                    check(has("Temperature "))
                    result.putString(
                        "checks",
                        "Today/Week data; radar scrub/play/pause; Places search/cancel; return to Today",
                    )
                }
                "fixture-forecast" -> {
                    waitFor("fixture forecast", 10000) { has("Temperature ") }
                    runOnMainSync {
                        val date =
                            activity.window.decorView.findViewWithTag<android.widget.TextView>(
                                "today-date"
                            )
                        check(date.text.toString() == "Thursday, 22 Oct 2026") {
                            "Forecast must use the isolated fixture clock"
                        }
                    }
                    result.putString("checks", "fixed fixture clock and real forecast Views")
                }
                "fixture-ui" -> {
                    check(fixture)
                    waitFor("fixture table") { has("Temperature ") }
                    fun views(root: android.view.View): List<android.view.View> =
                        listOf(root) +
                            if (root is android.view.ViewGroup)
                                (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
                            else emptyList()
                    var layoutError: String? = null
                    runOnMainSync {
                        val all = views(activity.window.decorView)
                        if (all.none { it.contentDescription?.toString() == "Rain unavailable" })
                            layoutError = "Missing rain does not announce unavailable"
                        val configuration =
                            android.content.res.Configuration(appContext.resources.configuration)
                        configuration.setLocale(java.util.Locale.GERMANY)
                        val german = appContext.createConfigurationContext(configuration)
                        val header = CurrentWeatherHeader(german)
                        header.bind(
                            Hour(PhoneFixtures.now, mapOf("TTT" to 267.45, "FF" to 2.0)),
                            Freshness.LIVE,
                            "UTC",
                        )
                        val temperature =
                            header.findViewWithTag<android.widget.TextView>("header-temperature")
                        if (!temperature.text.toString().contains("-5.7"))
                            layoutError = "HUD number followed German locale"
                    }
                    check(layoutError == null) { layoutError.orEmpty() }
                    click("Week")
                    waitFor("weekly table") { has("Maximum temperature") }
                    click("Radar")
                    waitFor("fixture radar timeline") { has("OBSERVED") }
                    result.putString("checks", "missing rain, German locale, radar timeline")
                }
                "fixture-states" -> {
                    check(fixture) { "fixture-states requires fixture=true" }
                    fun repository(owner: android.app.Activity): Repository =
                        MainActivity::class
                            .java
                            .getDeclaredField("repo")
                            .apply { isAccessible = true }
                            .get(owner) as Repository
                    val repo = repository(activity)
                    waitFor("fixture live forecast") {
                        repo.forecast.state == Freshness.LIVE && !repo.forecast.refreshing
                    }
                    val retained = requireNotNull(repo.forecast.data)
                    PhoneFixtures.forecastFailure = true
                    runOnMainSync { repo.refreshForecast() }
                    waitFor("failed refresh retains data") {
                        !repo.forecast.refreshing && repo.forecast.state == Freshness.STALE
                    }
                    check(repo.forecast.data == retained)
                    check(has("STALE") && has("Temperature "))
                    PhoneFixtures.forecastFailure = false
                    AppPlatform.connected = false
                    runOnMainSync {
                        repo.connection()
                        repo.refreshRadar()
                    }
                    waitFor("offline Today retains forecast") {
                        repo.forecast.state == Freshness.STALE &&
                            repo.forecast.data == retained &&
                            has("STALE") &&
                            has("Temperature ")
                    }
                    click("Radar")
                    waitFor("fixture offline radar") { has("Offline radar") }
                    waitFor("fixture requests settle", 5000) { !Http.downloads.active }
                    val before = PhoneFixtures.requests.get()
                    runOnMainSync {
                        repo.refreshForecast()
                        repo.refreshRadar()
                    }
                    waitFor("offline refresh terminal") { !repo.forecast.refreshing }
                    check(before == PhoneFixtures.requests.get()) {
                        "Offline issued an HTTP request"
                    }
                    AppPlatform.connected = true
                    runOnMainSync {
                        repo.connection()
                        repo.refreshForecast()
                    }
                    waitFor("fixture recovery") {
                        repo.forecast.state == Freshness.LIVE && !repo.forecast.refreshing
                    }
                    click("Today")
                    val gate = java.util.concurrent.CountDownLatch(1)
                    PhoneFixtures.forecastGate = gate
                    var recreated: android.app.Activity? = null
                    try {
                        runOnMainSync { repo.refreshForecast() }
                        waitFor("controlled pending refresh") { repo.forecast.refreshing }
                        val monitor = addMonitor(MainActivity::class.java.name, null, false)
                        runOnMainSync { activity.recreate() }
                        recreated =
                            waitForMonitorWithTimeout(monitor, 10000)
                                ?: error("Pending refresh recreation failed")
                        removeMonitor(monitor)
                        gate.countDown()
                        waitFor("new instance owns completion") {
                            repository(requireNotNull(recreated)).forecast.state == Freshness.LIVE
                        }
                        check(activity.isDestroyed)
                    } finally {
                        gate.countDown()
                        PhoneFixtures.forecastGate = null
                    }
                    val current = requireNotNull(recreated)
                    PlaceStore(appContext).select(Place("Outside coverage", 0.0, 0.0, "UTC"))
                    val monitor = addMonitor(MainActivity::class.java.name, null, false)
                    runOnMainSync { current.recreate() }
                    val empty =
                        waitForMonitorWithTimeout(monitor, 10000)
                            ?: error("Empty-state recreation failed")
                    removeMonitor(monitor)
                    waitFor("empty forecast error") {
                        repository(empty).forecast.state == Freshness.ERROR
                    }
                    check(repository(empty).forecast.data == null)
                    waitFor("error Views") { has("No DWD forecast point within 50 km") }
                    result.putString(
                        "checks",
                        "real Views: failed-refresh retention, offline no-request, recovery, pending recreation, obsolete owner, empty/error",
                    )
                }
                "static-assets" -> {
                    runOnMainSync {
                        // Keep this map uninitialised for a place, so gestures cannot save a
                        // camera.
                        val map = RadarMap(appContext).apply { online = false }
                        val parent = android.widget.FrameLayout(appContext)
                        parent.addView(map)
                        map.layout(0, 0, 400, 400)
                        var clicks = 0
                        map.setOnClickListener { clicks++ }
                        val start = SystemClock.uptimeMillis()
                        fun send(down: Long, elapsed: Long, action: Int, x: Float) {
                            val event = MotionEvent.obtain(down, down + elapsed, action, x, 200f, 0)
                            try {
                                check(map.dispatchTouchEvent(event))
                            } finally {
                                event.recycle()
                            }
                        }
                        try {
                            send(start, 0, MotionEvent.ACTION_DOWN, 200f)
                            send(start, 50, MotionEvent.ACTION_UP, 200f)
                            check(clicks == 1) { "Radar tap must reach performClick" }
                            val dragStart = start + 1000
                            send(dragStart, 0, MotionEvent.ACTION_DOWN, 200f)
                            send(dragStart, 50, MotionEvent.ACTION_MOVE, 300f)
                            send(dragStart, 100, MotionEvent.ACTION_UP, 300f)
                            check(clicks == 1) { "Radar drag must not emit a click" }
                            check(
                                map.performAccessibilityAction(
                                    AccessibilityNodeInfo.ACTION_CLICK,
                                    null,
                                )
                            )
                            check(clicks == 2) {
                                "Radar accessibility click must reach performClick"
                            }
                        } finally {
                            map.dispose()
                            parent.removeView(map)
                        }

                        fun descendants(root: android.view.View): List<android.view.View> =
                            listOf(root) +
                                if (root is android.view.ViewGroup)
                                    (0 until root.childCount).flatMap {
                                        descendants(root.getChildAt(it))
                                    }
                                else emptyList()

                        var selected = 0
                        var deleted = 0
                        val favourite =
                            FavouriteCard(
                                appContext,
                                Place.MUNICH.copy(label = "Test favourite"),
                                selected = true,
                                select = { selected++ },
                                delete = { deleted++ },
                            )
                        check(favourite.isClickable && favourite.isSelected)
                        check(favourite.contentDescription == "Select favourite Test favourite")
                        check(favourite.performClick() && selected == 1)
                        check(
                            descendants(favourite).filterIsInstance<android.widget.Button>().none {
                                it.text.toString() in listOf("SELECT", "DELETE")
                            }
                        ) {
                            "Favourite cards must not show text action buttons"
                        }
                        val delete = descendants(favourite).first { it.tag == "favourite-delete" }
                        check(delete.performClick() && deleted == 1 && selected == 1) {
                            "Delete icon must delete without selecting the favourite"
                        }
                    }
                    for ((file, time) in
                        listOf(
                            "rv-observed-20261022.h5" to "2026-10-22T20:00:00Z",
                            "rv-forecast-20261022.h5" to "2026-10-22T22:15:00Z",
                        )) {
                        val radar = Odim.decode(context.assets.open(file).use { it.readBytes() })
                        check(radar.time == Times.parse(time)) { "Radar fixture validity: $file" }
                        check(radar.grid.width == 1100 && radar.grid.height == 1200)
                        check(radar.colors.any { it == 0 }) { "Dry radar pixels: $file" }
                        check(radar.colors.any { it == RadarPalette.colors[1] }) {
                            "Precipitation radar pixels: $file"
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        runOnMainSync {
                            val icon =
                                appContext.packageManager.getApplicationIcon(appContext.packageName)
                                    as android.graphics.drawable.AdaptiveIconDrawable
                            val mask = checkNotNull(icon.monochrome) { "Missing themed icon mask" }
                            val bitmap =
                                android.graphics.Bitmap.createBitmap(
                                    108,
                                    108,
                                    android.graphics.Bitmap.Config.ARGB_8888,
                                )
                            try {
                                mask.setBounds(0, 0, 108, 108)
                                mask.draw(android.graphics.Canvas(bitmap))
                                val pixels = IntArray(108 * 108)
                                bitmap.getPixels(pixels, 0, 108, 0, 0, 108, 108)
                                check(pixels.any { android.graphics.Color.alpha(it) > 0 }) {
                                    "Themed icon mask is empty"
                                }
                                check(android.graphics.Color.alpha(bitmap.getPixel(0, 0)) == 0) {
                                    "Themed icon mask must have a transparent background"
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }

                    val names =
                        ((0..99).map { weatherIconName(it) } +
                                ForecastMetric.entries.map { "metric-${it.name.lowercase()}" } +
                                listOf(
                                    "delete",
                                    "nav-today",
                                    "nav-week",
                                    "nav-radar",
                                    "nav-places",
                                    "sunrise",
                                    "sunset",
                                    "wind-compass",
                                    "wind-arrow",
                                    "location-crosshair",
                                    "logo",
                                    "play",
                                    "pause",
                                    "launcher-foreground",
                                    "launcher-legacy",
                                ) +
                                listOf(0.0, 30.0, 40.0).mapNotNull { windIconName(it) })
                            .toSet()
                    runOnMainSync {
                        val bitmap =
                            android.graphics.Bitmap.createBitmap(
                                96,
                                96,
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                        val canvas = android.graphics.Canvas(bitmap)
                        val pixels = IntArray(96 * 96)
                        try {
                            for (name in names) {
                                bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                                val icon = SvgIcon(appContext, name)
                                val spec =
                                    android.view.View.MeasureSpec.makeMeasureSpec(
                                        96,
                                        android.view.View.MeasureSpec.EXACTLY,
                                    )
                                icon.measure(spec, spec)
                                icon.layout(0, 0, 96, 96)
                                icon.draw(canvas)
                                bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
                                check(pixels.any { android.graphics.Color.alpha(it) > 0 }) {
                                    "SVG vector renders empty: $name"
                                }
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    result.putString(
                        "checks",
                        "Radar tap/drag/accessibility click dispatch; favourite-card selection/delete; observed/forecast fixture decoding; themed launcher mask rendering; every SVG-backed native vector inflates and renders, including all MOSMIX conditions, intensities and UI icons",
                    )
                }
                "hourly-refresh" -> {
                    fun views(root: android.view.View): List<android.view.View> =
                        listOf(root) +
                            if (root is android.view.ViewGroup)
                                (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
                            else emptyList()
                    fun timeline(): android.widget.HorizontalScrollView =
                        views(activity.window.decorView)
                            .filterIsInstance<android.widget.HorizontalScrollView>()
                            .first()
                    fun refresh(): android.widget.Button =
                        views(activity.window.decorView.findViewWithTag("forecast-details"))
                            .filterIsInstance<android.widget.Button>()
                            .first()
                    click("Week")
                    click("Today")
                    waitFor("hourly table") {
                        has("Temperature ") && has("Hourly forecast timeline")
                    }
                    waitFor("initial hour scroll") {
                        var positioned = false
                        runOnMainSync {
                            positioned =
                                timeline().scrollX > 0 ||
                                    Times.format(
                                        AppPlatform.now(),
                                        "HH",
                                        PlaceStore(appContext).selected().zone,
                                    ) == "00"
                        }
                        positioned
                    }
                    val date =
                        Times.format(
                            AppPlatform.now(),
                            "EEEE, dd MMM yyyy",
                            PlaceStore(appContext).selected().zone,
                        )
                    check(has(date) && !has("HOURLY FORECAST") && !has("00:00–24:00"))
                    waitFor("ready to refresh", 30000) {
                        var ready = false
                        runOnMainSync { ready = refresh().isEnabled }
                        ready
                    }
                    var offset = 0
                    runOnMainSync {
                        val bounds = Rect()
                        timeline().getGlobalVisibleRect(bounds)
                        check(bounds.right == activity.window.decorView.width) {
                            "Hourly table must reach the right edge"
                        }
                        timeline().scrollTo(appContext.dp(112), 0)
                        offset = timeline().scrollX
                        refresh().performClick()
                    }
                    waitFor("refresh complete", 30000) {
                        var ready = false
                        runOnMainSync { ready = refresh().isEnabled && timeline().width > 0 }
                        ready
                    }
                    waitForIdleSync()
                    runOnMainSync {
                        check(timeline().scrollX == offset) { "Refresh moved the hourly table" }
                    }
                    result.putString(
                        "checks",
                        "opens at current hour; date heading; right edge; horizontal offset retained through refresh",
                    )
                }
                "day-forecast" -> {
                    fun views(root: android.view.View): List<android.view.View> =
                        listOf(root) +
                            if (root is android.view.ViewGroup)
                                (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
                            else emptyList()
                    fun cardTexts(): List<String> {
                        var texts = listOf<String>()
                        runOnMainSync {
                            val card =
                                activity.window.decorView.findViewWithTag<android.view.View>(
                                    "forecast-details"
                                )
                            if (card != null)
                                texts =
                                    views(card).filterIsInstance<android.widget.TextView>().map {
                                        it.text.toString()
                                    }
                        }
                        return texts
                    }
                    click("Today")
                    waitFor("current forecast") { has("Temperature ") }
                    check(
                        has(
                            Times.format(
                                AppPlatform.now(),
                                "EEEE, dd MMM yyyy",
                                PlaceStore(appContext).selected().zone,
                            )
                        ) && !has("NEXT 24 HOURS")
                    )
                    var dataRows = false
                    runOnMainSync {
                        dataRows =
                            activity.window.decorView.findViewWithTag<android.view.View>(
                                "hourly-probability"
                            ) != null &&
                                activity.window.decorView.findViewWithTag<android.view.View>(
                                    "hourly-gusts"
                                ) != null
                    }
                    check(dataRows) { "Available precipitation probability and gusts are missing" }
                    waitFor("calculated sunrise and sunset", 10000) {
                        has("Sunrise ") && has("Sunset ")
                    }
                    waitFor("stable forecast details", 90000) {
                        var ready = false
                        runOnMainSync {
                            val card =
                                activity.window.decorView.findViewWithTag<android.view.View>(
                                    "forecast-details"
                                )
                            ready =
                                card != null &&
                                    views(card).filterIsInstance<android.widget.Button>().any {
                                        it.text.toString() == "REFRESH DWD" && it.isEnabled
                                    }
                        }
                        ready
                    }
                    val today = cardTexts()
                    check(today.isNotEmpty() && today.none { it.contains("DATA TRANSMISSION") })
                    check(today.any { it == "Timezone" } && today.any { it == "Last updated" })
                    check(today.none { it in listOf("Issue", "Valid", "Received") })
                    click("Week")
                    waitFor("week table") {
                        has("Maximum temperature") && has("Minimum temperature")
                    }
                    check(today == cardTexts()) {
                        "Forecast details must be identical in Today and Week"
                    }
                    check(!has("Sunrise ") && !has("Sunset ")) {
                        "Solar card belongs only to Today"
                    }
                    click("Today")
                    result.putString(
                        "checks",
                        "local-midnight through tomorrow-noon columns; precipitation probability and gust rows; local sunrise/sunset card; identical formatted details in Today/Week without DATA TRANSMISSION",
                    )
                }
                "header-weather" -> {
                    click("Today")
                    waitFor("current weather") { has("Temperature ") }
                    var name: android.widget.TextView? = null
                    var weather: android.view.View? = null
                    var scroll: android.widget.ScrollView? = null
                    runOnMainSync {
                        name = activity.window.decorView.findViewWithTag("place-name")
                        weather = activity.window.decorView.findViewWithTag("header-weather")
                        fun views(root: android.view.View): List<android.view.View> =
                            listOf(root) +
                                if (root is android.view.ViewGroup)
                                    (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
                                else emptyList()
                        scroll =
                            views(activity.window.decorView)
                                .filterIsInstance<android.widget.ScrollView>()
                                .first { it.visibility == android.view.View.VISIBLE }
                    }
                    check(name != null && weather != null) {
                        "Current weather must be in the shared location header"
                    }
                    var sourceView: android.widget.TextView? = null
                    runOnMainSync {
                        sourceView = activity.window.decorView.findViewWithTag("dwd-source")
                    }
                    val source = sourceView
                    check(source != null) { "Header must credit the DWD source" }
                    check(source.contentDescription == "Quelle: Deutscher Wetterdienst") {
                        "DWD source note needs an accessible label"
                    }
                    check(!contentHasTemperature(activity)) {
                        "Today still contains the duplicate current-weather card"
                    }
                    val before = Rect()
                    runOnMainSync { weather!!.getGlobalVisibleRect(before) }
                    nodes()
                        .first { it.className == "android.widget.ScrollView" }
                        .performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    val after = Rect()
                    runOnMainSync { weather!!.getGlobalVisibleRect(after) }
                    check(before == after) { "Header weather moved while scrolling" }
                    for (page in listOf("Week", "Radar", "Places", "Today")) {
                        click(page)
                        check(has("Temperature ") && has("arrow shows wind flow")) {
                            "Weather header missing on $page"
                        }
                    }
                    result.putString(
                        "checks",
                        "no duplicate Today card; header stays fixed and shared across tabs",
                    )
                }
                "forecast-status" -> {
                    fun views(root: android.view.View): List<android.view.View> =
                        listOf(root) +
                            if (root is android.view.ViewGroup)
                                (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
                            else emptyList()
                    fun tagged(tag: String): android.view.View? =
                        activity.window.decorView.findViewWithTag(tag)
                    fun anchor(page: String): android.view.View =
                        tagged(if (page == "Week") "week-row:0" else "today-date")
                            ?: error("Missing $page anchor")
                    fun firstChild(tag: String): android.view.View? =
                        (tagged("forecast-body") as? android.view.ViewGroup)
                            ?.getChildAt(0)
                            ?.takeIf { it.tag == tag }
                    for (page in listOf("Today", "Week")) {
                        click(page)
                        val tag = if (page == "Week") "week-row:0" else "today-date"
                        waitFor("forecast ready to refresh") {
                            var ready = false
                            runOnMainSync {
                                val body = tagged("forecast-body") as? android.view.ViewGroup
                                ready =
                                    body != null &&
                                        tagged(tag)?.isShown == true &&
                                        views(body).filterIsInstance<android.widget.Button>().any {
                                            it.text.toString() == "REFRESH DWD" && it.isEnabled
                                        }
                            }
                            ready
                        }
                        val before = Rect()
                        runOnMainSync {
                            activity.window.decorView
                                .findViewWithTag<android.widget.ScrollView>("forecast-scroll")
                                ?.scrollTo(0, 0)
                            val anchorView = anchor(page)
                            check(anchorView.isShown)
                            check(anchorView.getGlobalVisibleRect(before)) {
                                "$page anchor is not visible"
                            }
                            check(firstChild(tag) === anchorView) {
                                "Status banner inserted above $page content before refresh"
                            }
                            views(tagged("forecast-body")!!)
                                .filterIsInstance<android.widget.Button>()
                                .first { it.text.toString() == "REFRESH DWD" }
                                .performClick()
                        }
                        var bannerAbsent = false
                        runOnMainSync {
                            val anchorView = firstChild(tag)
                            bannerAbsent = anchorView != null && anchorView.isShown
                        }
                        check(bannerAbsent) {
                            "Status banner inserted above $page content during refresh"
                        }
                        waitFor("refreshed content laid out", 3000) {
                            var measured = false
                            runOnMainSync {
                                val anchorView = firstChild(tag)
                                val box = Rect()
                                measured =
                                    anchorView != null &&
                                        anchorView.height > 0 &&
                                        anchorView.getGlobalVisibleRect(box)
                            }
                            measured
                        }
                        val after = Rect()
                        runOnMainSync {
                            val anchorView = anchor(page)
                            check(anchorView.isShown)
                            check(anchorView.getGlobalVisibleRect(after)) {
                                "$page anchor is not visible after refresh"
                            }
                        }
                        check(before.top == after.top) {
                            "$page content moved when refresh status changed"
                        }
                    }
                    click("Today")
                    result.putString(
                        "checks",
                        "Today/Week refresh inserts no status banner; tagged content anchor stays shown and fixed",
                    )
                }
                "recenter-probe" -> {
                    click("Radar")
                    val place = PlaceStore(appContext).selected()
                    val action =
                        radarMapNode().actionList.first {
                            it.label?.toString() == "Recenter on selected location"
                        }
                    check(radarMapNode().performAction(action.id))
                    waitForIdleSync()
                    val center =
                        Regex("Center ([-0-9.]+), ([-0-9.]+);")
                            .find(radarMapNode().contentDescription.toString())!!
                    val dy =
                        kotlin.math.abs(
                            center.groupValues[1].toDouble() -
                                place.lat.coerceIn(-85.05112878, 85.05112878)
                        )
                    val dx =
                        kotlin.math.abs(
                            center.groupValues[2].toDouble() -
                                (if (place.lon == 180.0) -180.0 else place.lon)
                        )
                    result.putDouble("latitude_difference_degrees", dy)
                    result.putDouble("longitude_difference_degrees", minOf(dx, 360 - dx))
                }
                "navigation" -> {
                    waitFor("current forecast") { has("Temperature ") }
                    check(
                        !has("CURRENT FORECAST") &&
                            !has("DWD  /  MOSMIX") &&
                            !has("SWIPE TO EXPLORE") &&
                            !has("LOCAL  ")
                    ) {
                        "Removed forecast labels remain"
                    }
                    fun tab(name: String) =
                        nodes().first {
                            it.isClickable && it.contentDescription?.toString() == name
                        }
                    check(tab("Today").isSelected)
                    val before = Rect()
                    tab("Today").getBoundsInScreen(before)
                    nodes()
                        .first { it.className == "android.widget.ScrollView" }
                        .performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(300)
                    val after = Rect()
                    tab("Today").getBoundsInScreen(after)
                    check(before == after) { "Tab bar moved with forecast content" }
                    click("Places")
                    check(
                        tab("Places").isSelected && has("FAVOURITES") && !has("ENTER COORDINATES")
                    )
                    click("SEARCH CITY")
                    check(has("Search city"))
                    click("Close dialog")
                    check(tab("Places").isSelected && has("FAVOURITES"))
                    click("Week")
                    check(
                        tab("Week").isSelected &&
                            has("Maximum temperature") &&
                            has("Minimum temperature")
                    )
                    click("Radar")
                    check(tab("Radar").isSelected && has("Play or pause radar animation"))
                    click("Today")
                    check(tab("Today").isSelected)
                    repeat(20) {
                        nodes()
                            .first { it.className == "android.widget.ScrollView" }
                            .performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    }
                    waitFor("forecast restored") { has("Temperature ") }
                    check(has("arrow shows wind flow")) { "Wind direction symbol is missing" }
                    result.putString(
                        "checks",
                        "removed forecast labels; wind compass accessible; all four tabs select their screen; bottom tab bar fixed during scroll; Places search dialog cancels back to Places; forecast restored",
                    )
                }
                "lifecycle" -> {
                    fun selected(name: String) =
                        nodes().any { it.contentDescription?.toString() == name && it.isSelected }
                    click("Places")
                    check(selected("Places") && has("FAVOURITES"))
                    val monitor = addMonitor(MainActivity::class.java.name, null, false)
                    try {
                        runOnMainSync { activity.recreate() }
                        check(waitForMonitorWithTimeout(monitor, 10000) != null) {
                            "Activity did not recreate"
                        }
                        waitForIdleSync()
                        waitFor("Places restored after recreation", 10000) {
                            selected("Places") && has("FAVOURITES")
                        }
                    } finally {
                        removeMonitor(monitor)
                    }
                    click("Today")
                    check(selected("Today"))

                    // Regressions: tracked notices dialog during Activity destruction.
                    click("Places")
                    click("DATA & LICENCES")
                    waitFor("licence dialog", 5000) { has("Application licence (MIT)") }
                    click("Close")
                    waitFor("licence dialog closes", 3000) {
                        !has("Application licence (MIT)")
                    }
                    click("DATA & LICENCES")
                    waitFor("licence dialog reopens", 5000) {
                        has("Application licence (MIT)")
                    }
                    shell("logcat -c")
                    var owner: android.app.Activity = activity
                    val dialogMonitor = addMonitor(MainActivity::class.java.name, null, false)
                    try {
                        runOnMainSync { owner.recreate() }
                        owner =
                            waitForMonitorWithTimeout(dialogMonitor, 10000)
                                ?: error("Activity did not recreate with an open notices dialog")
                        waitForIdleSync()
                    } finally {
                        removeMonitor(dialogMonitor)
                    }
                    // Destroying the focused dialog window detaches this instrumentation's
                    // accessibility connection, so verify the replacement Activity's own
                    // view state instead of the accessibility tree.
                    var placesShown = false
                    var forecastShown = false
                    var dialog: android.app.AlertDialog? = null
                    runOnMainSync {
                        val decor = owner.window.decorView
                        placesShown =
                            decor.findViewWithTag<android.view.View>("places-scroll")?.isShown ==
                                true
                        forecastShown =
                            decor.findViewWithTag<android.view.View>("forecast-scroll")?.isShown ==
                                true
                        dialog =
                            MainActivity::class
                                .java
                                .getDeclaredField("noticesDialog")
                                .apply { isAccessible = true }
                                .get(owner) as? android.app.AlertDialog
                    }
                    check(activity.isDestroyed) { "Original Activity was not destroyed" }
                    check(placesShown && !forecastShown) {
                        "Places did not survive recreation with an open notices dialog"
                    }
                    check(dialog?.isShowing != true) {
                        "Notices dialog outlived its destroyed Activity"
                    }
                    SystemClock.sleep(1000)
                    val windowLog = shell("logcat -d -s WindowManager")
                    check(
                        !(windowLog.contains("WindowLeaked") &&
                            windowLog.contains("app.drizziq.MainActivity"))
                    ) {
                        "Destroyed Activity leaked its notices window"
                    }
                    result.putString(
                        "checks",
                        "Places selection and content survive Activity recreation; Today remains reachable; notices dialog closes and reopens; recreation with the dialog open destroys the original Activity, keeps Places, and dismisses the dialog without a leaked window",
                    )
                }
                "radar-corruption" -> {

                    click("Radar")
                    waitFor("radar sequence for failed frame", 180000) {
                        nodes().any {
                            it.className == "android.widget.SeekBar" &&
                                (it.rangeInfo?.max ?: 0f) > 0f
                        }
                    }
                    waitFor("radar refresh settles", 30000) { !Http.downloads.active }
                    SystemClock.sleep(1500)
                    val data = java.io.File(appContext.filesDir, "data")
                    val manifest = java.io.File(data, "radar.json")
                    check(manifest.isFile) { "Radar manifest is missing" }
                    val frames = org.json.JSONObject(manifest.readText()).getJSONArray("frames")
                    val target = frames.length() - 1
                    check(target > 0) { "Radar timeline needs more than one frame" }
                    val frameFile =
                        java.io.File(data, frames.getJSONObject(target).getString("file"))
                    check(frameFile.isFile) { "Selected radar frame is missing" }
                    val original = frameFile.readBytes()
                    fun views(root: android.view.View): List<android.view.View> =
                        listOf(root) +
                            if (root is android.view.ViewGroup)
                                (0 until root.childCount).flatMap {
                                    views(root.getChildAt(it))
                                }
                            else emptyList()
                    try {
                        frameFile.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5))
                        val bounds =
                            Rect().apply {
                                nodes()
                                    .first { it.className == "android.widget.SeekBar" }
                                    .getBoundsInScreen(this)
                            }
                        drag(
                            bounds.left + 2f,
                            bounds.centerY().toFloat(),
                            bounds.right - 2f,
                            bounds.centerY().toFloat(),
                        )
                        waitFor("failed frame decode", 15000) {
                            has("Radar frame unavailable")
                        }
                        val timeline = nodes().first { it.className == "android.widget.SeekBar" }
                        check(timeline.rangeInfo.current.toInt() < target) {
                            "Timeline must continue to identify the retained visible frame"
                        }
                        check(timeline.contentDescription.toString().contains("unavailable")) {
                            "Accessible timeline must report the failed frame"
                        }
                        var frame: RadarFrame? = null
                        runOnMainSync {
                            frame =
                                views(activity.window.decorView)
                                    .filterIsInstance<RadarMap>()
                                    .single()
                                    .frame
                        }
                        check(frame != null) {
                            "Failed decode discarded the last successful radar frame"
                        }
                    } finally {
                        frameFile.writeBytes(original)
                    }
                    val repaired =
                        Rect().apply {
                            nodes()
                                .first { it.className == "android.widget.SeekBar" }
                                .getBoundsInScreen(this)
                        }
                    drag(
                        repaired.left + 2f,
                        repaired.centerY().toFloat(),
                        repaired.right - 2f,
                        repaired.centerY().toFloat(),
                    )
                    waitFor("repaired frame displays", 15000) {
                        !has("Radar frame unavailable")
                    }
                    result.putString(
                        "checks",
                        "corrupt selected frame retains the last successful map and matching timeline; accessible description reports the failure; repaired frame displays",
                    )
                }
                "geocoding-probe" -> {
                    try {
                        val reply = GeocodeHttp().fetch(Geocoding.url("München"))
                        result.putInt("http_status", reply.status)
                        result.putInt("response_bytes", reply.body.size)
                        val json = org.json.JSONObject(reply.body.toString(Charsets.UTF_8))
                        val first = json.optJSONArray("results")?.optJSONObject(0)
                        if (first != null)
                            result.putString(
                                "field_types",
                                listOf("id", "latitude", "longitude", "name", "timezone")
                                    .joinToString { "$it:${first.opt(it)?.javaClass?.simpleName}" },
                            )
                        result.putInt(
                            "candidate_count",
                            Geocoding.parse(reply.body.toString(Charsets.UTF_8)).size,
                        )
                    } catch (error: Exception) {
                        error(
                            "Geocoding diagnostic: " +
                                generateSequence<Throwable>(error) { it.cause }
                                    .joinToString(" -> ") { it.javaClass.simpleName }
                        )
                    }
                }
                "places-integration" -> {
                    val prefs = appContext.getSharedPreferences("places", 0)
                    val camera = appContext.getSharedPreferences("radar-viewport", 0)
                    val oldSelected = prefs.getString("selected", null)
                    val oldFavourites = prefs.getString("favourites", null)
                    val oldCamera = camera.getString("camera", null)
                    val coarse =
                        appContext.checkSelfPermission(
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    val fine =
                        appContext.checkSelfPermission(
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    var owner = activity
                    try {
                        click("Places")
                        click("SEARCH CITY")
                        enter("CITY NAME", "München")
                        submitSearch()
                        waitFor("candidate list", 45000) {
                            nodes().any {
                                it.contentDescription
                                    ?.toString()
                                    ?.startsWith("Select city result ") == true
                            }
                        }
                        val candidate =
                            nodes()
                                .first {
                                    it.contentDescription
                                        ?.toString()
                                        ?.startsWith("Select city result ") == true
                                }
                                .contentDescription
                                .toString()
                        val detail =
                            Regex(
                                    "Select city result ([0-9]+); .*; latitude ([-0-9.Ee+]+); longitude ([-0-9.Ee+]+)"
                                )
                                .matchEntire(candidate)!!
                        click(candidate)
                        waitFor("explicit result selection") {
                            PlaceStore(appContext).selected().geocodeId ==
                                "open-meteo:${detail.groupValues[1]}"
                        }
                        val selected = PlaceStore(appContext).selected()
                        check(
                            selected.lat == detail.groupValues[2].toDouble() &&
                                selected.lon == detail.groupValues[3].toDouble()
                        ) {
                            "Search result coordinates were changed"
                        }
                        check(selected.zone in java.util.TimeZone.getAvailableIDs())
                        waitFor("nearest forecast distance", 90000) { has("km away") }
                        check(has("Open-Meteo") && has("GeoNames"))
                        click("Places")
                        click("SAVE CURRENT AS FAVOURITE")
                        enter("FAVOURITE LABEL", "Geocoding integration snapshot")
                        click("Save")
                        val saved = PlaceStore(appContext).selected()
                        check(saved.geocodeId == selected.geocodeId && saved.geocodeLabelEdited)
                        val monitor = addMonitor(MainActivity::class.java.name, null, false)
                        try {
                            runOnMainSync { owner.recreate() }
                            owner =
                                waitForMonitorWithTimeout(monitor, 10000)
                                    ?: error("Recreation failed")
                            waitForIdleSync()
                        } finally {
                            removeMonitor(monitor)
                        }
                        check(PlaceStore(appContext).selected() == saved)
                        check(PlaceStore(appContext).favourites().any { it == saved })
                        // Opted-in fixture selections replace the removed manual-coordinate UI.
                        fun fixture(place: Place) {
                            runOnMainSync { owner.finish() }
                            waitForIdleSync()
                            PlaceStore(appContext).select(place)
                            owner =
                                startActivitySync(
                                    Intent(appContext, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                        }
                        fixture(Place.MUNICH.copy(label = "Current location"))
                        waitFor("device snapshot uses station name") {
                            has("MUENCHEN STADT") &&
                                !nodes().any { it.text?.toString() == "Current location" }
                        }
                        click("Places")
                        click("SAVE CURRENT AS FAVOURITE")
                        check(
                            nodes().any {
                                it.isEditable &&
                                    it.contentDescription?.toString() == "FAVOURITE LABEL" &&
                                    it.text?.toString() == "MUENCHEN STADT"
                            }
                        )
                        click("Close dialog")
                        fixture(Place("Coverage test", 0.0, 0.0, "UTC"))
                        waitFor("coverage explanation") {
                            has("No DWD forecast point within 50 km")
                        }
                        check(!has("Temperature ")) {
                            "Previous place weather leaked outside coverage"
                        }
                        waitFor("sun times outside DWD coverage", 10000) {
                            has("Sunrise ") && has("Sunset ")
                        }
                        click("Radar")
                        check(has("Precipitation map"))
                        check(
                            appContext.checkSelfPermission(
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == coarse
                        )
                        check(
                            appContext.checkSelfPermission(
                                android.Manifest.permission.ACCESS_FINE_LOCATION
                            ) == fine
                        )
                        result.putString(
                            "checks",
                            "explicit candidate coordinates/zone; nearest forecast distance; linked attribution; edited-provenance favourite and selection survive recreation; device snapshot displays station name; out-of-coverage forecast clears previous weather while radar remains available; original selected data restored",
                        )
                    } finally {
                        runOnMainSync { owner.finish() }
                        waitForIdleSync()
                        check(
                            prefs
                                .edit()
                                .putString("selected", oldSelected)
                                .putString("favourites", oldFavourites)
                                .commit()
                        ) {
                            "Could not restore original places"
                        }
                        check(camera.edit().putString("camera", oldCamera).commit()) {
                            "Could not restore original map"
                        }
                        startActivitySync(
                            Intent(appContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                "station-benchmark" -> {
                    check(
                        appContext.applicationInfo.flags and
                            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0
                    ) {
                        "Benchmark requires a release build"
                    }
                    val start = SystemClock.elapsedRealtimeNanos()
                    val bytes = appContext.assets.open("stations.tsv").use { it.readBytes() }
                    val index = StationIndex.fromTsv(bytes.toString(Charsets.UTF_8))
                    val loadMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    repeat(20) { index.nearestInCoverage(Place.MUNICH) }
                    val samples =
                        (1..200)
                            .map {
                                val before = SystemClock.elapsedRealtimeNanos()
                                check(index.nearestInCoverage(Place.MUNICH)!!.station.id == "10865")
                                (SystemClock.elapsedRealtimeNanos() - before) / 1_000_000.0
                            }
                            .sorted()
                    result.putString("device", android.os.Build.MODEL)
                    result.putInt("api", android.os.Build.VERSION.SDK_INT)
                    result.putDouble("catalogue_load_ms", loadMs)
                    result.putDouble("lookup_median_ms", samples[samples.size / 2])
                    result.putDouble("lookup_p95_ms", samples[189])
                    result.putDouble("lookup_max_ms", samples.last())
                    result.putString(
                        "catalogue_sha256",
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(bytes)
                            .joinToString("") { "%02x".format(it) },
                    )
                    result.putString(
                        "checks",
                        "release build; 20 warmups; 200 measured MOSMIX lookups",
                    )
                }
                "places-search" -> {
                    val original = PlaceStore(appContext).selected()
                    val favourites = PlaceStore(appContext).favourites()
                    val coarse =
                        appContext.checkSelfPermission(
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    val fine =
                        appContext.checkSelfPermission(
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    val platform = PlatformLocationSource(appContext)
                    val eligible = platform.enabledProviders()
                    check(eligible.all { it in listOf("gps", "network") })
                    if (fine != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        check("gps" !in eligible)
                    if (
                        coarse != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                            fine != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        check(eligible.isEmpty())
                        check(
                            runCatching { platform.lastKnown("network") }.exceptionOrNull()
                                is SecurityException
                        )
                        check(
                            runCatching { platform.listen("gps", {}, {}) }.exceptionOrNull()
                                is SecurityException
                        )
                    }
                    click("Places")
                    click("SEARCH CITY")
                    waitFor("search disclosure and attribution", 3000) {
                        has("Open-Meteo") && has("query")
                    }
                    enter("CITY NAME", "M")
                    submitSearch()
                    waitFor("query validation", 3000) { has("2–120") }
                    enter("CITY NAME", "München")
                    submitSearch()
                    waitFor("city candidates", 45000) {
                        nodes().any {
                            it.contentDescription?.toString()?.startsWith("Select city result ") ==
                                true
                        }
                    }
                    check(PlaceStore(appContext).selected() == original) {
                        "Search selected a candidate without a tap"
                    }
                    enter("CITY NAME", "Berlin")
                    waitFor("edited query publishes only its own results", 10000) {
                        val results =
                            nodes()
                                .mapNotNull { it.contentDescription?.toString() }
                                .filter { it.startsWith("Select city result ") }
                        results.any { it.contains("Berlin") } &&
                            results.none { it.contains("München") }
                    }
                    click("Close dialog")
                    click("CLEAR SEARCH CACHE")
                    click("DATA & LICENCES")
                    waitFor("licence dialog", 5000) { has("Application licence (MIT)") }
                    check(
                        has("Quelle: Deutscher Wetterdienst") &&
                            has("Apache License") &&
                            has("GNU General Public License") &&
                            has("jHDF MIT licence")
                    ) {
                        "Licence dialog must include the full notices"
                    }
                    click("Close")
                    check(
                        PlaceStore(appContext).favourites() == favourites &&
                            PlaceStore(appContext).selected() == original
                    )
                    if (
                        coarse != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                            fine != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        click("USE CURRENT LOCATION")
                        if (has("Current location")) {
                            check(has("on this device")) { "Location rationale missing" }
                            click("Close dialog")
                        } else {
                            check(
                                has("Location access") && (has("City search") || has("city search"))
                            )
                            click("Close dialog")
                        }
                    }
                    check(
                        appContext.checkSelfPermission(
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == coarse
                    )
                    check(
                        appContext.checkSelfPermission(
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        ) == fine
                    )
                    check(PlaceStore(appContext).selected() == original)
                    result.putString(
                        "checks",
                        "explicit city search; query validation; candidates require selection; editing/cancel/cache-clear preserve selection and favourites; optional location rationale cancellation preserves permissions",
                    )
                }
                "viewport-save" -> {
                    click("Radar")
                    val original = radarMapNode().contentDescription.toString()
                    radarGestures()
                    val moved = radarMapNode().contentDescription.toString()
                    check(moved != original) { "Persistence test must change the viewport" }
                    check(
                        appContext
                            .getSharedPreferences("radar-test", 0)
                            .edit()
                            .putString("expected", moved)
                            .commit()
                    )
                    result.putString(
                        "checks",
                        "panned and zoomed radar; saved independent expected viewport for force-stop check",
                    )
                }
                "viewport-restore" -> {
                    click("Radar")
                    val expected =
                        appContext.getSharedPreferences("radar-test", 0).getString("expected", null)
                    check(expected != null) { "Run viewport-save before viewport-restore" }
                    check(radarMapNode().contentDescription.toString() == expected) {
                        "Viewport reset after process restart: expected $expected; got ${radarMapNode().contentDescription}"
                    }
                    val monitor = addMonitor(MainActivity::class.java.name, null, false)
                    try {
                        runOnMainSync { activity.recreate() }
                        check(waitForMonitorWithTimeout(monitor, 10000) != null)
                        waitForIdleSync()
                        waitFor("radar viewport survives Activity recreation", 10000) {
                            has("Precipitation map") &&
                                radarMapNode().contentDescription.toString() == expected
                        }
                    } finally {
                        removeMonitor(monitor)
                    }
                    result.putString(
                        "checks",
                        "radar center and fractional zoom survive force-stop/relaunch and Activity recreation",
                    )
                }
                "radar-gestures" -> {
                    click("Radar")
                    radarGestures()
                    result.putString(
                        "checks",
                        "one-finger pan; two-finger zoom in and out; double-tap zoom",
                    )
                }
                "radar" -> {
                    click("Radar")
                    val barBounds =
                        Rect().apply {
                            nodes()
                                .first { it.className == "android.widget.SeekBar" }
                                .getBoundsInScreen(this)
                        }
                    val selectedPlace = PlaceStore(appContext).selected()
                    radarGestures()
                    val recenter =
                        radarMapNode().actionList.first {
                            it.label?.toString() == "Recenter on selected location"
                        }
                    check(radarMapNode().performAction(recenter.id))
                    waitFor("accessible recenter restores selected-place center", 3000) {
                        // A Mercator round-trip may cross a six-decimal rounding midpoint.
                        // Require agreement within half of the displayed coordinate unit.
                        val center =
                            Regex("Center ([-0-9.]+), ([-0-9.]+);")
                                .find(radarMapNode().contentDescription.toString())!!
                        val dy =
                            kotlin.math.abs(
                                center.groupValues[1].toDouble() -
                                    selectedPlace.lat.coerceIn(-85.05112878, 85.05112878)
                            )
                        val dx =
                            kotlin.math.abs(center.groupValues[2].toDouble() - selectedPlace.lon)
                        dy <= 0.500001e-6 && minOf(dx, 360 - dx) <= 0.500001e-6
                    }
                    waitFor("radar sequence", 180000) {
                        nodes().any {
                            it.className == "android.widget.SeekBar" &&
                                (it.rangeInfo?.max ?: 0f) > 0f
                        }
                    }
                    fun bar() = nodes().first { it.className == "android.widget.SeekBar" }
                    drag(
                        barBounds.left + 2f,
                        barBounds.centerY().toFloat(),
                        barBounds.right - 2f,
                        barBounds.centerY().toFloat(),
                    )
                    waitFor("last frame selected") {
                        bar().rangeInfo.current == bar().rangeInfo.max
                    }
                    waitFor("radar player foreground", 5000) {
                        var focused = false
                        runOnMainSync { focused = activity.hasWindowFocus() }
                        focused
                    }
                    click("Play or pause radar animation")
                    check(
                        nodes()
                            .first {
                                it.contentDescription?.toString() == "Play or pause radar animation"
                            }
                            .isSelected
                    ) {
                        "Play icon must reflect playback state"
                    }
                    waitFor("playback loops", 20000) {
                        val button =
                            nodes().firstOrNull {
                                it.contentDescription?.toString() == "Play or pause radar animation"
                            }
                        check(button?.isSelected != false) { "Playback stopped before looping" }
                        bar().rangeInfo.current < bar().rangeInfo.max
                    }
                    click("Play or pause radar animation")
                    val paused = bar().rangeInfo.current
                    SystemClock.sleep(1000)
                    check(bar().rangeInfo.current == paused) { "Pause did not stop playback" }
                    result.putString(
                        "checks",
                        "pan; pinch in/out; double tap; scrub to last frame; playback loops and pauses",
                    )
                }
                "forecast" -> {
                    click("Today")
                    waitFor("today forecast") { has("Temperature ") }
                    check(!has("Maximum temperature") && !has("Minimum temperature"))
                    fun scroll(): AccessibilityNodeInfo =
                        nodes().first { it.className == "android.widget.ScrollView" }
                    check(
                        !has("HOURLY FORECAST") &&
                            has(
                                Times.format(
                                    AppPlatform.now(),
                                    "EEEE, dd MMM yyyy",
                                    PlaceStore(appContext).selected().zone,
                                )
                            )
                    )
                    val hourly =
                        nodes().firstOrNull {
                            it.className == "android.widget.HorizontalScrollView"
                        }
                    check(hourly != null) { "Hourly timeline is not horizontally scrollable" }
                    repeat(6) {
                        hourly.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(120)
                    }
                    repeat(4) {
                        scroll().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(150)
                    }
                    waitFor("hourly provenance footer") {
                        has("Timezone") && has("Last updated")
                    }
                    check(!has("no DWD value") && !has("Relative humidity is not supplied")) {
                        "Removed hourly explanation remains"
                    }
                    click("Week")
                    waitFor("week ahead") {
                        has("Maximum temperature") && has("Minimum temperature")
                    }
                    check(!contentHasTemperature(activity) && !has("HOURLY FORECAST"))
                    repeat(8) {
                        scroll().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(120)
                    }
                    val zone = PlaceStore(appContext).selected().zone
                    val last =
                        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(zone)).apply {
                            timeInMillis = AppPlatform.now()
                            add(java.util.Calendar.DAY_OF_MONTH, 6)
                        }
                    check(has(Times.format(last.timeInMillis, "dd MMM", zone))) {
                        "Seventh forecast day is not reachable"
                    }
                    check(has("Timezone") && has("Last updated"))
                    val monitor = addMonitor(MainActivity::class.java.name, null, false)
                    try {
                        runOnMainSync { activity.recreate() }
                        check(waitForMonitorWithTimeout(monitor, 10000) != null)
                        waitFor("Week restored after recreation") {
                            nodes().any {
                                it.contentDescription?.toString() == "Week" && it.isSelected
                            }
                        }
                    } finally {
                        removeMonitor(monitor)
                    }
                    click("Today")
                    repeat(20) {
                        scroll().performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    }
                    result.putString(
                        "checks",
                        "Today contains current conditions and aligned hourly ribbon; horizontal hour scrolling and omitted missing humidity; Week contains seven reachable daily rows without current/hourly content; provenance footer on both; Week survives recreation",
                    )
                }
                "main" -> {
                    waitFor("live Munich forecast") { has("Temperature ") && has("LIVE") }
                    waitFor("Munich forecast") { has("MUENCHEN STADT") && has("LIVE") }
                    click("PLACES")
                    click("SAVE CURRENT AS FAVOURITE")
                    enter("FAVOURITE LABEL", "Munich home")
                    click("Save")
                    click("PLACES")
                    click("SEARCH CITY")
                    enter("CITY NAME", "Berlin")
                    submitSearch()
                    waitFor("Berlin city result", 45000) {
                        nodes().any {
                            it.contentDescription
                                ?.toString()
                                ?.startsWith("Select city result 2950159;") == true
                        }
                    }
                    click(
                        nodes()
                            .first {
                                it.contentDescription
                                    ?.toString()
                                    ?.startsWith("Select city result 2950159;") == true
                            }
                            .contentDescription
                            .toString()
                    )
                    waitFor("Berlin live forecast") {
                        has("BERLIN") && has("LIVE") && has("Temperature ")
                    }
                    click("PLACES")
                    click("SAVE CURRENT AS FAVOURITE")
                    enter("FAVOURITE LABEL", "Berlin saved")
                    click("Save")
                    click("Radar")
                    waitFor("radar frames", 180000) { has("OBSERVED") && has("frames") }
                    val map =
                        nodes().first {
                            it.contentDescription?.toString()?.startsWith("Precipitation map") ==
                                true
                        }
                    val bounds = Rect()
                    map.getBoundsInScreen(bounds)
                    fun viewport() =
                        nodes()
                            .first {
                                it.contentDescription
                                    ?.toString()
                                    ?.startsWith("Precipitation map") == true
                            }
                            .contentDescription
                            .toString()
                    val original = viewport()
                    drag(
                        bounds.centerX().toFloat(),
                        bounds.centerY().toFloat(),
                        bounds.centerX() + 170f,
                        bounds.centerY() + 120f,
                    )
                    check(viewport() != original) { "Map did not pan" }
                    val panned = viewport()
                    pinch(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    check(viewport() != panned) { "Pinch did not change viewport" }
                    val recenter =
                        radarMapNode().actionList.first {
                            it.label?.toString() == "Recenter on selected location"
                        }
                    val beforeRecenter = viewport()
                    check(radarMapNode().performAction(recenter.id))
                    waitFor("accessible recenter") { viewport() != beforeRecenter }
                    val centered = viewport()
                    check(
                        radarMapNode().performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    )
                    waitFor("accessible zoom out") { viewport() != centered }
                    check(radarMapNode().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    val bar = nodes().first { it.className == "android.widget.SeekBar" }
                    val r = Rect()
                    bar.getBoundsInScreen(r)
                    drag(r.left + 10f, r.centerY().toFloat(), r.right - 10f, r.centerY().toFloat())
                    waitFor("last forecast frame") { has("FORECAST +120m") }
                    click("Play or pause radar animation")
                    waitFor("animation loops", 10000) { has("OBSERVED") }
                    click("Play or pause radar animation")
                    click("PLACES")
                    click("Select favourite Munich home")
                    result.putString(
                        "checks",
                        "live Munich; explicit Berlin search selection; live Berlin; save two favourites; radar pan; two-finger pinch; accessible zoom and recenter; scrub to +120; playback loop; select favourite",
                    )
                }
                "persistence" -> {
                    click("PLACES")
                    check(has("Berlin saved"))
                    check(has("Munich home"))
                    click("Select favourite Berlin saved")
                    waitFor("selected persisted Berlin") { has("Temperature ") && has("BERLIN") }
                    click("PLACES")
                    click("Delete favourite Berlin saved")
                    check(
                        nodes().none {
                            it.contentDescription?.toString() == "Select favourite Berlin saved"
                        }
                    )
                    click("Select favourite Munich home")
                    result.putString(
                        "checks",
                        "favourites survived force-stop; selected Berlin; deleted Berlin; selected Munich",
                    )
                }
                "offline" -> {
                    waitFor("offline cached forecast") {
                        has("STALE") && has("Temperature ")
                    }
                    check(
                        !has("No forecast received") && !has("Receiving the atmosphere") && has("°")
                    ) {
                        "Offline refresh must retain cached forecast values"
                    }
                    click("Radar")
                    waitFor("offline cached radar") {
                        has("Offline radar") && has("STALE") && has("frames") && has("OBSERVED")
                    }
                    click("Play or pause radar animation")
                    SystemClock.sleep(1800)
                    click("Play or pause radar animation")
                    result.putString(
                        "checks",
                        "airplane mode retains cached forecast values; Today STALE; Radar offline notice; cached playback",
                    )
                }
                "stopped-network" -> {
                    // Full-only: the runner has disabled connectivity before this phase and
                    // restores the original radios afterwards.
                    waitFor("offline cache before stop", 30000) {
                        has("STALE") && has("Temperature ")
                    }
                    check(!Http.downloads.active) {
                        "Downloads were already running before the Activity stopped"
                    }
                    val data = java.io.File(appContext.filesDir, "data")
                    fun cache(): Map<String, Long> =
                        (data.listFiles() ?: emptyArray())
                            .filter { it.isFile }
                            .associate { it.name to it.lastModified() }
                    val beforeStop = cache()
                    shell("input keyevent KEYCODE_HOME")
                    waitFor("Activity stopped", 15000) {
                        var focused = true
                        runOnMainSync { focused = activity.window.decorView.hasWindowFocus() }
                        !focused && !has("DRIZZIQ")
                    }
                    shell("cmd connectivity airplane-mode disable")
                    shell("svc wifi enable")
                    shell("svc data enable")
                    val manager =
                        appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                            as android.net.ConnectivityManager
                    fun validated(): Boolean {
                        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
                        return capabilities?.hasCapability(
                            android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
                        ) == true
                    }
                    waitFor("connectivity restored while stopped", 30000) { validated() }
                    var downloadsWhileStopped = false
                    val quietUntil = SystemClock.uptimeMillis() + 4000
                    while (SystemClock.uptimeMillis() < quietUntil) {
                        if (Http.downloads.active) {
                            downloadsWhileStopped = true
                            break
                        }
                        SystemClock.sleep(50)
                    }
                    check(!downloadsWhileStopped) {
                        "Network callback started downloads while the Activity was stopped"
                    }
                    check(cache() == beforeStop) {
                        "A stopped Activity rewrote its cache after connectivity returned"
                    }
                    shell("am start -W --activity-single-top -n app.drizziq/.MainActivity")
                    waitFor("foreground refresh after connectivity return", 60000) {
                        has("LIVE") && has("Temperature ")
                    }
                    result.putString(
                        "checks",
                        "no downloads or cache writes while stopped; connectivity return reconciled by one foreground refresh",
                    )
                }
                "restore" -> {
                    waitFor("live forecast recovered") { has("LIVE") && has("Temperature ") }
                    result.putString("checks", "connectivity recovery returns forecast to live")
                }
                else -> error("Unknown phase: $phase")
            }
            if (fixture) {
                check(PhoneFixtures.unexpected.isEmpty()) { "Unexpected fixture request" }
                result.putInt("fixture_requests", PhoneFixtures.requests.get())
                result.putInt("external_requests", 0)
            }
            result.putString("result", "PASS")
            finish(0, result)
        } catch (t: Throwable) {
            result.putString("result", "FAIL")
            result.putString("error", t.stackTraceToString())
            finish(-1, result)
        }
    }
}
