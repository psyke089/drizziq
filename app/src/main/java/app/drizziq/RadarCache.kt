package app.drizziq

import java.io.File
import java.util.zip.CRC32
import org.json.JSONArray
import org.json.JSONObject

/** Structural PNG validation without Android bitmap decoding. */
internal object Png {
    private const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_DIMENSION = 16_384
    private const val MAX_PIXELS = 50_000_000L
    private val signature =
        byteArrayOf(
            0x89.toByte(),
            'P'.code.toByte(),
            'N'.code.toByte(),
            'G'.code.toByte(),
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )

    fun dimensions(file: File): Pair<Int, Int>? {
        if (!file.isFile || file.length() !in 33..MAX_BYTES.toLong()) return null
        return runCatching { dimensions(file.readBytes()) }.getOrNull()
    }

    fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size !in 33..MAX_BYTES) return null
        if (!bytes.copyOfRange(0, signature.size).contentEquals(signature)) return null
        var position = signature.size
        var width = -1
        var height = -1
        var sawHeader = false
        var sawPixels = false
        val crc = CRC32()
        while (position + 12 <= bytes.size) {
            val length = readInt(bytes, position)
            if (length < 0 || length > bytes.size - position - 12) return null
            val typeAt = position + 4
            val dataAt = position + 8
            val crcAt = dataAt + length
            val type =
                runCatching { String(bytes, typeAt, 4, Charsets.US_ASCII) }.getOrNull()
                    ?: return null
            if (!type.all { it in 'A'..'Z' || it in 'a'..'z' }) return null
            crc.reset()
            crc.update(bytes, typeAt, 4 + length)
            if (crc.value.toInt() != readInt(bytes, crcAt)) return null
            when (type) {
                "IHDR" -> {
                    if (sawHeader || position != signature.size || length != 13) return null
                    sawHeader = true
                    width = readInt(bytes, dataAt)
                    height = readInt(bytes, dataAt + 4)
                    if (
                        width <= 0 ||
                            height <= 0 ||
                            width > MAX_DIMENSION ||
                            height > MAX_DIMENSION ||
                            width.toLong() * height > MAX_PIXELS
                    )
                        return null
                }
                "IDAT" -> {
                    if (!sawHeader) return null
                    sawPixels = true
                }
                "IEND" -> {
                    if (length != 0 || !sawHeader || !sawPixels) return null
                    return width to height
                }
            }
            position = crcAt + 4
        }
        return null
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xff) shl 24) or
            ((bytes[at + 1].toInt() and 0xff) shl 16) or
            ((bytes[at + 2].toInt() and 0xff) shl 8) or
            (bytes[at + 3].toInt() and 0xff)
}

internal data class RadarCacheRead(
    val sequence: RadarSequence?,
    val invalid: List<File>,
    val downloadedAt: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val latestComplete: Boolean = true,
) {
    fun removeInvalid() {
        val retained = sequence?.frames.orEmpty().map { File(it.file) }.toSet()
        invalid.filterNot { it in retained }.forEach { it.delete() }
    }

    val snapshot: RadarSnapshot?
        get() = sequence?.let {
            RadarSnapshot(it, downloadedAt, etag, lastModified, latestComplete)
        }
}

/** Versioned radar manifest and frame validation shared by the cache writer and loader. */
internal object RadarCache {
    const val VERSION = 1
    const val PALETTE_VERSION = 1
    private const val MANIFEST = "radar.json"

