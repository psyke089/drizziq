package app.drizziq

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.*
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var repo: Repository
    private lateinit var store: PlaceStore
    private lateinit var zones: ZoneLookup
    private lateinit var placeChoices: PlaceChoices
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var placeTitle: TextView
    private lateinit var currentWeather: CurrentWeatherHeader
    private lateinit var coordinates: TextView
    private lateinit var forecastContainer: LinearLayout
    private lateinit var weekHeader: LinearLayout
    private lateinit var progressLine: ProgressBar
    private var progressSubscription: Cancel? = null
    private val forecastOffsets = mutableMapOf<Page, Int>()
    private var hourlyView: HourlyForecast? = null
    private var hourlyKey: Pair<Place, String>? = null

    private enum class Page {
        TODAY,
        WEEK,
        RADAR,
        PLACES,
    }

    private var page = Page.TODAY
    private val tabs = mutableMapOf<Page, LinearLayout>()
    private lateinit var placesScroll: ScrollView
    private lateinit var forecastScroll: ScrollView
    private lateinit var radarPanel: FrameLayout
    private lateinit var map: RadarMap
    private lateinit var radarNotice: TextView
    private var frameError = false
    private lateinit var timeline: RadarTimeline
    private lateinit var playButton: ImageButton
    private val handler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private val frames = object : android.util.LruCache<String, Bitmap>(3) {}
    private var sequence: RadarSequence? = null
    private var frameIndex = 0
    private var decodeToken = 0
    private var playing = false
    private var active = false
    private var started = false
    private var stopped = false
    private var radarVisible = false
    private var ready = false
    private var dead = false
    private var networkChangedWhileStopped = false
    private var noticesDialog: AlertDialog? = null
    private var forecastVersion: Reading<Forecast>? = null
    private var lastForecastPlace: Place? = null
    private var lastForecastDay: String? = null

    private data class SolarKey(
        val lat: Double,
        val lon: Double,
        val zone: String,
        val date: LocalDate,
    )

    private val solarWorker =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    private val solarGeneration = RequestGeneration()
    private var solarFuture: Future<*>? = null
    private var solarKey: SolarKey? = null
    private var solarDay: SolarDay? = null
    private var solarFailed = false
    private var sunriseCard: SunriseCard? = null
    private var lastRefresh = 0L
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changedNetwork()

            override fun onLost(network: Network) = changedNetwork()

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = changedNetwork()
        }

    private fun changedNetwork() {
        handler.post {
            if (dead) return@post
            if (!started) {
                // A stopped window must not start downloads; onStart reconciles the change.
                if (stopped) networkChangedWhileStopped = true
                return@post
            }
            if (ready) {
                val was = repo.online
                val now = repo.connection()
                if (!was && now) refresh()
            }
        }
    }

    private val tick =
        object : Runnable {
            override fun run() {
                if (!active || dead) return
                repo.age()
                render()
                if (repo.online && AppPlatform.now() - lastRefresh > 300000) refresh()
                handler.postDelayed(this, 30000)
            }
        }
    private val advance = Runnable {
        if (playing && active && radarVisible)
            sequence?.let { requestFrame(nextRadarFrame(frameIndex, it.frames.size)) }
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppPlatform.context(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PlaceStore(this)
        zones = ZoneLookup(this)
        repo = Repository(this) { if (ready) render() }
        placeChoices =
            PlaceChoices(
                this,
                zones,
                { repo.online },
                { place ->
                    if (choose(place)) {
                        showPage(Page.TODAY)
                        true
                    } else false
                },
                { place -> zoneEntry(place) },
            )
        createLayout()
        ready = true
        progressSubscription =
            Http.downloads.observe { busy ->
                handler.post {
                    if (!dead) progressLine.visibility = if (busy) View.VISIBLE else View.INVISIBLE
                }
            }
        repo.connection()
        choose(store.selected(), false)
        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
            .registerDefaultNetworkCallback(networkCallback)
        showPage(
            savedInstanceState?.getString("page")?.let { name ->
                Page.entries.firstOrNull { it.name == name }
                    ?: when (name) {
                        "FORECAST" -> Page.TODAY
                        "MAP" -> Page.RADAR
                        else -> null
                    }
            } ?: Page.TODAY
        )
    }

    private fun createLayout() {
        root =
            column().apply {
                setBackgroundColor(Hud.bg)
                setPadding(0, dp(6), 0, dp(6))
            }
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop + dp(6),
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom + dp(6),
            )
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        val header = row().apply { setPadding(dp(20), 0, dp(20), 0) }
        header.addView(WeatherGlyph(this, logo = true), LinearLayout.LayoutParams(dp(36), dp(36)))
        val wordmark =
            label("DRIZZIQ", 19f).apply {
                letterSpacing = .22f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL
            }
        header.addView(
            wordmark,
            LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(10) },
        )
        header.addView(
            label("Quelle:\nDeutscher\nWetterdienst", 8f, Hud.muted, true).apply {
                tag = "dwd-source"
                gravity = Gravity.END
                contentDescription = "Quelle: Deutscher Wetterdienst"
                letterSpacing = .04f
            },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP },
        )
        root.addView(header)
        progressLine =
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = "download-progress"
                contentDescription = "Downloads in progress"
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(Hud.cyan)
                visibility = View.INVISIBLE
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
            }
        root.addView(progressLine, LinearLayout.LayoutParams(-1, dp(2)))
        root.space(16)
        val location =
            row().apply {
                setPadding(dp(20), 0, dp(20), 0)
                minimumHeight = dp(112)
            }
        val placeColumn = column()
        placeTitle =
            label("Munich", 20f).apply {
                tag = "place-name"
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                minLines = 2
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        coordinates = label("", 9f, Hud.muted, true)
        placeColumn.addView(placeTitle)
        placeColumn.space(6)
        placeColumn.addView(coordinates)
        location.addView(placeColumn, LinearLayout.LayoutParams(0, -2, 1f))
        currentWeather = CurrentWeatherHeader(this)
        location.addView(
            currentWeather,
            LinearLayout.LayoutParams(dp(180), -2).apply { leftMargin = dp(12) },
        )
        root.addView(location)
        root.space(16)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        forecastScroll =
            ScrollView(this).apply {
                tag = "forecast-scroll"
                isFillViewport = true
                clipToPadding = false
                setPadding(0, 0, 0, 0)
            }
        forecastContainer = column()
        weekHeader = column()
        forecastContainer.addView(weekHeader)
        forecastContainer.addView(forecastScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(forecastContainer, FrameLayout.LayoutParams(-1, -1))
        createRadar()
        placesScroll =
            ScrollView(this).apply {
                tag = "places-scroll"
                isFillViewport = true
            }
        content.addView(placesScroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            View(this).apply { setBackgroundColor(Hud.line) },
            LinearLayout.LayoutParams(-1, dp(1)),
        )
        val nav = row()
        Page.entries.forEach { destination ->
            val tab =
                column().apply {
                    gravity = Gravity.CENTER
                    tag = "tab:${destination.name}"
                    isClickable = true
                    isFocusable = true
                    contentDescription =
                        destination.name.lowercase().replaceFirstChar { it.uppercase() }
                    setOnClickListener { showPage(destination) }
                    addView(
                        NavigationGlyph(
                            this@MainActivity,
                            destination.name,
                        ),
                        LinearLayout.LayoutParams(dp(24), dp(24)),
                    )
                    space(5)
                    addView(
                        label(destination.name, 10f, Hud.muted, true).apply {
                            gravity = Gravity.CENTER
                        }
                    )
                }
            tabs[destination] = tab
            nav.addView(tab, LinearLayout.LayoutParams(0, dp(64), 1f))
        }
        root.addView(nav)
    }

    private fun buildForecast(scroll: Int = forecastScroll.scrollY) {
        val data = repo.forecast
        val forecast = data.data
        val place = repo.place
        val state = data.state
        val now = AppPlatform.now()
        val key = place to Times.day(now, place.zone)
        val hourlyOffset = hourlyView?.takeIf { hourlyKey == key }?.timelineOffset
        hourlyView = null
        hourlyKey = key
        val body =
            column().apply {
                tag = "forecast-body"
                setPadding(0, 0, 0, 0)
                clipToPadding = false
                clipChildren = false
            }
        forecastScroll.removeAllViews()
        forecastScroll.addView(body)
        weekHeader.removeAllViews()
        weekHeader.visibility =
            if (page == Page.WEEK && forecast != null) View.VISIBLE else View.GONE
        lastForecastDay = Times.day(now, place.zone)
        sunriseCard = null
        ensureSolar(place, now)
        if (forecast == null) {
            val empty =
                column().apply {
                    pad(24)
                    background = panel()
                }
            empty.addView(
                WeatherGlyph(this, logo = true),
                LinearLayout.LayoutParams(dp(100), dp(100)),
            )
            empty.space(20)
            empty.addView(
                label(
                    if (state == Freshness.ERROR) "No forecast received"
                    else "Receiving the atmosphere",
                    24f,
                )
            )
            empty.space(12)
            empty.addView(
                label(
                    if (state == Freshness.ERROR && data.message != null) data.message
                    else
                        "Direct from Deutscher Wetterdienst.\nNearest forecast point resolved on this device.",
                    14f,
                    Hud.muted,
                )
            )
            empty.space(24)
            empty.addView(action("RETRY") { refresh() })
            body.addView(
                empty,
                LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = dp(20)
                    rightMargin = dp(20)
                },
            )
            if (page != Page.WEEK) {
                body.space(16)
                addSunriseCard(body, place)
            }
            return
        }
        if (page != Page.WEEK) buildHourly(body, forecast, place, now, hourlyOffset)
        else buildWeek(body, forecast, place, now)
        if (page != Page.WEEK) {
            body.space(16)
            addSunriseCard(body, place)
        }
        body.space(16)
        body.addView(
            ForecastDetailsCard(this, data, place) { refresh() },
            LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = dp(20)
                rightMargin = dp(20)
            },
        )
        body.space(12)
        forecastScroll.post { forecastScroll.scrollTo(0, scroll) }
    }

    private fun ensureSolar(place: Place, now: Long) {
        val key =
            SolarKey(place.lat, place.lon, place.zone, LocalDate.parse(Times.day(now, place.zone)))
        if (key == solarKey) return
        solarFuture?.cancel(true)
        solarWorker.queue.clear()
        val token = solarGeneration.next()
        solarKey = key
        solarDay = null
        solarFailed = false
        sunriseCard?.bind(null, place.zone)
        solarFuture = solarWorker.submit {
            val result = runCatching { SolarCalculator.calculate(place, key.date) }
            handler.post {
                if (!dead && solarGeneration.isCurrent(token) && solarKey == key) {
                    solarDay = result.getOrNull()
                    solarFailed = result.isFailure
                    sunriseCard?.bind(solarDay, key.zone, solarFailed)
                }
            }
        }
    }

    private fun addSunriseCard(body: LinearLayout, place: Place) {
        sunriseCard = SunriseCard(this).apply { bind(solarDay, place.zone, solarFailed) }
        body.addView(
            sunriseCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = dp(20)
                rightMargin = dp(20)
            },
        )
    }

    private fun buildHourly(
        body: LinearLayout,
        forecast: Forecast,
        place: Place,
        now: Long,
        offset: Int?,
    ) {
        val columns = hourlyColumns(forecast, now, place.zone)
        body.addView(
            label(Times.format(now, "EEEE, dd MMM yyyy", place.zone), 12f, Hud.cyan, true).apply {
                tag = "today-date"
                setPadding(dp(20), 0, dp(20), 0)
            }
        )
        body.space(12)
        if (columns.isEmpty()) body.addView(label("No hourly forecast for today", 13f, Hud.muted))
        else {
            hourlyView =
                HourlyForecast(
                    this,
                    columns,
                    place.zone,
                    offset ?: dp(56) * currentHourColumn(columns, now),
                )
            body.addView(hourlyView, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun buildWeek(body: LinearLayout, forecast: Forecast, place: Place, now: Long) {
        val days = forecast.days(now, place.zone)
        val labels = days.map {
            Times.format(it.time, "EEE", place.zone) to Times.format(it.time, "dd MMM", place.zone)
        }
        val datePaint = android.graphics.Paint()
        val scale = resources.displayMetrics.scaledDensity
        datePaint.typeface = Typeface.create("monospace", Typeface.NORMAL)
        var dateWidth = dp(64)
        for ((weekday, date) in labels) {
            datePaint.textSize = 15 * scale
            val weekdayWidth = datePaint.measureText(weekday)
            datePaint.textSize = 10 * scale
            dateWidth =
                maxOf(
                    dateWidth,
                    kotlin.math
                        .ceil(
                            maxOf(
                                weekdayWidth,
                                datePaint.measureText(date),
                            ) + dp(8)
                        )
                        .toInt(),
                )
        }
        fun cell(
            row: LinearLayout,
            text: String,
            weight: Float,
            color: Int = Hud.ink,
            size: Float = 12f,
        ) {
            row.addView(
                label(text, size, color, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, -2, weight),
            )
        }
        val head =
            row().apply {
                setBackgroundColor(Hud.tableHeader)
            }
        head.addView(
            ForecastMetricLabel(this, ForecastMetric.TIME).apply {
                setBackgroundColor(Hud.tableHeader)
            },
            LinearLayout.LayoutParams(dateWidth, dp(62)),
        )
        head.addView(
            ForecastMetricLabel(this, ForecastMetric.SKY),
            LinearLayout.LayoutParams(0, dp(62), .7f),
        )
        for ((metric, unit, weight) in
            listOf(
                Triple(ForecastMetric.HIGH, "°C", .8f),
                Triple(ForecastMetric.LOW, "°C", .8f),
                Triple(ForecastMetric.RAIN, "mm", 1f),
                Triple(ForecastMetric.WIND, "km/h", 1f),
                Triple(ForecastMetric.SUN, "h", .8f),
            )) head.addView(
            ForecastMetricLabel(this, metric, unit),
            LinearLayout.LayoutParams(0, dp(62), weight),
        )
        weekHeader.addView(head)
        for ((index, day) in days.withIndex()) {
            val row =
                row().apply {
                    tag = "week-row:$index"
                    setBackgroundColor(if (index % 2 == 0) Hud.panel else Hud.bg)
                }
            row.addView(
                WeekDayLabel(
                        this,
                        labels[index].first,
                        labels[index].second,
                    )
                    .apply {
                        minimumHeight = dp(64)
                        setBackgroundColor(Hud.legend)
                    },
                LinearLayout.LayoutParams(dateWidth, -2),
            )
            row.addView(
                WeatherGlyph(
                        this,
                        day.condition,
                        precipitation =
                            day.hours
                                .filter { it.condition == day.condition }
                                .mapNotNull { it.get("RR1c") }
                                .maxOrNull(),
                    )
                    .apply {
                        contentDescription =
                            weatherLabel(day.condition) ?: "No weather condition supplied"
                    },
                LinearLayout.LayoutParams(0, dp(36), .7f),
            )
            cell(row, day.high?.let { "${number(it,0)}°" } ?: "—", .8f)
            cell(row, day.low?.let { "${number(it,0)}°" } ?: "—", .8f, Hud.muted)
            cell(row, day.rain?.let { number(it, 1) } ?: "—", 1f, Hud.cyan)
            cell(row, day.wind?.let { number(it, 0) } ?: "—", 1f)
            cell(row, day.sun?.let { number(it, 1) } ?: "—", .8f, Hud.amber)
            body.addView(row)
            body.addView(
                View(this).apply { setBackgroundColor(Hud.line) },
                LinearLayout.LayoutParams(-1, 1),
            )
        }
    }

    private fun createRadar() {
        radarPanel = FrameLayout(this)
        map = RadarMap(this)
        radarPanel.addView(map, FrameLayout.LayoutParams(-1, -1))
        radarNotice =
            label("", 11f, Hud.amber).apply {
                pad(8)
                background = panel()
                gravity = Gravity.CENTER
                visibility = View.GONE
                setOnClickListener {
                    repo.refreshRadar()
                    if (sequence != null) requestFrame(frameIndex)
                }
            }
        radarPanel.addView(
            radarNotice,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(8)
            },
        )
        val attribution =
            label("© OpenStreetMap contributors", 9f, Hud.muted).apply {
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setBackgroundColor(0xCF080F19.toInt())
                contentDescription = "OpenStreetMap attribution and licence"
                setOnClickListener {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.openstreetmap.org/copyright"),
                        )
                    )
                }
            }
        radarPanel.addView(
            attribution,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START),
        )
        val controls =
            row().apply {
                tag = "radar-controls"
                setPadding(dp(12), 0, dp(12), 0)
            }
        playButton =
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_play)
                contentDescription = "Play or pause radar animation"
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = null
                setOnClickListener { setPlaying(!playing) }
            }
        controls.addView(playButton, LinearLayout.LayoutParams(dp(48), dp(40)))
        timeline =
            RadarTimeline(this).apply {
                onSelect = {
                    setPlaying(false)
                    requestFrame(it)
                }
            }
        controls.addView(
            timeline,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(8) },
        )
        radarPanel.addView(
            controls,
            FrameLayout.LayoutParams(-1, dp(40), Gravity.BOTTOM),
        )
        content.addView(radarPanel, FrameLayout.LayoutParams(-1, -1))
    }

    private fun updateRadarNotice() {
        val reading = repo.radar
        val message =
            when {
                frameError -> "Radar frame unavailable"
                reading.data == null ->
                    if (reading.state == Freshness.ERROR) "Radar unavailable" else "Loading radar…"
                !repo.online -> "Offline radar"
                reading.state == Freshness.STALE -> "Radar data is stale or incomplete"
                reading.state == Freshness.CACHED -> "Cached radar"
                else -> null
            }
        radarNotice.text = message
        radarNotice.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun updateRadarAccessibility() {
        val seq = sequence
        val frame = seq?.frames?.getOrNull(frameIndex)
        timeline.contentDescription =
            if (frame == null) "Radar timeline, waiting for frames"
            else {
                val type = if (frame.lead > 0) "FORECAST +${frame.lead}m" else "OBSERVED"
                val previous = seq.frames.getOrNull(frameIndex - 1)
                val gap = previous != null && frame.time - previous.time > 300000
                val state =
                    if (frameError) "unavailable; showing retained frame" else repo.radar.state.name
                "Radar frame ${Times.format(frame.time, "dd MMM HH:mm z", repo.place.zone)}, $type, $state, ${seq.frames.size} frames${if (gap) ", gap before frame" else ""}"
            }
    }

    private fun renderRadar() {
        val reading = repo.radar
        map.online = repo.online
        map.invalidate()
        val data = reading.data
        if (data != sequence && data != null) {
            val oldTime = sequence?.frames?.getOrNull(frameIndex)?.time
            sequence = data
            timeline.frames = data.frames
            frameIndex =
                if (oldTime == null) data.frames.indexOfLast { it.lead == 0 }.coerceAtLeast(0)
                else data.frames.indices.minBy { abs(data.frames[it].time - oldTime) }
            requestFrame(frameIndex)
        }
        playButton.isEnabled = data != null
        updateRadarNotice()
        updateRadarAccessibility()
    }

    private fun requestFrame(index: Int) {
        val seq = sequence ?: return
        val frame = seq.frames.getOrNull(index) ?: return
        val token = ++decodeToken
        handler.removeCallbacks(advance)
        val display: (Bitmap) -> Unit = { image ->
            if (!dead && token == decodeToken) {
                frameError = false
                map.show(frame, image)
                frameIndex = index
                timeline.selected = index
                updateRadarAccessibility()
                updateRadarNotice()
                if (playing && active && radarVisible)
                    handler.postDelayed(advance, if (index == seq.frames.lastIndex) 1400 else 700)
            }
        }
        frames.get(frame.file)?.let {
            display(it)
            return
        }
        frameError = false
        updateRadarNotice()
        decoder.execute {
            val image = BitmapFactory.decodeFile(frame.file)
            handler.post {
                if (!dead) {
                    if (image != null) {
                        frames.put(frame.file, image)
                        display(image)
                    } else if (token == decodeToken) {
                        setPlaying(false)
                        frameError = true
                        updateRadarNotice()
                    }
                }
            }
        }
    }

    private fun setPlaying(value: Boolean) {
        playing = value
        playButton.isSelected = value
        playButton.setImageResource(if (value) R.drawable.ic_pause else R.drawable.ic_play)
        handler.removeCallbacks(advance)
        if (value) handler.postDelayed(advance, 700)
    }

    private fun showPage(value: Page) {
        if (value == Page.TODAY && page != Page.TODAY) hourlyView = null
        if (page == Page.TODAY || page == Page.WEEK) forecastOffsets[page] = forecastScroll.scrollY
        page = value
        radarVisible = value == Page.RADAR
        radarPanel.visibility = if (radarVisible) View.VISIBLE else View.GONE
        forecastContainer.visibility =
            if (value == Page.TODAY || value == Page.WEEK) View.VISIBLE else View.GONE
        placesScroll.visibility = if (value == Page.PLACES) View.VISIBLE else View.GONE
        tabs.forEach { (destination, tab) ->
            val selected = destination == value
            tab.isSelected = selected
            tab.background = if (selected) panel(Hud.panel, Hud.panel, 0f) else null
            (tab.getChildAt(0) as NavigationGlyph).apply {
                highlighted = selected
                invalidate()
            }
            (tab.getChildAt(2) as TextView).setTextColor(if (selected) Hud.cyan else Hud.muted)
        }
        if (!radarVisible) setPlaying(false)
        if (value == Page.PLACES) locations()
        if (value == Page.TODAY || value == Page.WEEK) buildForecast(forecastOffsets[value] ?: 0)
    }

    private fun render() {
        repo.age()
        ensureSolar(repo.place, AppPlatform.now())
        placeTitle.text = placeDisplayName(repo.place, repo.forecastPoint?.station)
        coordinates.text = coords(repo.place.lat, repo.place.lon)
        currentWeather.bind(
            repo.forecast.data?.current(AppPlatform.now()),
            repo.forecast.state,
            repo.place.zone,
        )
        if (
            forecastVersion != repo.forecast ||
                lastForecastPlace != repo.place ||
                lastForecastDay != Times.day(AppPlatform.now(), repo.place.zone)
        ) {
            forecastVersion = repo.forecast
            lastForecastPlace = repo.place
            buildForecast()
        }
        renderRadar()
    }

    private fun refresh() {
        lastRefresh = AppPlatform.now()
        repo.connection()
        repo.refreshForecast()
        repo.refreshRadar()
    }

    private fun choose(place: Place, persist: Boolean = true): Boolean {
        if (persist)
            try {
                store.select(place)
            } catch (error: Exception) {
                Toast.makeText(
                        this,
                        "Location could not be saved. Please retry.",
                        Toast.LENGTH_LONG,
                    )
                    .show()
                return false
            }
        placeChoices.invalidate()
        map.place = place
        setPlaying(false)
        lastRefresh = AppPlatform.now()
        repo.select(place)
        return true
    }

    private fun locations() {
        val body = column().apply { setPadding(dp(20), dp(20), dp(20), 0) }
        placesScroll.removeAllViews()
        placesScroll.addView(body)
        body.addView(action("SEARCH CITY") { placeChoices.searchCity() })
        body.space(8)
        body.addView(action("USE CURRENT LOCATION") { placeChoices.currentLocation() })
        body.space(8)
        body.addView(action("SAVE CURRENT AS FAVOURITE") { saveFavourite() })
        body.space(22)
        body.addView(label("FAVOURITES", 11f, Hud.cyan, true))
        body.space(10)
        val favourites = store.favourites()
        if (favourites.isEmpty())
            body.addView(label("Saved places will appear here.", 13f, Hud.muted))
        favourites.forEach { place ->
            body.addView(
                FavouriteCard(
                    this,
                    place,
                    selected = repo.place.lat == place.lat && repo.place.lon == place.lon,
                    select = {
                        if (choose(place)) showPage(Page.TODAY)
                    },
                    delete = {
                        if (store.delete(place)) locations()
                        else
                            Toast.makeText(
                                    this,
                                    "Favourite could not be deleted. Please retry.",
                                    Toast.LENGTH_LONG,
                                )
                                .show()
                    },
                )
            )
            body.space(10)
        }
        body.space(12)
        body.addView(action("CLEAR SEARCH CACHE") { placeChoices.clearCache() })
        body.space(8)
        body.addView(
            action("DATA & LICENCES") {
                placeChoices.invalidate()
                notices()
            }
        )
        body.space(16)
        body.addView(
            label("Drizziq ${BuildConfig.VERSION_NAME}", 11f, Hud.muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(12))
            }
        )
    }

    private fun notices() {
        val text =
            label(assets.open("notices.txt").bufferedReader().use { it.readText() }, 13f, Hud.muted)
                .apply {
                    pad(20)
                    android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Data & licences")
                .setView(ScrollView(this).apply { addView(text) })
                .setPositiveButton("Close", null)
                .create()
        dialog.setOnDismissListener { if (noticesDialog === dialog) noticesDialog = null }
        noticesDialog = dialog
        dialog.show()
    }

    private fun field(
        body: LinearLayout,
        title: String,
        value: String,
    ): EditText {
        body.addView(label(title, 11f, Hud.muted, true))
        body.space(4)
        val input =
            EditText(this).apply {
                setText(value)
                setTextColor(Hud.ink)
                textSize = 16f
                setSingleLine(true)
                contentDescription = title
                inputType = InputType.TYPE_CLASS_TEXT
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = panel()
                minimumHeight = dp(48)
            }
        body.addView(input)
        body.space(14)
        return input
    }

    private fun zoneEntry(seed: Place) {
        val token = placeChoices.invalidate()
        val body = column().apply { pad(20) }
        body.addView(
            label(
                "Time zone could not be resolved for this place. Enter an IANA zone, e.g. Europe/Berlin.",
                12f,
                Hud.muted,
            )
        )
        body.space(12)
        val zone = field(body, "TIME ZONE (IANA ID)", "")
        val dialog =
            placeDialog(
                AlertDialog.Builder(this).setView(body).setPositiveButton("Use place", null),
                "Time zone",
            )
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!placeChoices.isCurrent(token)) return@setOnClickListener
                val value = zone.text.toString().trim()
                if (!Zones.valid(value)) {
                    zone.error = "Use a valid IANA zone, such as Europe/Berlin"
                    return@setOnClickListener
                }
                if (choose(seed.copy(zone = value))) {
                    dialog.dismiss()
                    showPage(Page.TODAY)
                }
            }
        }
        placeChoices.track(dialog, token)
        dialog.show()
    }

    private fun saveFavourite() {
        val token = placeChoices.invalidate()
        val body = column().apply { pad(20) }
        val name =
            field(
                body,
                "FAVOURITE LABEL",
                placeDisplayName(repo.place, repo.forecastPoint?.station),
            )
        val dialog =
            placeDialog(
                AlertDialog.Builder(this).setView(body).setPositiveButton("Save", null),
                "Save favourite",
            )
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = compactPlaceLabel(name.text.toString().trim())
                if (label.isEmpty()) {
                    name.error = "Enter a label"
                    return@setOnClickListener
                }
                if (!placeChoices.isCurrent(token)) return@setOnClickListener
                val place = repo.place.relabel(label)
                try {
                    store.save(place)
                } catch (error: Exception) {
                    Toast.makeText(
                            this,
                            "Favourite could not be saved. Please retry.",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    return@setOnClickListener
                }
                if (!choose(place)) return@setOnClickListener
                dialog.dismiss()
                locations()
                Toast.makeText(this, "Favourite saved", Toast.LENGTH_SHORT).show()
            }
        }
        placeChoices.track(dialog, token)
        dialog.show()
    }

    override fun onStart() {
        super.onStart()
        started = true
        stopped = false
        placeChoices.foreground(true)
        if (ready) {
            val changedWhileStopped = networkChangedWhileStopped
            networkChangedWhileStopped = false
            val was = repo.online
            val online = repo.connection()
            if (online && (!was || changedWhileStopped)) refresh()
        }
    }

    override fun onStop() {
        started = false
        stopped = true
        placeChoices.foreground(false)
        solarGeneration.next()
        solarFuture?.cancel(true)
        solarKey = null
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        placeChoices.onPermissionResult(requestCode)
    }

    override fun onResume() {
        super.onResume()
        active = true
        if (ready) handler.post(tick)
    }

    override fun onPause() {
        map.saveViewport()
        active = false
        setPlaying(false)
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("page", page.name)
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        dead = true
        ready = false
        started = false
        noticesDialog?.dismiss()
        noticesDialog = null
        placeChoices.close()
        progressSubscription?.cancel()
        progressSubscription = null
        handler.removeCallbacksAndMessages(null)
        runCatching {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(networkCallback)
        }
        map.dispose()
        repo.close()
        decoder.shutdownNow()
        solarGeneration.next()
        solarFuture?.cancel(true)
        solarWorker.shutdownNow()
        frames.evictAll()
        super.onDestroy()
    }

    private fun number(n: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", n)

    private fun coords(lat: Double, lon: Double) =
        "${number(abs(lat),4)}°${if(lat>=0)"N" else "S"}  ${number(abs(lon),4)}°${if(lon>=0)"E" else "W"}"
}
