package app.drizziq

import java.io.File

/** Conditional-request validators plus the last successful receipt time for one tile. */
internal data class TileReceipt(
    val checkedAt: Long,
    val etag: String?,
    val lastModified: String?,
)

/**
 * Persisted validator metadata for cached OSM tiles.
 *
 * This is deliberately separate from [CacheStore]'s access-recency index: freshness describes the
 * last successful 200/304, while access recency describes least-recently-used eviction.
 */
internal class TileReceipts(private val file: File) {
    private val receipts = mutableMapOf<String, TileReceipt>()

    init {
        runCatching {
            if (file.isFile) {
                file.forEachLine { line ->
                    val fields = line.split('\t')
                    if (fields.size != 4) return@forEachLine
                    val key = fields[0]
                    val checkedAt = fields[1].toLongOrNull() ?: return@forEachLine
                    if (!validKey(key)) return@forEachLine
                    receipts[key] = TileReceipt(checkedAt, decode(fields[2]), decode(fields[3]))
                }
            }
        }
    }

    @Synchronized fun get(key: String): TileReceipt? = receipts[key]

    @Synchronized
    fun put(key: String, receipt: TileReceipt) {
        if (!validKey(key)) return
        receipts[key] = receipt
        store()
    }

    @Synchronized
    fun forget(keys: Collection<String>) {
        var changed = false
        keys.forEach { changed = receipts.remove(it) != null || changed }
        if (changed) store()
    }

    @Synchronized
    fun pruneMissing(folder: File) {
        val missing = receipts.keys.filterNot { File(folder, "$it.png").isFile }
        if (missing.isNotEmpty()) {
            missing.forEach(receipts::remove)
            store()
        }
    }

    private fun store() {
        runCatching {
            if (receipts.isEmpty()) {
                file.delete()
                return
            }
            val text = buildString {
                receipts.forEach { (key, receipt) ->
                    append(key)
                        .append('\t')
                        .append(receipt.checkedAt)
                        .append('\t')
                        .append(encode(receipt.etag))
                        .append('\t')
                        .append(encode(receipt.lastModified))
                        .append('\n')
                }
            }
            AtomicFiles.write(file, text.toByteArray())
        }
    }

    private fun validKey(key: String) =
        key.isNotEmpty() && '\t' !in key && '\n' !in key && '\r' !in key

    private fun encode(value: String?): String =
        value
            ?.replace("\\", "\\\\")
            ?.replace("\t", "\\t")
            ?.replace("\n", "\\n")
            ?.replace("\r", "\\r") ?: ""

    private fun decode(value: String): String? {
        if (value.isEmpty()) return null
        val out = StringBuilder(value.length)
        var escaped = false
        value.forEach { c ->
            if (escaped) {
                out.append(
                    when (c) {
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> c
                    }
                )
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else {
                out.append(c)
            }
        }
        if (escaped) out.append('\\')
        return out.toString()
    }
}

/**
 * Loads one cached tile, revalidating it conditionally once the seven-day receipt window ends. [T]
 * is the decoded image type so the policy is unit-testable without Android bitmaps.
 */
internal class TileFetcher<T>(
    private val folder: File,
    private val receipts: TileReceipts,
    private val decode: (ByteArray) -> T?,
    private val decodeFile: (File) -> T?,
    private val fetch: (address: String, etag: String?, lastModified: String?) -> HttpReply,
    private val clock: () -> Long,
    private val write: (File, ByteArray) -> Unit = AtomicFiles::write,
    private val onRead: (File) -> Unit = {},
    private val onWrite: (File) -> Unit = {},
) {
    init {
        receipts.pruneMissing(folder)
    }

    fun load(key: String, address: String, online: Boolean = true): T? {
        val file = File(folder, "$key.png")
        val image = file.takeIf { it.isFile }?.let { runCatching { decodeFile(it) }.getOrNull() }
        if (image != null) onRead(file)
        if (!online) return image

        val now = clock()
        val stored = receipts.get(key)
        val checkedAt = stored?.checkedAt ?: file.lastModified()
        if (image != null && now - checkedAt <= CachePolicy.TILE_FRESH_MS) return image

        var etag = stored?.etag
        var modified = stored?.lastModified
        if (image == null) {
            // No usable cached representation: a conditional request could only produce a 304
            // that we cannot display, so ask for the bytes unconditionally.
            etag = null
            modified = null
        }
        var unconditional = etag == null && modified == null
        repeat(2) {
            try {
                val reply =
                    fetch(
                        address,
                        if (unconditional) null else etag,
                        if (unconditional) null else modified,
                    )
                when (reply) {
                    is HttpReply.NotModified -> {
                        if (image == null) {
                            // The cache disappeared or no longer decodes after all; recover with
                            // an unconditional download instead of publishing an empty tile.
                            etag = null
                            modified = null
                            unconditional = true
                            return@repeat
                        }
                        receipts.put(
                            key,
                            TileReceipt(
                                checkedAt = clock(),
                                etag = reply.etag ?: etag,
                                lastModified = reply.lastModified ?: modified,
                            ),
                        )
                        return image
                    }
                    is HttpReply.Ok -> {
                        val downloaded = decode(reply.bytes) ?: error("Invalid map tile")
                        write(file, reply.bytes)
                        receipts.put(
                            key,
                            TileReceipt(
                                checkedAt = clock(),
                                etag = reply.etag,
                                lastModified = reply.lastModified,
                            ),
                        )
                        onWrite(file)
                        receipts.pruneMissing(folder)
                        return downloaded
                    }
                }
            } catch (_: Exception) {
                // Network, decode or cache-write failure: keep the previous image and receipt.
                return image
            }
        }
        return image
    }
}
