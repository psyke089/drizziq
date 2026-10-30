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
        error(
            "Timed out: $description. Screen: ${nodes().mapNotNull {it.text?.toString()}.joinToString(" | ")}"
        )
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
        check(radarMapNode().contentDescription.toString() != original) {
            "One-finger pan did not move the map"
        }
        val beforeZoom = zoom()
        pinch(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        check(zoom() > beforeZoom) { "Spreading fingers did not zoom in: $beforeZoom to ${zoom()}" }
        val zoomed = zoom()
        pinch(bounds.centerX().toFloat(), bounds.centerY().toFloat(), reverse = true)
        check(zoom() < zoomed) { "Pinching fingers did not zoom out" }
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
                    check(tab("Forecast").isSelected)
                    val before = Rect()
                    tab("Forecast").getBoundsInScreen(before)
                    nodes()
                        .first { it.className == "android.widget.ScrollView" }
                        .performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(300)
                    val after = Rect()
                    tab("Forecast").getBoundsInScreen(after)
                    check(before == after) { "Tab bar moved with forecast content" }
                    click("Places")
                    check(tab("Places").isSelected && has("FAVOURITES") && has("ENTER COORDINATES"))
                    click("ENTER COORDINATES")
                    check(has("Enter location"))
                    click("Cancel")
                    check(tab("Places").isSelected && has("FAVOURITES"))
                    click("Radar")
                    check(tab("Radar").isSelected && has("Play or pause radar animation"))
                    click("Forecast")
                    check(tab("Forecast").isSelected)
                    repeat(20) {
                        nodes()
                            .first { it.className == "android.widget.ScrollView" }
                            .performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    }
                    waitFor("forecast restored") { has("Temperature ") }
                    check(has("arrow shows wind flow")) { "Wind direction symbol is missing" }
                    result.putString(
                        "checks",
                        "removed forecast labels; wind compass accessible; all three tabs select their screen; bottom tab bar fixed during scroll; Places coordinate dialog cancels back to Places; forecast restored",
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
                    click("Forecast")
                    check(selected("Forecast"))
                    result.putString(
                        "checks",
                        "Places selection and content survive Activity recreation; Forecast remains reachable",
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
                    val initialCenter =
                        "Precipitation map. Center " +
                            String.format(
                                java.util.Locale.US,
                                "%.6f, %.6f",
                                selectedPlace.lat.coerceIn(-85.05112878, 85.05112878),
                                if (selectedPlace.lon == 180.0) -180.0 else selectedPlace.lon,
                            )
                    radarGestures()
                    val recenter =
                        radarMapNode().actionList.first {
                            it.label?.toString() == "Recenter on selected location"
                        }
                    check(radarMapNode().performAction(recenter.id))
                    waitFor("accessible recenter restores selected-place center", 3000) {
                        radarMapNode().contentDescription.toString().substringBefore("; zoom") ==
                            initialCenter
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
                    click("Forecast")
                    waitFor("forecast") { has("Temperature ") }
                    fun scroll(): AccessibilityNodeInfo =
                        nodes().first { it.className == "android.widget.ScrollView" }
                    scroll().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(400)
                    val outlook =
                        nodes().firstOrNull {
                            it.className == "android.widget.HorizontalScrollView"
                        }
                    check(outlook != null) { "Outlook is not scrollable" }
                    repeat(6) {
                        outlook.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(120)
                    }
                    val last =
                        java.util.Calendar.getInstance(
                                java.util.TimeZone.getTimeZone("Europe/Berlin")
                            )
                            .apply { add(java.util.Calendar.DAY_OF_MONTH, 6) }
                    check(has(Times.format(last.timeInMillis, "dd MMM", "Europe/Berlin"))) {
                        "Seventh forecast day is not reachable"
                    }
                    var reached = false
                    for (i in 0..16) {
                        if (has("DATA TRANSMISSION")) {
                            reached = true
                            break
                        }
                        scroll().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(150)
                    }
                    check(reached && has("Issue") && has("Received")) {
                        "Hourly detail or provenance footer is not reachable"
                    }
                    check(has("Relative humidity is not supplied")) {
                        "Missing humidity is not explained"
                    }
                    repeat(20) {
                        scroll().performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    }
                    result.putString(
                        "checks",
                        "forecast scroll; all seven days reachable; hourly detail traversed; missing humidity omitted; issue, validity and received footer visible",
                    )
                }
                "main" -> {
                    waitFor("live Munich forecast") { has("Temperature ") && has("LIVE") }
                    click("PLACES")
                    click("ENTER COORDINATES")
                    enter("LATITUDE · −90 TO 90", "48.138108")
                    enter("LONGITUDE · −180 TO 180", "11.574726")
                    enter("LABEL (OPTIONAL)", "Munich")
                    click("Use location")
                    waitFor("Munich forecast") { has("MUENCHEN STADT") && has("LIVE") }
                    click("PLACES")
                    click("SAVE CURRENT AS FAVOURITE")
                    enter("FAVOURITE LABEL", "Munich home")
                    click("Save")
                    click("PLACES")
                    click("ENTER COORDINATES")
                    enter("LATITUDE · −90 TO 90", "91")
                    click("Use location")
                    check(has("Enter location")) { "Invalid latitude accepted" }
                    enter("LATITUDE · −90 TO 90", "52.5200")
                    enter("LONGITUDE · −180 TO 180", "181")
                    click("Use location")
                    check(has("Enter location")) { "Invalid longitude accepted" }
                    enter("LONGITUDE · −180 TO 180", "13.4050")
                    enter("LABEL (OPTIONAL)", "Berlin test")
                    click("Use location")
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
                        "live Munich; coordinate validation; live Berlin; save two favourites; radar pan; two-finger pinch; accessible zoom and recenter; scrub to +120; playback loop; select favourite",
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