    fun encode(
        sequence: RadarSequence,
        historyComplete: Boolean,
        downloadedAt: Long? = null,
        etag: String? = null,
        lastModified: String? = null,
    ): String {
        val frames = JSONArray()
        sequence.frames.forEach { frame ->
            frames.put(
                JSONObject()
                    .put("time", frame.time)
                    .put("issue", frame.issue)
                    .put("lead", frame.lead)
                    .put("file", File(frame.file).name)
                    .put("w", frame.grid.width)
                    .put("h", frame.grid.height)
                    .put("left", frame.grid.left)
                    .put("top", frame.grid.top)
                    .put("dx", frame.grid.dx)
                    .put("dy", frame.grid.dy)
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("palette", PALETTE_VERSION)
            .put("issue", sequence.issue)
            .put("historyComplete", historyComplete)
            .put("downloadedAt", downloadedAt)
            .put("etag", etag)
            .put("lastModified", lastModified)
            .put("frames", frames)
            .toString()
    }

    /** Keep valid frames for display/reuse; incomplete latest images cannot satisfy HTTP 304. */
    fun read(directory: File): RadarCacheRead {
        val manifest = File(directory, MANIFEST)
        if (!manifest.isFile || manifest.length() > 262_144L)
            return RadarCacheRead(null, emptyList())
        val root =
            runCatching { JSONObject(manifest.readText()) }.getOrNull()
                ?: return RadarCacheRead(null, emptyList())
        val referenced = runCatching {
            frameNames(root)
        }
            .getOrDefault(emptyList())
            .map { File(directory, it) }
        if (root.optInt("version", -1) != VERSION || root.optInt("palette", -1) != PALETTE_VERSION)
            return RadarCacheRead(null, referenced)
        val invalid = mutableListOf<File>()
        var lostLatest = false
        val sequence = runCatching {
            val issue = root.getLong("issue")
            require(issue >= 0 && issue <= Long.MAX_VALUE - 7_200_000 && issue % 300_000L == 0L)
            val entries = root.getJSONArray("frames")
            require(entries.length() in 1..37)
            val parsed =
                (0 until entries.length())
                    .mapNotNull { index ->
                        val entry = entries.optJSONObject(index)
                        val name = entry?.optString("file").orEmpty()
                        val path = File(directory, name)
                        val frame = runCatching {
                            require(isSafeName(name))
                            val value = requireNotNull(entry)
                            val time = value.getLong("time")
                            val origin = value.getLong("issue")
                            val lead = value.getInt("lead")
                            require(lead in 0..120 && lead % 5 == 0)
                            require(origin >= 0 && origin <= Long.MAX_VALUE - 7_200_000)
                            require(time == origin + lead * 60_000L)
                            require(
                                (origin == issue) ||
                                    (lead == 0 &&
                                        origin in (issue - 3_600_000L) until issue &&
                                        (issue - origin) % 300_000L == 0L)
                            )
                            val grid =
                                RadarGrid(
                                    value.getInt("w"),
                                    value.getInt("h"),
                                    value.getDouble("left"),
                                    value.getDouble("top"),
                                    value.getDouble("dx"),
                                    value.getDouble("dy"),
                                )
                            require(grid.left.isFinite() && grid.top.isFinite())
                            require(
                                grid.dx.isFinite() &&
                                    grid.dx > 0 &&
                                    grid.dy.isFinite() &&
                                    grid.dy > 0
                            )
                            require((grid.left + grid.width * grid.dx).isFinite())
                            require((grid.top - grid.height * grid.dy).isFinite())
                            require(Png.dimensions(path) == grid.width to grid.height)
                            RadarFrame(time, origin, lead, grid, path.path)
                        }
                            .getOrNull()
                        if (frame == null) {
                            if (isSafeName(name)) invalid += path
                            // Do not send validators when part of the saved representation is
                            // missing.
                            lostLatest = true
                        }
                        frame
                    }
                    .sortedBy { it.time }
            require(parsed.isNotEmpty())
            require(parsed.map { it.issue to it.lead }.distinct().size == parsed.size)
            val completeHistory =
                (1..12).all { back ->
                    parsed.any { it.lead == 0 && it.issue == issue - back * 300_000L }
                }
            RadarSequence(
                parsed,
                issue,
                root.getBoolean("historyComplete") && completeHistory && invalid.isEmpty(),
            )
        }
            .getOrNull()
        val latestComplete =
            !lostLatest &&
                sequence != null &&
                sequence.frames.any { it.issue == sequence.issue && it.lead == 0 } &&
                sequence.frames.any { it.issue == sequence.issue && it.lead > 0 }
        return RadarCacheRead(
            sequence,
            invalid,
            root.optLong("downloadedAt", manifest.lastModified()),
            root.optString("etag").takeIf { it.isNotEmpty() && it != "null" },
            root.optString("lastModified").takeIf { it.isNotEmpty() && it != "null" },
            latestComplete,
        )
    }

    fun save(directory: File, snapshot: RadarSnapshot) {
        AtomicFiles.write(
            File(directory, MANIFEST),
            encode(
                    snapshot.sequence,
                    snapshot.sequence.historyComplete,
                    snapshot.downloadedAt,
                    snapshot.etag,
                    snapshot.lastModified,
                )
                .toByteArray(),
        )
    }

    fun clear(directory: File) {
        directory
            .listFiles()
            ?.filter {
                it.name == MANIFEST ||
                    (it.name.startsWith("$MANIFEST.") && it.name.endsWith(".part")) ||
                    (it.name.startsWith("rv-") &&
                        (it.name.endsWith(".png") || it.name.endsWith(".part")))
            }
            ?.forEach { check(it.delete()) { "Radar cache could not be cleared" } }
    }

    private fun frameNames(root: JSONObject): List<String> {
        val frames = root.getJSONArray("frames")
        return (0 until frames.length()).map { index ->
            val name = frames.getJSONObject(index).getString("file")
            require(isSafeName(name))
            name
        }
    }

    private fun isSafeName(name: String): Boolean =
        Regex("rv-[0-9]{1,19}-[0-9]{1,3}\\.png").matches(name)
}
