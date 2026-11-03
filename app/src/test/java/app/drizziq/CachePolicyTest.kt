package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class CachePolicyTest {
    private fun entry(name: String, bytes: Long, accessed: Long) =
        CachePolicy.Entry(name, bytes, accessed)

    @Test
    fun policyConstants_matchTheDocumentedBudgetsAndAges() {
        assertEquals(64L * 1024 * 1024, CachePolicy.BASEMAP_BYTES)
        assertEquals(32L * 1024 * 1024, CachePolicy.FORECAST_BYTES)
        assertEquals(256, CachePolicy.FAILED_TILE_KEYS)
        assertEquals(24L * 60 * 60 * 1000, CachePolicy.ABANDONED_TEMP_MS)
        assertEquals(3L * 24 * 60 * 60 * 1000, CachePolicy.HISTORY_MS)
        assertEquals(7L * 24 * 60 * 60 * 1000, CachePolicy.TILE_FRESH_MS)
    }

    @Test
    fun evictions_exactBasemapAndForecastBoundaries() {
        val atBasemap = listOf(entry("map", CachePolicy.BASEMAP_BYTES, 1))
        assertEquals(
            emptyList<String>(),
            CachePolicy.evictions(atBasemap, CachePolicy.BASEMAP_BYTES),
        )
        assertEquals(
            listOf("map"),
            CachePolicy.evictions(atBasemap, CachePolicy.BASEMAP_BYTES - 1),
        )
        val overForecast =
            listOf(
                entry("old", CachePolicy.FORECAST_BYTES / 2, 1),
                entry("new", CachePolicy.FORECAST_BYTES / 2 + 1, 2),
            )
        assertEquals(
            listOf("old"),
            CachePolicy.evictions(overForecast, CachePolicy.FORECAST_BYTES),
        )
    }

    @Test
    fun evictions_atOrBelowBudgetKeepEverything() {
        val entries =
            listOf(
                entry("a", 40, 1),
                entry("b", 30, 2),
                entry("c", 20, 3),
            )

        assertEquals(emptyList<String>(), CachePolicy.evictions(entries, 90))
        assertEquals(emptyList<String>(), CachePolicy.evictions(entries, 1000))
    }

    @Test
    fun evictions_overBudgetRemovesLeastRecentlyAccessedFirst() {
        val entries =
            listOf(
                entry("a", 40, 1),
                entry("b", 30, 2),
                entry("c", 20, 3),
            )

        assertEquals(listOf("a"), CachePolicy.evictions(entries, 70))
        assertEquals(listOf("a", "b"), CachePolicy.evictions(entries, 49))
    }

    @Test
    fun evictions_stopsAtTheExactBudgetBoundary() {
        val entries =
            listOf(
                entry("a", 40, 1),
                entry("b", 30, 2),
                entry("c", 20, 3),
            )

        // 90 - 40 = 50, so only "a" has to go.
        assertEquals(listOf("a"), CachePolicy.evictions(entries, 50))
    }

    @Test
    fun evictions_neverDeletesProtectedActiveFiles() {
        val entries =
            listOf(
                entry("active-oldest", 40, 1),
                entry("b", 30, 2),
                entry("c", 20, 3),
            )

        assertEquals(
            listOf("b"),
            CachePolicy.evictions(entries, 60, protected = setOf("active-oldest")),
        )
    }

    @Test
    fun evictions_equalAccessTimesAreBrokenStablyByName() {
        val entries =
            listOf(
                entry("z", 30, 7),
                entry("a", 30, 7),
                entry("m", 30, 7),
            )

        assertEquals(listOf("a"), CachePolicy.evictions(entries, 60))
    }

    @Test
    fun boundedAccessMap_256KeysKeepAndThe257thEvictsTheOldest() {
        val failed = BoundedAccessMap<String, Long>(CachePolicy.FAILED_TILE_KEYS)

        (0 until 256).forEach { failed["key-$it"] = it.toLong() }

        assertEquals(256, failed.size)

        failed["key-256"] = 256

        assertEquals(256, failed.size)
        assertNull(failed["key-0"])
        assertNotNull(failed["key-1"])
        assertNotNull(failed["key-256"])
    }

    @Test
    fun boundedAccessMap_readingAKeyMakesItMostRecentlyUsed() {
        val failed = BoundedAccessMap<String, Long>(2)
        failed["a"] = 0
        failed["b"] = 0

        assertNotNull(failed["a"])
        failed["c"] = 0

        assertNotNull(failed["a"])
        assertNull(failed["b"])
        assertNotNull(failed["c"])
    }
}
