package app.drizziq

internal const val FORECAST_MAX_AGE = 9 * 3_600_000L
internal const val RADAR_MAX_AGE = 20 * 60_000L

/** Explicit time inputs keep expiry rules deterministic in JVM tests. */
internal fun issueFreshness(
    issue: Long,
    now: Long,
    maxAge: Long,
    online: Boolean = true,
    complete: Boolean = true,
): Freshness = if (!online || !complete || now - issue > maxAge) Freshness.STALE else Freshness.LIVE

internal fun <T> Reading<T>.failed(message: String): Reading<T> =
    copy(
        state = if (data == null) Freshness.ERROR else Freshness.STALE,
        refreshing = false,
        message = message,
    )

internal fun <T> Reading<T>.aged(
    issue: Long?,
    now: Long,
    maxAge: Long,
    message: String,
): Reading<T> =
    if (issue != null && now - issue > maxAge) copy(state = Freshness.STALE, message = message)
    else this

/** Used on the main thread to reject callbacks for a previous place selection. */
internal class RequestGeneration {
    @Volatile private var current = 0

    @Synchronized fun next(): Int = ++current

    fun isCurrent(token: Int): Boolean = token == current

    @Synchronized
    fun runIfCurrent(token: Int, action: () -> Unit): Boolean {
        if (token != current) return false
        action()
        return true
    }
}
