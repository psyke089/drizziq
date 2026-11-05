package app.drizziq

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar

/** A translucent in-map panel, updated only with radar progress rather than unrelated HTTP. */
@SuppressLint("ViewConstructor") // Programmatic panel requires radar actions.
internal class RadarStatusPanel(
    context: Context,
    refresh: () -> Unit,
    clear: () -> Unit,
    close: () -> Unit,
) : LinearLayout(context) {
    private val last = context.label("", 11f, Hud.muted)
    private val state = context.label("", 12f)
    private val download = context.label("", 11f, Hud.muted, true)
    private val processing = context.label("", 11f, Hud.muted, true)
    private val history = context.label("", 11f, Hud.muted, true)
    private val downloadBar = bar()
    private val processingBar = bar()
    private val historyBar = bar()
    private val refreshButton = context.action("Refresh now", click = refresh)
    private val clearButton = context.action("Clear radar cache", click = clear)

    init {
        orientation = VERTICAL
        pad(16)
        background = panel(0xDF0F1A28.toInt(), Hud.line, context.dp(12).toFloat())
        isClickable = true
        val header = context.row()
        header.addView(context.label("RADAR", 12f, Hud.cyan, true), LayoutParams(0, -2, 1f))
        header.addView(
            FrameLayout(context).apply {
                contentDescription = "Close radar details"
                isClickable = true
                isFocusable = true
                addView(
                    SvgIcon(context, "close").apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    FrameLayout.LayoutParams(context.dp(18), context.dp(18), Gravity.CENTER),
                )
                setOnClickListener { close() }
            },
            LayoutParams(context.dp(48), context.dp(48)),
        )
        addView(header)
        addView(last)
        space(10)
        addView(state)
        space(12)
        for ((text, bar) in
            listOf(download to downloadBar, processing to processingBar, history to historyBar)) {
            addView(text)
            addView(
                bar,
                LayoutParams(-1, context.dp(4)).apply {
                    topMargin = context.dp(5)
                    bottomMargin = context.dp(12)
                },
            )
        }
        val actions = context.row().apply { gravity = Gravity.CENTER }
        actions.addView(refreshButton, LayoutParams(0, -2, 1f))
        actions.addView(clearButton, LayoutParams(0, -2, 1f).apply { leftMargin = context.dp(8) })
        addView(actions)
    }

    private fun bar() =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Hud.cyan)
            indeterminateTintList = ColorStateList.valueOf(Hud.cyan)
            progressBackgroundTintList = ColorStateList.valueOf(Hud.line)
            setPadding(0, 0, 0, 0)
            minimumHeight = 0
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun ProgressBar.bind(done: Long, total: Long?, busy: Boolean) {
        isIndeterminate = busy && (total == null || total <= 0)
        progress =
            if (total != null && total > 0) (1000 * done / total).toInt().coerceIn(0, 1000) else 0
    }

    fun bind(reading: Reading<RadarSequence>, progress: RadarProgress, zone: String) {
        last.text =
            context.hudString(
                R.string.radar_last_download,
                reading.fetched?.let { Times.format(it, "dd MMM HH:mm:ss z", zone) } ?: "—",
            )
        state.text =
            when (progress.phase) {
                RadarPhase.IDLE -> if (reading.data == null) "No radar cached" else "Saved radar"
                RadarPhase.DOWNLOAD -> "Checking / downloading radar…"
                RadarPhase.PROCESSING -> "Preparing radar images…"
                RadarPhase.HISTORY -> "Completing observed history…"
                RadarPhase.COMPLETE ->
                    if (reading.data?.historyComplete == false) "Ready · gaps in observed history"
                    else if (progress.bytes == 0L) "Radar unchanged" else "Radar ready"
                RadarPhase.FAILED -> reading.message ?: "Radar refresh failed"
                RadarPhase.CLEARING -> "Clearing radar cache…"
            }
        fun kb(value: Long) = context.hudString(R.string.radar_bytes, (value + 999) / 1000)
        download.text =
            if (progress.phase == RadarPhase.COMPLETE && progress.bytes == 0L)
                context.hudString(R.string.radar_download_unchanged)
            else
                context.hudString(
                    R.string.radar_download,
                    progress.totalBytes?.let {
                        context.hudString(R.string.radar_bytes_total, kb(progress.bytes), kb(it))
                    } ?: kb(progress.bytes),
                )
        processing.text =
            context.hudString(
                R.string.radar_images,
                progress.processed,
                progress.totalFrames.takeIf { it > 0 }?.let { hudFormat("%d", it) } ?: "—",
            )
        history.text = context.hudString(R.string.radar_history, progress.history)
        downloadBar.bind(progress.bytes, progress.totalBytes, progress.phase == RadarPhase.DOWNLOAD)
        processingBar.bind(
            progress.processed.toLong(),
            progress.totalFrames.toLong(),
            progress.phase == RadarPhase.PROCESSING,
        )
        historyBar.bind(progress.history.toLong(), 12, false)
        refreshButton.isEnabled = !reading.refreshing && progress.phase != RadarPhase.CLEARING
        clearButton.isEnabled = progress.phase != RadarPhase.CLEARING
    }
}
