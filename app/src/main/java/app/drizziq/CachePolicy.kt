package app.drizziq

import java.io.File
import java.io.FileOutputStream
import java.util.LinkedHashMap

/** Same-directory unique temporary writes, atomic replacement and abandoned-temp cleanup. */
internal object AtomicFiles {
    fun write(file: File, bytes: ByteArray) = write(file) { it.writeBytes(bytes) }

    fun write(file: File, fill: (File) -> Unit) {
        val directory = file.absoluteFile.parentFile ?: error("Cache target has no directory")
        directory.mkdirs()
        val temp = File.createTempFile("${file.name}.", ".part", directory)
        try {
            fill(temp)
            FileOutputStream(temp, true).use { it.fd.sync() }
            check(temp.renameTo(file)) { "Cache write failed" }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun cleanup(directory: File, now: Long, maxAge: Long = CachePolicy.ABANDONED_TEMP_MS) {
        directory
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".part") }
            ?.filter { file ->
                val modified = file.lastModified()
                modified > 0 && now - modified > maxAge
            }
            ?.forEach { it.delete() }
    }
}

/** Shared disk-cache budgets and least-recently-used eviction. */
internal object CachePolicy {
    const val BASEMAP_BYTES = 64L * 1024 * 1024
    const val FORECAST_BYTES = 32L * 1024 * 1024
    const val FAILED_TILE_KEYS = 256
    const val ABANDONED_TEMP_MS = 24L * 60 * 60 * 1000
    const val HISTORY_MS = 3L * 24 * 60 * 60 * 1000
    const val TILE_FRESH_MS = 7L * 24 * 60 * 60 * 1000

    data class Entry(val name: String, val bytes: Long, val accessed: Long)

    fun evictions(
        entries: Collection<CachePolicy.Entry>,
        budget: Long,
        protected: Set<String> = emptySet(),
    ): List<String> {
        var total = entries.sumOf { maxOf(0, it.bytes) }
        if (total <= budget) return emptyList()
        val victims =
            entries
                .asSequence()
                .filter { it.name !in protected }
                .sortedWith(compareBy<CachePolicy.Entry> { it.accessed }.thenBy { it.name })
        val evicted = mutableListOf<String>()
        for (victim in victims) {
            if (total <= budget) break
            evicted += victim.name
            total -= maxOf(0, victim.bytes)
        }
        return evicted
    }
}

/** Fixed-capacity access-ordered map; reading a key makes it the newest entry. */
internal class BoundedAccessMap<K, V>(private val capacity: Int) {
    private val entries = LinkedHashMap<K, V>(16, .75f, true)

    init {
        require(capacity > 0)
    }

    @Synchronized operator fun get(key: K): V? = entries[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        entries[key] = value
        while (entries.size > capacity) {
            val iterator = entries.keys.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    val size: Int
        @Synchronized get() = entries.size
}

/** Persisted last-access times, kept separate from source freshness timestamps. */
internal class AccessIndex(private val file: File) {
    private val times = mutableMapOf<String, Long>()

    init {
        runCatching {
            file.forEachLine { line ->
                val separator = line.indexOf('\t')
                if (separator <= 0) return@forEachLine
                val time = line.substring(0, separator).toLongOrNull() ?: return@forEachLine
                val name = line.substring(separator + 1)
                if (name.isNotEmpty() && '\t' !in name) times[name] = time
            }
        }
    }

    @Synchronized fun last(name: String): Long? = times[name]

    @Synchronized
    fun touch(name: String, now: Long) {
        if (name.isEmpty() || '\t' in name || '\n' in name) return
        times[name] = now
        store()
    }

    @Synchronized
    fun forget(names: Collection<String>) {
        var changed = false
        names.forEach { changed = times.remove(it) != null || changed }
        if (changed) store()
    }

    private fun store() {
        runCatching {
            if (times.isEmpty()) {
                file.delete()
                return
            }
            val text = buildString {
                times.forEach { (name, time) ->
                    append(time).append('\t').append(name).append('\n')
                }
            }
            AtomicFiles.write(file, text.toByteArray())
        }
    }
}

/**
 * Directory cache with explicit access recency, size pruning and optional age expiry.
 *
 * Reads never alter file timestamps, so receipt/freshness timestamps remain meaningful.
 */
internal class CacheStore(
    private val directory: File,
    private val budget: Long,
    private val eligible: (String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    indexName: String = "cache-access.tsv",
) {
    private val index = AccessIndex(File(directory, indexName))

    init {
        directory.mkdirs()
    }

    @Synchronized
    fun read(file: File) {
        if (!file.isFile) return
        index.touch(file.name, clock())
    }

    @Synchronized
    fun wrote(file: File, expire: (File, Long) -> Boolean = { _, _ -> false }) {
        index.touch(file.name, clock())
        prune(expire)
    }

    @Synchronized
    fun prune(expire: (File, Long) -> Boolean = { _, _ -> false }) {
        val now = clock()
        AtomicFiles.cleanup(directory, now)
        val files = directory.listFiles()?.filter { it.isFile && eligible(it.name) } ?: return
        val expired = files.filter { expire(it, now) }
        if (expired.isNotEmpty()) {
            expired.forEach { it.delete() }
            index.forget(expired.map { it.name })
        }
        val remaining = files.filter { it.exists() }
        val entries = remaining.map {
            CachePolicy.Entry(
                it.name,
                it.length(),
                index.last(it.name) ?: it.lastModified(),
            )
        }
        val evicted = CachePolicy.evictions(entries, budget)
        if (evicted.isNotEmpty()) {
            evicted.forEach { File(directory, it).delete() }
            index.forget(evicted)
        }
    }
}
