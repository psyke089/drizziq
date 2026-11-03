package app.drizziq

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val day = 86_400_000L
    private var now = 1_800_000_000_000L
    private val clock = { now }

    private fun store(directory: File, budget: Long) =
        CacheStore(
            directory,
            budget,
            eligible = { it.endsWith(".kmz") || it.endsWith(".png") },
            clock = clock,
        )

    private fun file(directory: File, name: String, bytes: Int, modified: Long): File =
        File(directory, name).apply {
            writeBytes(ByteArray(bytes) { 7 })
            setLastModified(modified)
        }

    @Test
    fun read_recordsAccessWithoutChangingTheReceiptTimestamp() {
        val directory = temp.newFolder("cache")
        val receipt = now - 10 * day
        val tile = file(directory, "7-1-1.png", 30, receipt)
        val cache = store(directory, 64L)

        cache.read(tile)

        assertEquals(receipt, tile.lastModified())
        assertTrue(File(directory, "cache-access.tsv").readText().contains("7-1-1.png"))
    }

    @Test
    fun prune_prefersRecordedAccessRecencyOverReceiptTimestamps() {
        val directory = temp.newFolder("cache")
        val newestReceipt = file(directory, "a.kmz", 30, now - 1)
        val oldestReceipt = file(directory, "b.kmz", 30, now - 10 * day)
        val middleReceipt = file(directory, "c.kmz", 30, now - 5 * day)
        val access = store(directory, 64L)
        now = 100
        access.read(newestReceipt)
        now = 200
        access.read(oldestReceipt)
        now = 300
        access.read(middleReceipt)

        store(directory, 60L).prune()

        // "a" has the newest receipt but was accessed first, so it is the LRU victim.
        assertFalse(newestReceipt.exists())
        assertTrue(oldestReceipt.exists())
        assertTrue(middleReceipt.exists())
    }

    @Test
    fun prune_expiresHistoryOnlyPastTheThreeDayBoundary() {
        val directory = temp.newFolder("cache")
        val stale = file(directory, "MOSMIX_L_20261001_00_10865.kmz", 10, now - 3 * day - 1)
        val boundary = file(directory, "MOSMIX_L_20261002_00_10865.kmz", 10, now - 3 * day)
        val latest = file(directory, "forecast-10865.kmz", 10, now - 10 * day)
        val cache = store(directory, 1024L)

        cache.prune { candidate, time ->
            candidate.name.startsWith("MOSMIX_L_") && time - candidate.lastModified() > 3 * day
        }

        assertFalse(stale.exists())
        assertTrue(boundary.exists())
        assertTrue(latest.exists())
    }

    @Test
    fun accessRecencySurvivesANewStoreForStartupPruning() {
        val directory = temp.newFolder("cache")
        val firstReceipt = file(directory, "first.kmz", 30, now - day)
        val secondReceipt = file(directory, "second.kmz", 30, now - 2 * day)
        store(directory, 64L).read(secondReceipt)

        store(directory, 30L).prune()

        assertFalse(firstReceipt.exists())
        assertTrue(secondReceipt.exists())
    }

    @Test
    fun wrote_prunesImmediatelyUsingTheNewAccessTime() {
        val directory = temp.newFolder("cache")
        val older = file(directory, "older.kmz", 30, now - day)
        val written = file(directory, "written.kmz", 30, now - 2 * day)
        val cache = store(directory, 30L)

        cache.wrote(written)

        assertFalse(older.exists())
        assertTrue(written.exists())
    }

    @Test
    fun prune_isSafeWhenTheDirectoryHasNoEligibleFiles() {
        val directory = temp.newFolder("cache")
        val other = file(directory, "notes.txt", 100, now - 30 * day)

        store(directory, 0L).prune()

        assertTrue(other.exists())
    }
}
