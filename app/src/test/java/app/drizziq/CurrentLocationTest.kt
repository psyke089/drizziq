package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class CurrentLocationTest {
    private fun fix(
        provider: String = "network",
        time: Long = 100_000_000_000,
        accuracy: Double? = 100.0,
        lat: Double = 48.1,
        lon: Double = 11.5,
    ) = PositionFix(lat, lon, accuracy, time, provider)

    private class Timer : LocationTimer {
        var delay = 0L
        var callback: (() -> Unit)? = null
        var cancelled = false

        override fun schedule(delayMillis: Long, callback: () -> Unit): Cancel {
            delay = delayMillis
            this.callback = callback
            cancelled = false
            return Cancel { cancelled = true }
        }

        fun fire() {
            callback?.invoke()
        }
    }

    private class Source : PositionSource {
        var enabled = listOf("network", "gps")
        var denied = false
        val cached = mutableMapOf<String, PositionFix>()
        val listeners = mutableMapOf<String, (PositionFix) -> Unit>()
        val unavailable = mutableMapOf<String, () -> Unit>()
        var removals = 0
        var synchronous: PositionFix? = null

        override fun enabledProviders(): List<String> {
            if (denied) throw SecurityException()
            return enabled
        }

        override fun lastKnown(provider: String) = cached[provider]

        override fun listen(
            provider: String,
            onFix: (PositionFix) -> Unit,
            onUnavailable: () -> Unit,
        ): Cancel {
            listeners[provider] = onFix
            unavailable[provider] = onUnavailable
            synchronous?.takeIf { it.provider == provider }?.let(onFix)
            return Cancel {
                listeners.remove(provider)
                unavailable.remove(provider)
                removals++
            }
        }
    }

    @Test
    fun usable_ageAccuracyAndCoordinateBoundaries_areInclusiveWithoutWallClock() {
        val now = 220_000_000_000L
        assertTrue(FixPolicy.usable(fix(accuracy = 5000.0), now))
        assertTrue(FixPolicy.usable(fix(time = now), now))
        for (bad in
            listOf(
                fix(time = 99_999_999_999),
                fix(time = now + 1),
                fix(accuracy = null),
                fix(accuracy = 0.0),
                fix(accuracy = 5000.01),
                fix(accuracy = Double.NaN),
                fix(lat = 91.0),
                fix(lon = Double.POSITIVE_INFINITY),
            )) assertFalse(FixPolicy.usable(bad, now))
    }

    @Test
    fun cachedFixes_newestThenAccuracyThenProvider_areDeterministic() {
        val a = fix("network", accuracy = 10.0)
        val b = fix("gps", accuracy = 10.0)
        assertEquals(b, FixPolicy.newestUsable(listOf(a, b), 101_000_000_000))
        assertEquals(
            a,
            FixPolicy.newestUsable(listOf(b.copy(accuracyMetres = 20.0), a), 101_000_000_000),
        )
        assertEquals(
            a.copy(elapsedNanos = 101_000_000_000),
            FixPolicy.newestUsable(
                listOf(b, a.copy(elapsedNanos = 101_000_000_000)),
                101_000_000_000,
            ),
        )
    }

    @Test
    fun providers_deniedApproximateAndPrecise_respectEnabledProviderCapabilities() {
        assertTrue(FixPolicy.providers(false, false, setOf("network", "gps")).isEmpty())
        assertEquals(listOf("network"), FixPolicy.providers(true, false, setOf("network", "gps")))
        assertEquals(
            setOf("network", "gps"),
            FixPolicy.providers(true, true, setOf("network", "gps")).toSet(),
        )
        assertEquals(listOf("gps"), FixPolicy.providers(false, true, setOf("gps")))
    }

    @Test
    fun start_usableLastKnown_returnsSnapshotWithoutListenersOrTimeout() {
        val source =
            Source().apply {
                cached["network"] = PositionFix(48.1, 11.5, 100.0, 100_000_000_000, "network")
            }
        val timer = Timer()
        val result = mutableListOf<LocationResult>()
        OneShotLocation(source, { 100_000_000_000 }, timer).start { result += it }
        assertTrue(result.single() is LocationResult.Fix)
        assertTrue(source.listeners.isEmpty())
        assertNull(timer.callback)
    }

    @Test
    fun start_waitsForFirstUsableFix_thenRemovesAllListenersAndTimeoutBeforeCallback() {
        val source = Source()
        val timer = Timer()
        val result = mutableListOf<LocationResult>()
        OneShotLocation(source, { 100_000_000_000 }, timer).start {
            assertTrue(source.listeners.isEmpty())
            assertTrue(timer.cancelled)
            result += it
        }
        assertEquals(20000, timer.delay)
        assertEquals(2, source.listeners.size)
        val stale = source.listeners["network"]!!
        stale(fix(accuracy = 5001.0))
        assertTrue(result.isEmpty())
        source.listeners["gps"]!!(fix("gps"))
        stale(fix())
        timer.fire()
        assertEquals(1, result.size)
        assertEquals(2, source.removals)
    }

    @Test
    fun cancellationReplacementAndDestruction_ignoreLateCallbacksAndCleanEveryResource() {
        val source = Source()
        val timer = Timer()
        val engine = OneShotLocation(source, { 100_000_000_000 }, timer)
        val result = mutableListOf<LocationResult>()
        engine.start { result += it }
        val old = source.listeners["network"]!!
        engine.cancel()
        old(fix())
        timer.fire()
        assertTrue(result.isEmpty())
        assertTrue(source.listeners.isEmpty())
        engine.start { result += it }
        val replaced = source.listeners["gps"]!!
        engine.start { result += it }
        replaced(fix("gps"))
        assertTrue(result.isEmpty())
        val late = source.listeners["network"]!!
        engine.close()
        late(fix())
        timer.fire()
        assertTrue(result.isEmpty())
        assertTrue(source.listeners.isEmpty())
        assertTrue(timer.cancelled)
    }

    @Test
    fun timeoutDoesNotRelaxQuality_andNoProvidersOrRevocation_areFailures() {
        val source = Source()
        val timer = Timer()
        val result = mutableListOf<LocationResult>()
        val engine = OneShotLocation(source, { 100_000_000_000 }, timer)
        engine.start { result += it }
        source.listeners["gps"]!!(fix("gps", accuracy = 5001.0))
        timer.fire()
        assertEquals(LocationFailure.TIMEOUT, (result.single() as LocationResult.Failure).reason)
        assertTrue(source.listeners.isEmpty())
        result.clear()
        source.enabled = emptyList()
        engine.start { result += it }
        assertEquals(
            LocationFailure.UNAVAILABLE,
            (result.single() as LocationResult.Failure).reason,
        )
        result.clear()
        source.enabled = listOf("gps")
        engine.start { result += it }
        val pending = source.listeners["gps"]!!
        source.denied = true
        pending(fix("gps"))
        assertEquals(LocationFailure.PERMISSION, (result.single() as LocationResult.Failure).reason)
        assertTrue(source.listeners.isEmpty())
    }

    @Test
    fun synchronousProviderSuccess_alsoRemovesReturnedListener() {
        val source =
            Source().apply {
                synchronous = PositionFix(48.1, 11.5, 100.0, 100_000_000_000, "network")
            }
        val timer = Timer()
        val result = mutableListOf<LocationResult>()
        OneShotLocation(source, { 100_000_000_000 }, timer).start { result += it }
        assertEquals(1, result.size)
        assertTrue(source.listeners.isEmpty())
        assertTrue(timer.cancelled)
    }
}
