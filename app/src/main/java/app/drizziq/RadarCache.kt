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

internal data class RadarCacheRead(val sequence: RadarSequence?, val invalid: List<File>)

/** Versioned radar manifest and frame validation shared by the cache writer and loader. */
internal object RadarCache {
    const val VERSION = 1
    const val PALETTE_VERSION = 1
    private const val MANIFEST = "radar.json"

    fun encode(sequence: RadarSequence, historyComplete: Boolean): String {
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
            .put("frames", frames)
            .toString()
    }

    /**
     * Returns the sequence only when version, palette, frame structure and every PNG are valid. A
     * null sequence names files that should be removed before the next refresh regenerates.
     */
    fun read(directory: File): RadarCacheRead {
        val manifest = File(directory, MANIFEST)
        if (!manifest.isFile) return RadarCacheRead(null, emptyList())
        val root =
            runCatching { JSONObject(manifest.readText()) }.getOrNull()
                ?: return RadarCacheRead(null, emptyList())
        val referenced = runCatching {
            frameNames(root)
        }
            .getOrNull()
            .orEmpty()
            .map { File(directory, it) }
        if (root.optInt("version", -1) != VERSION || root.optInt("palette", -1) != PALETTE_VERSION)
            return RadarCacheRead(null, referenced)
        val invalid = mutableListOf<File>()
        val sequence = runCatching {
            val frames = root.getJSONArray("frames")
            require(frames.length() > 0)
            val parsed =
                (0 until frames.length()).map { index ->
                    val frame = frames.getJSONObject(index)
                    val name = frame.getString("file")
                    require(isSafeName(name))
                    val path = File(directory, name)
                    val grid =
                        RadarGrid(
                            frame.getInt("w"),
                            frame.getInt("h"),
                            frame.getDouble("left"),
                            frame.getDouble("top"),
                            frame.getDouble("dx"),
                            frame.getDouble("dy"),
                        )
                    val size = Png.dimensions(path)
                    if (size != grid.width to grid.height) {
                        invalid += path
                        error("Invalid radar frame")
                    }
                    RadarFrame(
                        frame.getLong("time"),
                        frame.getLong("issue"),
                        frame.getInt("lead"),
                        grid,
                        path.path,
                    )
                }
            RadarSequence(
                parsed,
                root.getLong("issue"),
                root.getBoolean("historyComplete"),
            )
        }
            .getOrNull()
        return RadarCacheRead(sequence, invalid)
    }

    fun reusable(file: File, grid: RadarGrid): Boolean =
        Png.dimensions(file)?.let { it == grid.width to grid.height } == true

    private fun frameNames(root: JSONObject): List<String> {
        val frames = root.getJSONArray("frames")
        return (0 until frames.length()).map { index ->
            val name = frames.getJSONObject(index).getString("file")
            require(isSafeName(name))
            name
        }
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith(".") && File(name).name == name && '\u0000' !in name
}
