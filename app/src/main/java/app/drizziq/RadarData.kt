package app.drizziq

import io.jhdf.HdfFile
import io.jhdf.api.Node
import java.io.InputStream
import java.lang.reflect.Array as JArray
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.*

/** The projection declared in the current DWD DE1200 ODIM data, in metres. */
object RadarProjection {
    private const val A = 6378137.0
    private const val B = 6356752.3142451802
    private const val LAT_TS = 60.0
    private const val LAT_0 = 90.0
    private const val LON_0 = 10.0
    private const val ORIGIN_X = 543196.83521776402
    private const val ORIGIN_Y = 3622588.8619310022
    private val E = sqrt(1 - (B / A).pow(2))
    private val phiC = Math.toRadians(LAT_TS)
    private val supported =
        setOf("proj", "lat_ts", "lat_0", "lon_0", "x_0", "y_0", "units", "a", "b", "no_defs")

    private fun t(phi: Double) =
        tan(Math.PI / 4 - phi / 2) / ((1 - E * sin(phi)) / (1 + E * sin(phi))).pow(E / 2)

    private val scale = A * cos(phiC) / sqrt(1 - E * E * sin(phiC).pow(2)) / t(phiC)

    fun validate(projdef: String) {
        require(projdef.isNotBlank()) { "Unsupported radar projection: missing +proj" }
        val params = LinkedHashMap<String, String?>()
        for (token in projdef.trim().split(Regex("\\s+"))) {
            require(token.length > 1 && token.startsWith('+')) {
                "Unsupported radar projection: malformed parameter '$token'"
            }
            val body = token.substring(1)
            val equals = body.indexOf('=')
            val key = if (equals < 0) body else body.substring(0, equals)
            val value = if (equals < 0) null else body.substring(equals + 1)
            require(key.isNotEmpty()) {
                "Unsupported radar projection: malformed parameter '$token'"
            }
            require(!params.containsKey(key)) {
                "Unsupported radar projection: duplicate +$key"
            }
            params[key] = value
        }
        for ((key, value) in params) {
            require(key in supported) { "Unsupported radar projection: unsupported +$key" }
            if (key == "no_defs") {
                require(value == null) {
                    "Unsupported radar projection: +no_defs must not have a value"
                }
            }
        }
        for (key in supported) {
            if (key != "no_defs") {
                require(params.containsKey(key)) { "Unsupported radar projection: missing +$key" }
            }
        }
        require(params["proj"] == "stere") {
            "Unsupported radar projection: +proj=${params["proj"]} (expected stere)"
        }
        require(params["units"] == "m") {
            "Unsupported radar projection: +units=${params["units"]} (expected m)"
        }
        requireNumber(params, "lat_ts", LAT_TS)
        requireNumber(params, "lat_0", LAT_0)
        requireNumber(params, "lon_0", LON_0)
        requireNumber(params, "x_0", ORIGIN_X)
        requireNumber(params, "y_0", ORIGIN_Y)
        requireNumber(params, "a", A)
        requireNumber(params, "b", B)
    }

    private fun requireNumber(params: Map<String, String?>, key: String, expected: Double) {
        val raw = params.getValue(key)
        val value = raw?.toDoubleOrNull()
        require(value != null && value.isFinite() && abs(value - expected) <= 1e-6) {
            "Unsupported radar projection: +$key=$raw (expected $expected)"
        }
    }

    fun project(lat: Double, lon: Double): Pair<Double, Double> {
        val r = scale * t(Math.toRadians(lat))
        val lambda = Math.toRadians(lon - LON_0)
        return (r * sin(lambda) + ORIGIN_X) to (-r * cos(lambda) + ORIGIN_Y)
    }

    fun unproject(x: Double, y: Double): Pair<Double, Double> {
        val px = x - ORIGIN_X
        val py = y - ORIGIN_Y
        val tt = hypot(px, py) / scale
        var phi = Math.PI / 2 - 2 * atan(tt)
        repeat(10) {
            phi = Math.PI / 2 - 2 * atan(tt * ((1 - E * sin(phi)) / (1 + E * sin(phi))).pow(E / 2))
        }
        return Math.toDegrees(phi) to (LON_0 + Math.toDegrees(atan2(px, -py)))
    }
}

data class RadarGrid(
    val width: Int,
    val height: Int,
    val left: Double,
    val top: Double,
    val dx: Double,
    val dy: Double,
) {
    fun contains(place: Place): Boolean {
        val (x, y) = RadarProjection.project(place.lat, place.lon)
        return x >= left && x < left + width * dx && y <= top && y > top - height * dy
    }
}

data class RadarFrame(
    val time: Long,
    val issue: Long,
    val lead: Int,
    val grid: RadarGrid,
    val file: String,
)

data class RadarSequence(
    val frames: List<RadarFrame>,
    val issue: Long,
    val historyComplete: Boolean,
) {
    fun position(time: Long): Float =
        ((time - (issue - 3_600_000L)).toFloat() / 10_800_000L).coerceIn(0f, 1f)
}

/** Next timeline position, wrapping from the last frame back to the first. */
internal fun nextRadarFrame(current: Int, size: Int): Int =
    when {
        size <= 0 -> 0
        current !in 0 until size - 1 -> 0
        else -> current + 1
    }

data class RadarPixels(val time: Long, val grid: RadarGrid, val colors: IntArray)

