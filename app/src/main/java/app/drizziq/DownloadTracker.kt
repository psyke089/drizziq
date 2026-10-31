package app.drizziq

import java.util.concurrent.atomic.AtomicBoolean

/** Shared by weather, map tiles and geocoding. Each lease finishes exactly once. */
class DownloadTracker {
    private var count = 0
    private val listeners = mutableMapOf<Any, (Boolean) -> Unit>()
    val active: Boolean
        @Synchronized get() = count > 0

    private fun notifyListeners() {
        listeners.values.toList().forEach { runCatching { it(count > 0) } }
    }

    @Synchronized
    fun begin(): Cancel {
        count++
        if (count == 1) notifyListeners()
        val finished = AtomicBoolean(false)
        return Cancel { if (finished.compareAndSet(false, true)) finish() }
    }

    @Synchronized
    private fun finish() {
        count--
        if (count == 0) notifyListeners()
    }

    @Synchronized
    fun observe(listener: (Boolean) -> Unit): Cancel {
        val key = Any()
        listeners[key] = listener
        runCatching { listener(count > 0) }
        return Cancel { remove(key) }
    }

    @Synchronized
    private fun remove(key: Any) {
        listeners.remove(key)
    }

    fun <T> track(action: () -> T): T {
        val lease = begin()
        try {
            return action()
        } finally {
            lease.cancel()
        }
    }
}
