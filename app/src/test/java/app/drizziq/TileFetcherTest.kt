package app.drizziq

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TileFetcherTest {
    @get:Rule val temp = TemporaryFolder()

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private val good = "good-image".toByteArray()
    private val key = "7-64-42"
    private val address = "https://tile.openstreetmap.org/7/64/42.png"

    private data class Call(val address: String, val etag: String?, val lastModified: String?)

    private class FakeFetch {
        val calls = mutableListOf<Call>()
        var reply: (Call) -> HttpReply = { throw IOException("offline") }

        val fetch: (String, String?, String?) -> HttpReply = { address, etag, modified ->
            Call(address, etag, modified).also { calls += it }.let(reply)
        }
    }

    private fun folder() = temp.newFolder("tiles")

    private fun decoder(bytes: ByteArray) = bytes.takeIf { it.contentEquals(good) }

    private fun loader(
        folder: File,
        receipts: TileReceipts = TileReceipts(File(folder, "tile-receipts.tsv")),
        fetch: (String, String?, String?) -> HttpReply,
        clock: () -> Long = { now },
        write: (File, ByteArray) -> Unit = AtomicFiles::write,
        read: (File) -> Unit = {},
        wrote: (File) -> Unit = {},
    ): TileFetcher<ByteArray> =
        TileFetcher(
            folder = folder,
            receipts = receipts,
            decode = ::decoder,
            decodeFile = { file -> file.takeIf { it.isFile }?.readBytes()?.let(::decoder) },
            fetch = fetch,
            clock = clock,
            write = write,
            onRead = read,
            onWrite = wrote,
        )

    private fun tile(folder: File, bytes: ByteArray = good, modified: Long = now - 30 * day) =
        File(folder, "$key.png").apply {
            writeBytes(bytes)
            setLastModified(modified)
        }

    private fun receipt(
        folder: File,
        checkedAt: Long,
        etag: String? = "\"v1\"",
        modified: String? = "Mon, 02 Nov 2026 10:00:00 GMT",
    ) =
        TileReceipts(File(folder, "tile-receipts.tsv"))
            .put(
                key,
                TileReceipt(checkedAt, etag, modified),
            )

    @Test
    fun evictedTiles_discardReceiptsWithoutDiscardingLiveValidators() {
        val folder = folder()
        val receiptsFile = File(folder, "tile-receipts.tsv")
        val receipts = TileReceipts(receiptsFile)
        receipts.put("orphan", TileReceipt(now, "\"old\"", null))
        val storage = CacheStore(folder, good.size.toLong(), { it.endsWith(".png") }, { now })
        val fetch = FakeFetch().apply { reply = { HttpReply.Ok(good, "\"new\"", null) } }
        val loader =
            loader(folder, receipts = receipts, fetch = fetch.fetch, wrote = storage::wrote)

        loader.load(key, address)
        loader.load("7-64-43", "https://tile.openstreetmap.org/7/64/43.png")

        val persisted = TileReceipts(receiptsFile)
        assertNull(persisted.get("orphan"))
        val survivingKey = listOf(key, "7-64-43").single { File(folder, "$it.png").exists() }
        val evictedKey = listOf(key, "7-64-43").single { it != survivingKey }
        assertNull(persisted.get(evictedKey))
        assertEquals("\"new\"", persisted.get(survivingKey)?.etag)
    }

    @Test
    fun freshTile_usesTheCacheWithoutAnyRequest_atTheSevenDayBoundary() {
        val folder = folder()
        tile(folder, modified = now - 30 * day)
        receipt(folder, checkedAt = now - CachePolicy.TILE_FRESH_MS)
        val fetch = FakeFetch()
        var reads = 0

        val image = loader(folder, fetch = fetch.fetch, read = { reads++ }).load(key, address)

        assertArrayEquals(good, image)
        assertTrue(fetch.calls.isEmpty())
        assertEquals(1, reads)
    }

    @Test
    fun staleTile_sendsValidatorsAndA304RefreshesFreshnessWithoutDownloading() {
        val folder = folder()
        val cached = tile(folder)
        receipt(folder, checkedAt = now - CachePolicy.TILE_FRESH_MS - 1)
        val fetch =
            FakeFetch().apply {
                reply = { HttpReply.NotModified("\"v1\"", "Mon, 02 Nov 2026 10:00:00 GMT") }
            }

        val first = loader(folder, fetch = fetch.fetch).load(key, address)

        assertArrayEquals(good, first)
        assertEquals(1, fetch.calls.size)
        assertEquals("\"v1\"", fetch.calls[0].etag)
        assertEquals("Mon, 02 Nov 2026 10:00:00 GMT", fetch.calls[0].lastModified)
        assertArrayEquals(good, cached.readBytes())
        assertEquals(now, TileReceipts(File(folder, "tile-receipts.tsv")).get(key)?.checkedAt)

        // The refreshed receipt makes the same stale file fresh for seven more days.
        val second = FakeFetch()
        assertArrayEquals(good, loader(folder, fetch = second.fetch).load(key, address))
        assertTrue(second.calls.isEmpty())
    }

    @Test
    fun missingTile_fetchesUnconditionallyAndPersistsValidators() {
        val folder = folder()
        val fetch =
            FakeFetch().apply {
                reply = { HttpReply.Ok(good, "\"new\"", "Tue, 03 Nov 2026 09:00:00 GMT") }
            }

        val image = loader(folder, fetch = fetch.fetch).load(key, address)

        assertArrayEquals(good, image)
        assertEquals(1, fetch.calls.size)
        assertNull(fetch.calls[0].etag)
        assertNull(fetch.calls[0].lastModified)
        assertArrayEquals(good, File(folder, "$key.png").readBytes())
        val stored = TileReceipts(File(folder, "tile-receipts.tsv")).get(key)
        assertEquals("\"new\"", stored?.etag)
        assertEquals("Tue, 03 Nov 2026 09:00:00 GMT", stored?.lastModified)
        assertEquals(now, stored?.checkedAt)
    }

    @Test
    fun staleTile_withoutValidatorsFetchesUnconditionally() {
        val folder = folder()
        tile(folder)
        receipt(
            folder,
            checkedAt = now - CachePolicy.TILE_FRESH_MS - 1,
            etag = null,
            modified = null,
        )
        val fetch = FakeFetch().apply { reply = { HttpReply.Ok(good, "\"new\"", null) } }

        assertArrayEquals(good, loader(folder, fetch = fetch.fetch).load(key, address))

        assertNull(fetch.calls[0].etag)
        assertNull(fetch.calls[0].lastModified)
    }

    @Test
    fun notModifiedWithMissingCachedImage_isRecoveredByAnUnconditionalFetch() {
        val folder = folder()
        val fetch =
            FakeFetch().apply {
                reply = {
                    if (calls.size == 1) HttpReply.NotModified("\"v1\"", null)
                    else HttpReply.Ok(good, "\"v2\"", null)
                }
            }

        val image = loader(folder, fetch = fetch.fetch).load(key, address)

        assertArrayEquals(good, image)
        assertEquals(2, fetch.calls.size)
        assertNull(fetch.calls[0].etag)
        assertNull(fetch.calls[1].etag)
        assertArrayEquals(good, File(folder, "$key.png").readBytes())
        assertEquals("\"v2\"", TileReceipts(File(folder, "tile-receipts.tsv")).get(key)?.etag)
    }

    @Test
    fun notModifiedWithInvalidCachedImage_isRecoveredByAnUnconditionalFetch() {
        val folder = folder()
        tile(folder, bytes = "corrupt".toByteArray())
        receipt(folder, checkedAt = now - CachePolicy.TILE_FRESH_MS - 1)
        val fetch =
            FakeFetch().apply {
                reply = {
                    if (calls.size == 1) HttpReply.NotModified("\"v1\"", null)
                    else HttpReply.Ok(good, "\"v2\"", null)
                }
            }

        val image = loader(folder, fetch = fetch.fetch).load(key, address)

        assertArrayEquals(good, image)
        assertEquals(2, fetch.calls.size)
        assertNull(fetch.calls[0].etag)
        assertArrayEquals(good, File(folder, "$key.png").readBytes())
    }

    @Test
    fun networkFailure_preservesTheStaleTileAndItsReceipt() {
        val folder = folder()
        val cached = tile(folder)
        receipt(folder, checkedAt = now - CachePolicy.TILE_FRESH_MS - 1)
        val before = TileReceipts(File(folder, "tile-receipts.tsv")).get(key)
        val fetch = FakeFetch().apply { reply = { throw IOException("network down") } }

        val image = loader(folder, fetch = fetch.fetch).load(key, address)

        assertArrayEquals(good, image)
        assertArrayEquals(good, cached.readBytes())
        assertEquals(before, TileReceipts(File(folder, "tile-receipts.tsv")).get(key))
    }

    @Test
    fun cacheWriteFailure_keepsTheStaleTileAndDoesNotPublishFreshness() {
        val folder = folder()
        val cached = tile(folder)
        receipt(folder, checkedAt = now - CachePolicy.TILE_FRESH_MS - 1)
        val before = TileReceipts(File(folder, "tile-receipts.tsv")).get(key)
        val fetch = FakeFetch().apply { reply = { HttpReply.Ok(good, "\"v2\"", null) } }
        val failingWrite: (File, ByteArray) -> Unit = { _, _ -> throw IOException("disk full") }

        val image = loader(folder, fetch = fetch.fetch, write = failingWrite).load(key, address)

        assertArrayEquals(good, image)
        assertArrayEquals(good, cached.readBytes())
        assertEquals(before, TileReceipts(File(folder, "tile-receipts.tsv")).get(key))
    }

    @Test
    fun invalidDownloadedImage_preservesTheStaleTileAndReceipt() {
        val folder = folder()
        val cached = tile(folder)
        receipt(folder, checkedAt = now - CachePolicy.TILE_FRESH_MS - 1)
        val before = TileReceipts(File(folder, "tile-receipts.tsv")).get(key)
        val fetch =
            FakeFetch().apply {
                reply = { HttpReply.Ok("not-an-image".toByteArray(), "\"v2\"", null) }
            }

        val image = loader(folder, fetch = fetch.fetch).load(key, address)

        assertArrayEquals(good, image)
        assertArrayEquals(good, cached.readBytes())
        assertEquals(before, TileReceipts(File(folder, "tile-receipts.tsv")).get(key))
    }

    @Test
    fun offline_usesTheStaleCachedTileWithoutARequest() {
        val folder = folder()
        tile(folder)
        receipt(folder, checkedAt = now - 30 * day)
        val fetch = FakeFetch()

        val image = loader(folder, fetch = fetch.fetch).load(key, address, online = false)

        assertArrayEquals(good, image)
        assertTrue(fetch.calls.isEmpty())
    }

    @Test
    fun aTileOlderThanSevenDaysIsRevalidated() {
        val folder = folder()
        tile(folder)
        receipt(folder, checkedAt = now - CachePolicy.TILE_FRESH_MS - 1)
        val fetch = FakeFetch().apply { reply = { HttpReply.NotModified("\"v1\"", null) } }

        assertArrayEquals(good, loader(folder, fetch = fetch.fetch).load(key, address))
        assertEquals(1, fetch.calls.size)
    }

    @Test
    fun receiptsAndAccessRecencyAreStoredSeparately_andReceiptsSurvivePruning() {
        val folder = folder()
        var clock = now
        val storage =
            CacheStore(
                folder,
                1024L,
                eligible = { it.endsWith(".png") },
                clock = { clock },
                indexName = "access.tsv",
            )
        val receiptsFile = File(folder, "tile-receipts.tsv")
        val fetch = FakeFetch().apply { reply = { HttpReply.Ok(good, "\"v1\"", null) } }
        loader(
                folder,
                TileReceipts(receiptsFile),
                fetch.fetch,
                clock = { clock },
                read = storage::read,
                wrote = storage::wrote,
            )
            .load(key, address)
        File(folder, "$key.png").setLastModified(now - 30 * day)
        clock = now + 1

        storage.prune()

        assertTrue(File(folder, "$key.png").exists())
        assertTrue(receiptsFile.exists())
        val access = File(folder, "access.tsv")
        assertTrue(access.exists())
        assertTrue(access.readText().contains(key))
        assertEquals("\"v1\"", TileReceipts(receiptsFile).get(key)?.etag)

        // Freshness comes from the receipt, not the age of the image file.
        val afterPrune = FakeFetch()
        assertArrayEquals(
            good,
            loader(folder, TileReceipts(receiptsFile), fetch = afterPrune.fetch, clock = { clock })
                .load(key, address),
        )
        assertTrue(afterPrune.calls.isEmpty())
    }

    @Test
    fun conditionalRequestsThroughTheProductionHttpPolicy_roundTrip200Then304() {
        val folder = folder()
        var clock = now
        var response: HttpReply = HttpReply.Ok(good, "\"v1\"", "Mon, 02 Nov 2026 10:00:00 GMT")
        val connections = mutableListOf<FakeConnection>()
        val fetch: (String, String?, String?) -> HttpReply = { target, etag, modified ->
            Http.conditional(target, 1500000, etag, modified) { url ->
                FakeConnection(
                        url,
                        status = if (response is HttpReply.NotModified) 304 else 200,
                        bytes = (response as? HttpReply.Ok)?.bytes ?: ByteArray(0),
                        fields =
                            if (response is HttpReply.NotModified)
                                mapOf(
                                    "ETag" to "\"v1\"",
                                    "Last-Modified" to "Mon, 02 Nov 2026 10:00:00 GMT",
                                )
                            else
                                mapOf(
                                    "ETag" to "\"v1\"",
                                    "Last-Modified" to "Mon, 02 Nov 2026 10:00:00 GMT",
                                ),
                    )
                    .also { connections += it }
            }
        }
        val receipts = TileReceipts(File(folder, "tile-receipts.tsv"))
        val loader = loader(folder, receipts, fetch, clock = { clock })

        assertArrayEquals(good, loader.load(key, address))
        assertEquals(1, connections.size)
        assertNull(connections[0].getRequestProperty("If-None-Match"))

        clock = now + CachePolicy.TILE_FRESH_MS + 1
        response = HttpReply.NotModified("", "")
        assertArrayEquals(good, loader.load(key, address))

        assertEquals(2, connections.size)
        assertEquals("\"v1\"", connections[1].getRequestProperty("If-None-Match"))
        assertEquals(
            "Mon, 02 Nov 2026 10:00:00 GMT",
            connections[1].getRequestProperty("If-Modified-Since"),
        )
        assertArrayEquals(good, File(folder, "$key.png").readBytes())
    }

    private class FakeConnection(
        url: java.net.URL,
        private val status: Int,
        private val bytes: ByteArray,
        private val fields: Map<String, String>,
    ) : java.net.HttpURLConnection(url) {
        override fun connect() {}

        override fun disconnect() {}

        override fun usingProxy() = false

        override fun getResponseCode() = status

        override fun getInputStream() = java.io.ByteArrayInputStream(bytes)

        override fun getHeaderField(name: String) =
            fields.entries.firstOrNull { it.key.equals(name, true) }?.value
    }
}
