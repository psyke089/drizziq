package app.drizziq

data class PositionFix(
    val lat: Double,
    val lon: Double,
    val accuracyMetres: Double?,
    val elapsedNanos: Long,
    val provider: String,
)

object FixPolicy {
    fun usable(fix: PositionFix, now: Long): Boolean {
        val accuracy = fix.accuracyMetres ?: return false
        return validCoordinates(fix.lat, fix.lon) &&
            accuracy.isFinite() &&
            accuracy > 0 &&
            accuracy <= 5000 &&
            fix.elapsedNanos >= 0 &&
            now >= fix.elapsedNanos &&
            now - fix.elapsedNanos <= 120_000_000_000L
    }

    fun newestUsable(fixes: List<PositionFix>, now: Long): PositionFix? =
        fixes
            .filter { usable(it, now) }
            .minWithOrNull(
                compareByDescending<PositionFix> { it.elapsedNanos }
                    .thenBy { it.accuracyMetres }
                    .thenBy { it.provider }
            )

    fun providers(coarse: Boolean, fine: Boolean, enabled: Set<String>): List<String> =
        listOf("network", "gps").filter {
            it in enabled && ((it == "network" && (coarse || fine)) || (it == "gps" && fine))
        }
}

fun interface Cancel {
    fun cancel()
}

fun interface LocationTimer {
    fun schedule(delayMillis: Long, callback: () -> Unit): Cancel
}

interface PositionSource {
    fun enabledProviders(): List<String>

    fun lastKnown(provider: String): PositionFix?

    fun listen(provider: String, onFix: (PositionFix) -> Unit, onUnavailable: () -> Unit): Cancel
}

enum class LocationFailure {
    UNAVAILABLE,
    PERMISSION,
    TIMEOUT,
}

sealed class LocationResult {
    data class Fix(val fix: PositionFix) : LocationResult()

    data class Failure(val reason: LocationFailure) : LocationResult()
}

/** Main-thread owner of exactly one acquisition; platform and time are replaceable in JVM tests. */
class OneShotLocation(
    private val source: PositionSource,
    private val now: () -> Long,
    private val timer: LocationTimer,
) {
    private var generation = 0
    private var active = false
    private var closed = false
    private var callback: ((LocationResult) -> Unit)? = null
    private var timeout: Cancel? = null
    private val listeners = mutableMapOf<String, Cancel>()

    private fun current(token: Int) = active && token == generation && !closed

    private fun cleanup() {
        val old = listeners.values.toList()
        listeners.clear()
        val deadline = timeout
        timeout = null
        runCatching { deadline?.cancel() }
        old.forEach { runCatching { it.cancel() } }
    }

    private fun finish(token: Int, result: LocationResult) {
        if (!current(token)) return
        active = false
        val completion = callback
        callback = null
        cleanup()
        completion?.invoke(result)
    }

    fun start(callback: (LocationResult) -> Unit) {
        cancel()
        if (closed) return
        val token = generation
        active = true
        this.callback = callback
        try {
            val providers = source.enabledProviders().distinct()
            if (providers.isEmpty()) {
                finish(token, LocationResult.Failure(LocationFailure.UNAVAILABLE))
                return
            }
            val fixes = providers.mapNotNull { provider ->
                source.lastKnown(provider)?.takeIf { it.provider == provider }
            }
            val cached = FixPolicy.newestUsable(fixes, now())
            if (cached != null) {
                finish(
                    token,
                    if (cached.provider in source.enabledProviders()) LocationResult.Fix(cached)
                    else LocationResult.Failure(LocationFailure.PERMISSION),
                )
                return
            }
            val deadline =
                timer.schedule(20000) {
                    finish(token, LocationResult.Failure(LocationFailure.TIMEOUT))
                }
            if (current(token)) timeout = deadline
            else {
                deadline.cancel()
                return
            }
            for (provider in providers) {
                if (!current(token)) break
                val remove =
                    source.listen(
                        provider,
                        { fix ->
                            if (current(token)) {
                                try {
                                    if (provider !in source.enabledProviders()) {
                                        finish(
                                            token,
                                            LocationResult.Failure(LocationFailure.PERMISSION),
                                        )
                                    } else if (
                                        fix.provider == provider && FixPolicy.usable(fix, now())
                                    )
                                        finish(token, LocationResult.Fix(fix))
                                } catch (error: SecurityException) {
                                    finish(
                                        token,
                                        LocationResult.Failure(LocationFailure.PERMISSION),
                                    )
                                } catch (error: Exception) {
                                    finish(
                                        token,
                                        LocationResult.Failure(LocationFailure.UNAVAILABLE),
                                    )
                                }
                            }
                        },
                        {
                            if (current(token)) {
                                runCatching { listeners.remove(provider)?.cancel() }
                                try {
                                    if (source.enabledProviders().isEmpty())
                                        finish(
                                            token,
                                            LocationResult.Failure(LocationFailure.UNAVAILABLE),
                                        )
                                } catch (error: SecurityException) {
                                    finish(
                                        token,
                                        LocationResult.Failure(LocationFailure.PERMISSION),
                                    )
                                } catch (error: Exception) {
                                    finish(
                                        token,
                                        LocationResult.Failure(LocationFailure.UNAVAILABLE),
                                    )
                                }
                            }
                        },
                    )
                if (!current(token)) remove.cancel()
                else {
                    val stillEnabled =
                        try {
                            source.enabledProviders()
                        } catch (error: Exception) {
                            remove.cancel()
                            throw error
                        }
                    if (provider in stillEnabled) listeners[provider] = remove
                    else {
                        remove.cancel()
                        if (stillEnabled.isEmpty())
                            finish(token, LocationResult.Failure(LocationFailure.PERMISSION))
                    }
                }
            }
        } catch (error: SecurityException) {
            finish(token, LocationResult.Failure(LocationFailure.PERMISSION))
        } catch (error: Exception) {
            finish(token, LocationResult.Failure(LocationFailure.UNAVAILABLE))
        }
    }

    fun cancel() {
        active = false
        generation++
        callback = null
        cleanup()
    }

    fun close() {
        cancel()
        closed = true
    }
}