object RadarPalette {
    val limits = doubleArrayOf(0.0, 0.1, 0.5, 1.0, 2.0, 5.0)
    val colors =
        intArrayOf(
            0,
            0xB83EBCBA.toInt(),
            0xCE48ABEC.toInt(),
            0xDD8B8AF1.toInt(),
            0xEAE9B76B.toInt(),
            0xF4F57083.toInt(),
        )

    fun color(mm: Double): Int =
        when {
            mm <= 0 -> 0
            mm < .1 -> 0xA55B898E.toInt()
            else -> colors[limits.indexOfLast { mm >= it }]
        }

    // Missing pixels are visibly hatched, never confused with dry/transparent pixels.
    fun missing(x: Int, y: Int) = if ((x + y) % 12 < 3) 0x806F7E95.toInt() else 0x183C4A60
}

object Odim {
    private fun scalar(value: Any?): Any? =
        if (value != null && value.javaClass.isArray) {
            if (value is ByteArray) value.toString(Charsets.UTF_8).trimEnd('\u0000')
            else if (JArray.getLength(value) > 0) scalar(JArray.get(value, 0)) else null
        } else value

    private fun Node.string(key: String) =
        scalar(getAttribute(key)?.data)?.toString() ?: error("DWD attribute $key missing")

    private fun Node.number(key: String) =
        (scalar(getAttribute(key)?.data) as? Number)?.toDouble()
            ?: error("DWD attribute $key missing")

    fun decode(bytes: ByteArray): RadarPixels =
        HdfFile.fromBytes(bytes).use { hdf ->
            val where = hdf.getByPath("/where")
            val what = hdf.getByPath("/dataset1/data1/what")
            val interval = hdf.getByPath("/dataset1/what")
            require(what.string("quantity") == "ACRR") { "Unexpected radar unit" }
            RadarProjection.validate(where.string("projdef"))
            val w = where.number("xsize").toInt()
            val h = where.number("ysize").toInt()
            require(w in 1..2000 && h in 1..2000) { "Unexpected radar dimensions" }
            val (left, top) =
                RadarProjection.project(where.number("UL_lat"), where.number("UL_lon"))
            val grid = RadarGrid(w, h, left, top, where.number("xscale"), where.number("yscale"))
            val date = interval.string("enddate") + interval.string("endtime")
            val time =
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                    .apply {
                        timeZone = Times.utc
                        isLenient = false
                    }
                    .parse(date)!!
                    .time
            val start =
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                    .apply {
                        timeZone = Times.utc
                        isLenient = false
                    }
                    .parse(interval.string("startdate") + interval.string("starttime"))!!
                    .time
            require(time - start == 300000L) { "Radar interval is not five minutes" }
            val gain = what.number("gain")
            val offset = what.number("offset")
            val missing = what.number("nodata")
            val dry = what.number("undetect")
            val dataset = hdf.getDatasetByPath("/dataset1/data1/data")
            require(dataset.dimensions.contentEquals(intArrayOf(h, w))) {
                "Radar dimension mismatch"
            }
            val data = dataset.data
            val colors = IntArray(w * h)
            for (y in 0 until h) {
                val row = JArray.get(data, y)
                for (x in 0 until w) {
                    val value = (JArray.get(row, x) as Number).toDouble()
                    // jHDF returns unsigned uint32 as long; accept its signed representation too.
                    val raw =
                        if (value < 0 && missing > Int.MAX_VALUE) value + 4294967296.0 else value
                    colors[y * w + x] =
                        when (raw) {
                            missing -> RadarPalette.missing(x, y)
                            dry -> 0
                            else -> RadarPalette.color(raw * gain + offset)
                        }
                }
            }
            RadarPixels(time, grid, colors)
        }
}

/** Small bounded reader for the uncompressed tar container used by DWD. */
object Tar {
    data class Entry(val name: String, val bytes: ByteArray)

    fun first(input: InputStream): Entry {
        val data = java.io.DataInputStream(input)
        val header = ByteArray(512)
        data.readFully(header)
        val name = header.copyOfRange(0, 100).toString(Charsets.US_ASCII).trimEnd('\u0000')
        require(header[156].toInt() in listOf(0, 48) && name.endsWith("_000-hd5")) {
            "First radar member is not an observation"
        }
        return Entry(name, ByteArray(size(header)).also { data.readFully(it) })
    }

    fun size(header: ByteArray): Int =
        header.copyOfRange(124, 136).toString(Charsets.US_ASCII).trim('\u0000', ' ').toInt(8).also {
            require(it in 0..24000000)
        }

    fun read(input: InputStream, consume: (Entry) -> Unit) {
        var count = 0
        while (true) {
            val header = ByteArray(512)
            var read = 0
            while (read < 512) {
                val n = input.read(header, read, 512 - read)
                if (n < 0) {
                    require(read == 0)
                    return
                }
                read += n
            }
            if (header.all { it == 0.toByte() }) return
            require(++count <= 100) { "Too many radar archive entries" }
            val size = size(header)
            val name = header.copyOfRange(0, 100).toString(Charsets.US_ASCII).trimEnd('\u0000')
            val body = ByteArray(size)
            var n = 0
            while (n < size) {
                val r = input.read(body, n, size - n)
                require(r > 0) { "Truncated radar frame" }
                n += r
            }
            var padding = (512 - size % 512) % 512
            while (padding > 0) {
                require(input.read() >= 0)
                padding--
            }
            if (header[156].toInt() in listOf(0, 48)) consume(Entry(name, body))
        }
    }
}
