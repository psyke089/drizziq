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
    private var phase = "main"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        phase = arguments?.getString("phase") ?: "main"
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
        val startDistance = targetContext.dp(if (reverse) 110 else 32).toFloat()
        val endDistance = targetContext.dp(if (reverse) 32 else 110).toFloat()
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

    override fun onStart() {
        val result = Bundle()
        try {
            val activity =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            waitFor("initial app") { has("PLACES") }
            when (phase) {
                "forecast-status" -> {
                    fun views(root: android.view.View): List<android.view.View> =
                        listOf(root) +
                            if (root is android.view.ViewGroup)
                                (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
                            else emptyList()
                    fun body(): android.view.ViewGroup {
                        val scroll =
                            views(activity.window.decorView)
                                .filterIsInstance<android.widget.ScrollView>()
                                .first { it.visibility == android.view.View.VISIBLE }
                        return scroll.getChildAt(0) as android.view.ViewGroup
                    }
                    for (page in listOf("Today", "Week")) {
                        click(page)
                        waitFor("forecast ready to refresh") {
                            var ready = false
                            runOnMainSync {
                                ready =
                                    views(body()).filterIsInstance<android.widget.Button>().any {
                                        it.text.toString() == "REFRESH DWD" && it.isEnabled
                                    }
                            }
                            ready
                        }
                        val before = Rect()
                        var bannerAbsent = false
                        runOnMainSync {
                            val old = body().getChildAt(0)
                            old.getGlobalVisibleRect(before)
                            views(body())
                                .filterIsInstance<android.widget.Button>()
                                .first { it.text.toString() == "REFRESH DWD" }
                                .performClick()
                            bannerAbsent = body().getChildAt(0) is android.view.ViewGroup
                        }
                        check(bannerAbsent) {
                            "Status banner inserted above $page content during refresh"
                        }
                        waitFor("refreshed content laid out", 3000) {
                            var measured = false
                            runOnMainSync { measured = body().getChildAt(0).height > 0 }
                            measured
                        }
                        val after = Rect()
                        runOnMainSync { body().getChildAt(0).getGlobalVisibleRect(after) }
                        check(before.top == after.top) {
                            "$page content moved when refresh status changed"
                        }
                    }
                    click("Today")
                    result.putString(
                        "checks",
                        "Today/Week refresh inserts no status banner; content top stays fixed",
                    )
                }
                "copy-cleanup" -> {
                    fun views(root: android.view.View): List<android.view.View> =
                        listOf(root) +
                            if (root is android.view.ViewGroup)
                                (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
                            else emptyList()
                    fun texts(): List<String> {
                        var result = listOf<String>()
                        runOnMainSync {
                            result =
                                views(activity.window.decorView)
                                    .filterIsInstance<android.widget.TextView>()
                                    .map { it.text.toString() }
                        }
                        return result
                    }
                    click("Today")
                    waitFor("today data") { has("Temperature ") }
                    check(
                        texts().none {
                            it.contains("no DWD value") ||
                                it.contains("Relative humidity is not supplied")
                        }
                    ) {
                        "Today explanation remains"
                    }
                    click("Week")
                    waitFor("weekly rows") { has("HIGH") && has("LOW") }
                    check(
                        texts().none {
                            it.contains("WEEK AHEAD") ||
                                it.contains("High / low from available") ||
                                it.contains("Partial day")
                        }
                    ) {
                        "Removed Week copy remains"
                    }
                    click("Places")
                    var buttonsFirst = false
                    runOnMainSync {
                        val scroll =
                            views(activity.window.decorView)
                                .filterIsInstance<android.widget.ScrollView>()
                                .first { it.visibility == android.view.View.VISIBLE }
                        val body = scroll.getChildAt(0) as android.view.ViewGroup
                        val first = body.getChildAt(0)
                        buttonsFirst =
                            first is android.widget.Button && first.text.toString() == "SEARCH CITY"
                    }
                    check(buttonsFirst) { "Places must start directly with the buttons" }
                    check(texts().any { it == "FAVOURITES" })
                    click("Today")
                    result.putString(
                        "checks",
                        "Today explanatory note removed; Week heading/summary/partial labels removed while table remains; Places starts with Search city without duplicate location",
                    )
                }
                "recenter-probe" -> {
                    click("Radar")
                    val place = PlaceStore(targetContext).selected()
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
                "layout" -> {
                    click("Today")
                    waitFor("today forecast") { has("Temperature ") }
                    check(!has("7-DAY OUTLOOK"))
                    val before = Rect()
                    nodes()
                        .first { it.contentDescription?.toString() == "Today" }
                        .getBoundsInScreen(before)
                    var line: android.widget.ProgressBar? = null
                    runOnMainSync {
                        line = activity.window.decorView.findViewWithTag("download-progress")
                        check(line != null && line!!.height == targetContext.dp(2)) {
                            "Download line needs reserved height"
                        }
                        for (name in listOf("TODAY", "WEEK", "RADAR", "PLACES")) {
                            val tab =
                                activity.window.decorView.findViewWithTag<android.view.View>(
                                    "tab:$name"
                                )
                            val background =
                                tab.background as? android.graphics.drawable.GradientDrawable
                            check(background == null || background.cornerRadius == 0f) {
                                "Tab corners must be square"
                            }
                        }
                    }
                    val transfer = Http.downloads.begin()
                    try {
                        waitFor("global download line", 3000) {
                            var visible = false
                            runOnMainSync {
                                visible = line!!.visibility == android.view.View.VISIBLE
                            }
                            visible
                        }
                        val after = Rect()
                        nodes()
                            .first { it.contentDescription?.toString() == "Today" }
                            .getBoundsInScreen(after)
                        check(before == after) { "Download line shifted navigation" }
                    } finally {
                        transfer.cancel()
                    }
                    click("Week")
                    waitFor("week forecast") { has("HIGH") && has("LOW") }
                    check(!has("Temperature ")) { "Right-now card leaked into Week" }
                    check(has("HIGH") && has("LOW"))
                    click("Places")
                    check(
                        !has("SELECTED COORDINATES") &&
                            !has("ENTER COORDINATES") &&
                            !has("One position, resolved")
                    )
                    check(has("SEARCH CITY") && has("USE CURRENT LOCATION"))
                    click("Radar")
                    val mapBounds = Rect().apply { radarMapNode().getBoundsInScreen(this) }
                    var screenWidth = 0
                    runOnMainSync { screenWidth = activity.window.decorView.width }
                    check(mapBounds.left == 0 && mapBounds.width() == screenWidth) {
                        "Radar must be full width"
                    }
                    val play =
                        Rect().apply {
                            nodes()
                                .first {
                                    it.contentDescription?.toString() ==
                                        "Play or pause radar animation"
                                }
                                .getBoundsInScreen(this)
                        }
                    val scrub =
                        Rect().apply {
                            nodes()
                                .first { it.className == "android.widget.SeekBar" }
                                .getBoundsInScreen(this)
                        }
                    check(mapBounds.contains(play) && mapBounds.contains(scrub)) {
                        "Playback controls must overlay the map"
                    }
                    check(!has("Precipitation legend") && !has("mm/5min"))
                    runOnMainSync {
                        val controls =
                            activity.window.decorView.findViewWithTag<android.view.View>(
                                "radar-controls"
                            )
                        check(controls.background == null) {
                            "Radar controls need a transparent background"
                        }
                    }
                    click("Today")
                    result.putString(
                        "checks",
                        "square Today/Week/Radar/Places tabs; isolated forecast screens; removed manual coordinates/copy; edge-to-edge map with transparent controls; no legend; download line preserves layout",
                    )
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
                    click("Cancel")
                    check(tab("Places").isSelected && has("FAVOURITES"))
                    click("Week")
                    check(tab("Week").isSelected && has("HIGH") && has("LOW"))
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
                    result.putString(
                        "checks",
                        "Places selection and content survive Activity recreation; Today remains reachable",
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
                    val prefs = targetContext.getSharedPreferences("places", 0)
                    val camera = targetContext.getSharedPreferences("radar-viewport", 0)
                    val oldSelected = prefs.getString("selected", null)
                    val oldFavourites = prefs.getString("favourites", null)
                    val oldCamera = camera.getString("camera", null)
                    val coarse =
                        targetContext.checkSelfPermission(
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    val fine =
                        targetContext.checkSelfPermission(
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    var owner = activity
                    try {
                        click("Places")
                        click("SEARCH CITY")
                        enter("CITY NAME", "München")
                        click("Search")
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
                            PlaceStore(targetContext).selected().geocodeId ==
                                "open-meteo:${detail.groupValues[1]}"
                        }
                        val selected = PlaceStore(targetContext).selected()
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
                        val saved = PlaceStore(targetContext).selected()
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
                        check(PlaceStore(targetContext).selected() == saved)
                        check(PlaceStore(targetContext).favourites().any { it == saved })
                        // Opted-in fixture selections replace the removed manual-coordinate UI.
                        fun fixture(place: Place) {
                            runOnMainSync { owner.finish() }
                            waitForIdleSync()
                            PlaceStore(targetContext).select(place)
                            owner =
                                startActivitySync(
                                    Intent(targetContext, MainActivity::class.java)
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
                        click("Cancel")
                        fixture(Place("Coverage test", 0.0, 0.0, "UTC"))
                        waitFor("coverage explanation") {
                            has("No DWD forecast point within 50 km")
                        }
                        check(!has("Temperature ")) {
                            "Previous place weather leaked outside coverage"
                        }
                        click("Radar")
                        check(has("Precipitation map"))
                        check(
                            targetContext.checkSelfPermission(
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == coarse
                        )
                        check(
                            targetContext.checkSelfPermission(
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
                            Intent(targetContext, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                "station-benchmark" -> {
                    check(
                        targetContext.applicationInfo.flags and
                            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0
                    ) {
                        "Benchmark requires a release build"
                    }
                    val start = SystemClock.elapsedRealtimeNanos()
                    val bytes = targetContext.assets.open("stations.tsv").use { it.readBytes() }
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
                    val original = PlaceStore(targetContext).selected()
                    val favourites = PlaceStore(targetContext).favourites()
                    val coarse =
                        targetContext.checkSelfPermission(
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    val fine =
                        targetContext.checkSelfPermission(
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    val platform = PlatformLocationSource(targetContext)
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
                    check(has("Open-Meteo") && has("query")) {
                        "Search disclosure/attribution missing"
                    }
                    enter("CITY NAME", "M")
                    click("Search")
                    waitFor("query validation", 3000) { has("2–120") }
                    enter("CITY NAME", "München")
                    click("Search")
                    waitFor("city candidates", 45000) {
                        nodes().any {
                            it.contentDescription?.toString()?.startsWith("Select city result ") ==
                                true
                        }
                    }
                    check(PlaceStore(targetContext).selected() == original) {
                        "Search selected a candidate without a tap"
                    }
                    enter("CITY NAME", "Berlin")
                    waitFor("editing invalidates results", 3000) {
                        nodes().none {
                            it.contentDescription?.toString()?.startsWith("Select city result ") ==
                                true
                        }
                    }
                    click("Cancel")
                    click("CLEAR SEARCH CACHE")
                    check(
                        PlaceStore(targetContext).favourites() == favourites &&
                            PlaceStore(targetContext).selected() == original
                    )
                    if (
                        coarse != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                            fine != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        click("USE CURRENT LOCATION")
                        if (has("Current location")) {
                            check(has("on this device")) { "Location rationale missing" }
                            click("Cancel")
                        } else {
                            check(
                                has("Location access") && (has("City search") || has("city search"))
                            )
                            click("Close")
                        }
                    }
                    check(
                        targetContext.checkSelfPermission(
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == coarse
                    )
                    check(
                        targetContext.checkSelfPermission(
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        ) == fine
                    )
                    check(PlaceStore(targetContext).selected() == original)
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
                        targetContext
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
                        targetContext
                            .getSharedPreferences("radar-test", 0)
                            .getString("expected", null)
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
                    val mapBounds = Rect().apply { radarMapNode().getBoundsInScreen(this) }
                    val tabs =
                        nodes().first {
                            it.contentDescription?.toString() == "Radar" && it.isClickable
                        }
                    val tabBounds = Rect().apply { tabs.getBoundsInScreen(this) }
                    val coordinates =
                        nodes().first {
                            it.text?.toString()?.let { text ->
                                text.contains("°N") || text.contains("°S")
                            } == true
                        }
                    val coordinateBounds = Rect().apply { coordinates.getBoundsInScreen(this) }
                    check(mapBounds.height() >= (tabBounds.top - coordinateBounds.bottom) * .75) {
                        "Radar map must occupy at least 75% of available content height"
                    }
                    val text = nodes().mapNotNull { it.text?.toString() }.joinToString(" | ")
                    for (removed in
                        listOf(
                            "OBSERVED",
                            "DWD RV",
                            "Issue ",
                            "Received ",
                            "tap to refresh",
                            "hatched:",
                            "mm / 5 min",
                        )) {
                        check(!text.contains(removed)) { "Removed radar text remains: $removed" }
                    }
                    check(!Regex("\\d{2} \\w{3} · \\d{2}:\\d{2}").containsMatchIn(text)) {
                        "Radar date/time remains visible"
                    }
                    check(
                        nodes().none {
                            it.isClickable &&
                                it.contentDescription?.toString() in
                                    listOf("Zoom in", "Zoom out", "Recenter on selected location")
                        }
                    ) {
                        "Map control buttons remain"
                    }
                    val play =
                        nodes().first {
                            it.contentDescription?.toString() == "Play or pause radar animation"
                        }
                    val playBounds = Rect().apply { play.getBoundsInScreen(this) }
                    check(play.text.isNullOrBlank() && playBounds.width() <= targetContext.dp(48)) {
                        "Playback must use a compact icon button"
                    }
                    val barBounds =
                        Rect().apply {
                            nodes()
                                .first { it.className == "android.widget.SeekBar" }
                                .getBoundsInScreen(this)
                        }
                    check(barBounds.height() <= targetContext.dp(40)) {
                        "Timeline must give the removed NOW label space back to the map"
                    }
                    val selectedPlace = PlaceStore(targetContext).selected()
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
                    waitFor("playback loops", 10000) {
                        bar().rangeInfo.current < bar().rangeInfo.max
                    }
                    click("Play or pause radar animation")
                    val paused = bar().rangeInfo.current
                    SystemClock.sleep(1000)
                    check(bar().rangeInfo.current == paused) { "Pause did not stop playback" }
                    result.putString(
                        "checks",
                        "map fills radar view; compact icon controls; removed text; pan; pinch in/out; double tap; scrub to last frame; playback loops and pauses",
                    )
                }
                "forecast" -> {
                    click("Today")
                    waitFor("today forecast") { has("Temperature ") }
                    check(!has("HIGH") && !has("LOW"))
                    fun scroll(): AccessibilityNodeInfo =
                        nodes().first { it.className == "android.widget.ScrollView" }
                    repeat(4) {
                        scroll().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(150)
                    }
                    check(has("HOURLY FORECAST") && has("NEXT 24 HOURS"))
                    val hourly =
                        nodes().firstOrNull {
                            it.className == "android.widget.HorizontalScrollView"
                        }
                    check(hourly != null) { "Hourly timeline is not horizontally scrollable" }
                    repeat(6) {
                        hourly.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(120)
                    }
                    waitFor("hourly provenance footer") {
                        has("DATA TRANSMISSION") && has("Issue") && has("Received")
                    }
                    check(!has("no DWD value") && !has("Relative humidity is not supplied")) {
                        "Removed hourly explanation remains"
                    }
                    click("Week")
                    waitFor("week ahead") { has("HIGH") && has("LOW") }
                    check(!has("Temperature ") && !has("HOURLY FORECAST"))
                    repeat(8) {
                        scroll().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(120)
                    }
                    val zone = PlaceStore(targetContext).selected().zone
                    val last =
                        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(zone)).apply {
                            add(java.util.Calendar.DAY_OF_MONTH, 6)
                        }
                    check(has(Times.format(last.timeInMillis, "dd MMM", zone))) {
                        "Seventh forecast day is not reachable"
                    }
                    check(has("Issue") && has("Received"))
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
                    click("Search")
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
                    check(viewport() != centered)
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
                        has("OFFLINE") && has("STALE") && has("Temperature ")
                    }
                    click("Radar")
                    waitFor("offline cached radar") {
                        has("STALE") && has("frames") && has("OBSERVED")
                    }
                    click("Play or pause radar animation")
                    SystemClock.sleep(1800)
                    click("Play or pause radar animation")
                    result.putString(
                        "checks",
                        "airplane mode retains forecast and radar; stale/offline labels; cached playback",
                    )
                }
                "restore" -> {
                    waitFor("live forecast recovered") { has("LIVE") && has("Temperature ") }
                    result.putString("checks", "connectivity recovery returns forecast to live")
                }
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
