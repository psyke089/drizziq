package app.drizziq

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Activity-owned place choice. Valid city text is submitted after a short typing pause. */
class PlaceChoices(
    private val activity: Activity,
    private val zones: ZoneLookup,
    private val online: () -> Boolean,
    private val select: (Place) -> Boolean,
    private val requireZone: (Place) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val generation = RequestGeneration()
    private val transport = GeocodeHttp()
    private val search =
        CitySearch(transport, { SystemClock.elapsedRealtime() }, { AppPlatform.now() })
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    private var future: Future<*>? = null
    private var dialog: AlertDialog? = null
    private var searchDebounce: SearchDebounce? = null
    private var foreground = false
    private var closed = false
    private var permissionRequest: Pair<Int, Int>? = null

    private companion object {
        val permissionCodes = java.util.concurrent.atomic.AtomicInteger(1000)
    }

    private val permissionHistory = activity.getSharedPreferences("location-permission", 0)
    private val locator =
        OneShotLocation(
            PlatformLocationSource(activity),
            { SystemClock.elapsedRealtimeNanos() },
            LocationTimer { delay, callback ->
                val task = Runnable { callback() }
                handler.postDelayed(task, delay)
                Cancel { handler.removeCallbacks(task) }
            },
        )

    fun isCurrent(token: Int) = !closed && foreground && generation.isCurrent(token)

    fun invalidate(dismiss: Boolean = true): Int {
        searchDebounce?.cancel()
        val token = generation.next()
        future?.cancel(true)
        future = null
        executor.queue.clear()
        runCatching { transport.cancel() }
        locator.cancel()
        permissionRequest = null
        if (dismiss) {
            val old = dialog
            dialog = null
            old?.dismiss()
        }
        return token
    }

    fun cancel(token: Int) {
        if (generation.isCurrent(token)) invalidate()
    }

    private fun track(d: AlertDialog, token: () -> Int) {
        dialog = d
        d.setOnCancelListener { cancel(token()) }
        d.setOnDismissListener { cancel(token()) }
    }

    fun track(d: AlertDialog, token: Int) = track(d) { token }

    fun foreground(value: Boolean) {
        foreground = value
        if (!value) invalidate()
    }

    fun close() {
        closed = true
        foreground = false
        invalidate()
        locator.close()
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    fun clearCache() {
        invalidate()
        search.clearCache()
        Toast.makeText(activity, "City-search cache cleared", Toast.LENGTH_SHORT).show()
    }

    fun searchCity() {
        if (closed) return
        var token = invalidate()
        val body = activity.column().apply { pad(20) }
        body.addView(
            activity.label(activity.getString(R.string.city_search_disclosure), 13f, Hud.muted)
        )
        body.space(12)
        body.addView(activity.label("CITY NAME", 11f, Hud.muted, true))
        body.space(6)
        val input =
            EditText(activity).apply {
                contentDescription = "CITY NAME"
                setSingleLine(true)
                setTextColor(Hud.ink)
                textSize = 16f
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                background = panel()
                pad(10)
            }
        body.addView(input)
        body.space(4)
        body.addView(activity.placeCreditView())
        body.space(10)
        val status = activity.label(activity.getString(R.string.city_search_hint), 12f, Hud.muted)
        body.addView(status)
        body.space(10)
        val rows = activity.column()
        body.addView(rows)
        val d =
            activity.placeDialog(
                AlertDialog.Builder(activity).setView(ScrollView(activity).apply { addView(body) }),
                activity.getText(R.string.city_search_title),
            )
        track(d) { token }
        fun submit(text: String, force: Boolean) {
            if (closed || !foreground) return
            val query =
                try {
                    Geocoding.query(text)
                } catch (error: IllegalArgumentException) {
                    input.error = error.message
                    status.text = error.message
                    status.setTextColor(Hud.amber)
                    return
                }
            input.error = null
            token = invalidate(false)
            val request = token
            rows.removeAllViews()
            status.setText(R.string.city_search_waiting)
            status.setTextColor(Hud.muted)
            val connected = online()
            future = executor.submit {
                val result =
                    search.search(
                        query,
                        force,
                        connected,
                        { generation.isCurrent(request) },
                        { action -> generation.runIfCurrent(request, action) },
                    )
                handler.post {
                    if (!isCurrent(request)) return@post
                    val candidates =
                        when (result) {
                            is SearchResult.Results -> {
                                status.text =
                                    if (result.cached)
                                        activity.getString(R.string.city_search_cached)
                                    else
                                        activity.resources.getQuantityString(
                                            R.plurals.city_search_results,
                                            result.candidates.size,
                                            result.candidates.size,
                                        )
                                status.setTextColor(Hud.muted)
                                result.candidates
                            }
                            is SearchResult.Empty -> {
                                status.setText(R.string.city_search_empty)
                                status.setTextColor(Hud.muted)
                                emptyList()
                            }
                            is SearchResult.Failure -> {
                                status.text =
                                    if (result.candidates.isEmpty()) result.message
                                    else
                                        activity.getString(
                                            R.string.city_search_retained,
                                            result.message,
                                        )
                                status.setTextColor(Hud.amber)
                                result.candidates
                            }
                            SearchResult.Obsolete -> return@post
                        }
                    rows.removeAllViews()
                    for (candidate in candidates) {
                        val detail = "${candidate.fullLabel}\n${candidate.lat}, ${candidate.lon}"
                        rows.addView(
                            activity
                                .action(
                                    detail,
                                    "Select city result ${candidate.id}; ${candidate.fullLabel}; latitude ${candidate.lat}; longitude ${candidate.lon}",
                                ) {
                                    if (isCurrent(request)) {
                                        val place = candidate.toPlace { a, b -> zones.at(a, b) }
                                        if (place != null) select(place)
                                        else {
                                            invalidate()
                                            requireZone(
                                                Place(
                                                    candidate.label,
                                                    candidate.lat,
                                                    candidate.lon,
                                                    "",
                                                    "open-meteo:${candidate.id}",
                                                )
                                            )
                                        }
                                    }
                                }
                                .apply {
                                    textSize = 12f
                                    maxLines = 3
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                }
                        )
                        rows.space(8)
                    }
                }
            }
        }
        val debounce =
            SearchDebounce(
                { delay, action ->
                    val task = Runnable { action() }
                    handler.postDelayed(task, delay)
                    Cancel { handler.removeCallbacks(task) }
                },
                { token = invalidate(false) },
                { text, force -> submit(text, force) },
            )
        searchDebounce = debounce
        input.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    input.error = null
                    rows.removeAllViews()
                    status.setText(R.string.city_search_hint)
                    status.setTextColor(Hud.muted)
                    debounce.edited(s?.toString().orEmpty())
                }

                override fun afterTextChanged(s: Editable?) {}
            }
        )
        input.setOnEditorActionListener { _, action, event ->
            if (
                action == EditorInfo.IME_ACTION_SEARCH ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN)
            ) {
                debounce.immediate(input.text.toString())
                true
            } else false
        }
        d.show()
    }

    private fun permitted() =
        activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun currentLocation() {
        if (closed) return
        val token = invalidate()
        if (permitted()) acquire(token)
        else {
            val permanent =
                permissionHistory.getBoolean("asked", false) &&
                    !activity.shouldShowRequestPermissionRationale(
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) &&
                    !activity.shouldShowRequestPermissionRationale(
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
            if (permanent) denied(token, true)
            else {
                val d =
                    activity.placeDialog(
                        AlertDialog.Builder(activity)
                            .setMessage(R.string.location_purpose)
                            .setPositiveButton("Continue", null),
                        "Current location",
                    )
                track(d, token)
                d.setOnShowListener {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (isCurrent(token)) {
                            permissionHistory.edit().putBoolean("asked", true).apply()
                            val code = permissionCodes.updateAndGet {
                                if (it >= 65000) 1000 else it + 1
                            }
                            permissionRequest = code to token
                            d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                            activity.requestPermissions(
                                arrayOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                ),
                                code,
                            )
                        }
                    }
                }
                d.show()
            }
        }
    }

    fun onPermissionResult(code: Int) {
        val pending = permissionRequest ?: return
        if (pending.first != code || !isCurrent(pending.second)) return
        permissionRequest = null
        if (permitted()) acquire(pending.second)
        else {
            val permanent =
                !activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) &&
                    !activity.shouldShowRequestPermissionRationale(
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
            denied(pending.second, permanent)
        }
    }

    private fun replaceDialog(d: AlertDialog, token: Int) {
        // Invalidate the old dialog's dismiss callback without invalidating this acquisition.
        val old = dialog
        dialog = null
        old?.setOnDismissListener(null)
        old?.setOnCancelListener(null)
        old?.dismiss()
        track(d, token)
        d.show()
    }

    private fun denied(token: Int, permanent: Boolean) {
        val builder =
            AlertDialog.Builder(activity)
                .setMessage(
                    if (permanent) R.string.location_permanent_denial else R.string.location_denied
                )
        if (permanent)
            builder.setPositiveButton("App settings") { _, _ ->
                cancel(token)
                activity.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${activity.packageName}"),
                    )
                )
            }
        replaceDialog(activity.placeDialog(builder, "Location access"), token)
    }

    private fun acquire(token: Int) {
        if (!isCurrent(token)) return
        val d =
            activity.placeDialog(
                AlertDialog.Builder(activity)
                    .setMessage(R.string.location_progress)
                    .setPositiveButton("Retry", null),
                "Current location",
            )
        replaceDialog(d, token)
        d.getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.GONE
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { currentLocation() }
        locator.start { result ->
            if (!isCurrent(token)) return@start
            when (result) {
                is LocationResult.Failure -> {
                    d.setMessage(
                        activity.getText(
                            when (result.reason) {
                                LocationFailure.UNAVAILABLE -> R.string.location_unavailable
                                LocationFailure.PERMISSION -> R.string.location_permission_changed
                                LocationFailure.TIMEOUT -> R.string.location_timeout
                            }
                        )
                    )
                    d.getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.VISIBLE
                }
                is LocationResult.Fix -> {
                    val fix = result.fix
                    val zone = Geocoding.zone(null, fix.lat, fix.lon) { a, b -> zones.at(a, b) }
                    val place = Place("Current location", fix.lat, fix.lon, zone ?: "")
                    if (zone == null) {
                        invalidate()
                        requireZone(place)
                    } else if (select(place)) {
                        Toast.makeText(
                                activity,
                                activity.getString(R.string.location_success, fix.accuracyMetres),
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    } else {
                        d.setMessage("Location could not be saved. Please retry.")
                        d.getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.VISIBLE
                    }
                }
            }
        }
    }
}
