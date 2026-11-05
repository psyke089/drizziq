package app.drizziq

import java.io.FilterInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.*
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import kotlin.math.*
import org.xml.sax.Attributes
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.XMLReader
import org.xml.sax.helpers.DefaultHandler

data class Place(
    val label: String,
    val lat: Double,
    val lon: Double,
    val zone: String = "Europe/Berlin",
    val geocodeId: String? = null,
    val geocodeLabelEdited: Boolean = false,
) {
    init {
        require(validCoordinates(lat, lon))
    }

    fun relabel(value: String) =
        copy(
            label = value,
            geocodeLabelEdited = geocodeId != null && (geocodeLabelEdited || value != label),
        )

    fun withCoordinates(latitude: Double, longitude: Double, timezone: String) =
        copy(
            lat = latitude,
            lon = longitude,
            zone = timezone,
            geocodeId = geocodeId.takeIf { latitude == lat && longitude == lon },
            geocodeLabelEdited = geocodeLabelEdited && latitude == lat && longitude == lon,
        )

    companion object {
        val MUNICH = Place("Munich", 48.138108, 11.574726)
    }
}

fun validCoordinates(lat: Double, lon: Double) =
    lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

data class Station(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevation: Double?,
) {
    fun distance(place: Place): Double {
        val a =
            sin(Math.toRadians(place.lat - lat) / 2).pow(2) +
                cos(Math.toRadians(lat)) *
                    cos(Math.toRadians(place.lat)) *
                    sin(Math.toRadians(place.lon - lon) / 2).pow(2)
        return 6371.0088 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

enum class Freshness {
    LOADING,
    LIVE,
    CACHED,
    STALE,
    ERROR,
}

data class Reading<T>(
    val data: T? = null,
    val state: Freshness = Freshness.LOADING,
    val fetched: Long? = null,
    val message: String? = null,
    val refreshing: Boolean = false,
)

/** Entry/storage/formatting boundary shared by search, places and forecast loading. */
object Zones {
    /** Returns the canonical java.time ID, or null for legacy aliases such as PST. */
    fun canonical(id: String?): String? {
        if (id == null) return null
        return try {
            ZoneId.of(id).id
        } catch (error: Exception) {
            null
        }
    }

    fun valid(id: String?) = canonical(id) != null

    fun get(id: String): ZoneId = ZoneId.of(id)
}

object Times {
    val utc: TimeZone = TimeZone.getTimeZone("UTC")

    fun parse(text: String): Long =
        SimpleDateFormat(
                if (text.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
                else "yyyy-MM-dd'T'HH:mm:ss'Z'",
                Locale.US,
            )
            .apply {
                timeZone = utc
                isLenient = false
            }
            .parse(text)!!
            .time

    fun format(time: Long, pattern: String = "HH:mm", zone: String = "UTC"): String =
        SimpleDateFormat(pattern, Locale.ENGLISH)
            .apply { timeZone = TimeZone.getTimeZone(Zones.get(zone)) }
            .format(Date(time))

    fun day(time: Long, zone: String) = format(time, "yyyy-MM-dd", zone)
}

data class Hour(val time: Long, val values: Map<String, Double>) {
    fun get(name: String) = values[name]

    val temperature
        get() = get("TTT")?.minus(273.15)

    val wind
        get() = get("FF")?.times(3.6)

    val pressure: Double?
        get() = get("PPPP")?.takeIf { it.isFinite() && it > 0 }?.div(100.0)

    val precipitationProbability: Double?
        get() = get("R101")?.takeIf { it.isFinite() && it in 0.0..100.0 }

    val gust: Double?
        get() =
            get("FX1")?.takeIf { it.isFinite() && it >= 0 }?.times(3.6)?.takeIf { it.isFinite() }

    val condition
        get() = get("ww")?.toInt()

    // Relative humidity is intentionally not derived from temperature/dewpoint.
    val humidity
        get() = get("RF") ?: get("RELH")
}

data class Day(val time: Long, val hours: List<Hour>, val complete: Boolean) {
    val high
        get() = hours.mapNotNull { it.temperature }.maxOrNull()

    val low
        get() = hours.mapNotNull { it.temperature }.minOrNull()

    val wind
        get() = hours.mapNotNull { it.wind }.maxOrNull()

    // Missing intervals never turn into a partial total advertised as a daily total.
    fun total(field: String): Double? =
        if (complete && hours.isNotEmpty() && hours.all { it.get(field) != null })
            hours.sumOf { it.get(field)!! }
        else null

    val rain
        get() = total("RR1c")

    val sun
        get() = total("SunD1")?.div(3600)

    val condition
        get() =
            hours
                .filter { it.condition != null }
                .maxByOrNull { weatherSeverity(it.condition!!) }
                ?.condition
}

data class Forecast(
    val station: Station,
    val issue: Long,
    val hours: List<Hour>,
    val earlier: List<Hour> = emptyList(),
) {
    fun withEarlierHours(other: Forecast): Forecast {
        if (other.station.id != station.id || other.issue > issue) return this
        val first = hours.minOfOrNull { it.time } ?: return this
        return copy(
            earlier =
                (other.earlier + other.hours + earlier)
                    .associateBy { it.time }
                    .values
                    .filter { it.time < first }
                    .sortedBy { it.time }
        )
    }

    fun current(now: Long): Hour? =
        hours.minByOrNull { abs(it.time - now) }?.takeIf { abs(it.time - now) <= 3600000 }

    fun days(now: Long, zone: String): List<Day> {
        val tz = Zones.get(zone)
        fun dateOf(time: Long) = Instant.ofEpochMilli(time - 1).atZone(tz).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(tz).toLocalDate()
        return (earlier + hours)
            .associateBy { it.time }
            .values
            .sortedBy { it.time }
            .filter { dateOf(it.time) >= today }
            .groupBy { dateOf(it.time) }
            .values
            .take(7)
            .map { h ->
                val date = dateOf(h.first().time)
                val start = date.atStartOfDay(tz).toInstant().toEpochMilli()
                val end = date.plusDays(1).atStartOfDay(tz).toInstant().toEpochMilli()
                val complete =
                    h.first().time == start + 3600000 &&
                        h.last().time == end &&
                        h.zipWithNext().all { (a, b) -> b.time - a.time == 3600000L }
                Day(start, h, complete)
            }
    }
}

fun weatherSeverity(code: Int): Int =
    when (code) {
        in 95..99 -> 9
        in 80..94 -> 8
        in 70..79 -> 7
        in 60..69 -> 6
        in 50..59 -> 5
        in 40..49 -> 4
        else -> code.coerceIn(0, 3)
    }

fun weatherLabel(code: Int?): String? =
    when (code) {
        null -> null
        0 -> "Clear sky"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        48,
        49 -> "Rime fog"
        56,
        57 -> "Freezing drizzle"
        66,
        67 -> "Freezing rain"
        68,
        69 -> "Rain and snow"
        77 -> "Snow grains"
        83,
        84 -> "Rain and snow showers"
        96,
        99 -> "Thunderstorm with hail"
        in 4..19 -> "Haze / visibility"
        in 20..29 -> "Recent precipitation"
        in 30..39 -> "Blowing snow / dust"
        in 40..49 -> "Fog"
        in 50..59 -> "Drizzle"
        in 60..69 -> "Rain"
        in 70..79 -> "Snow"
        in 80..84 -> "Rain showers"
        in 85..94 -> "Snow / mixed showers"
        in 95..99 -> "Thunderstorm"
        else -> "DWD weather code $code"
    }

object Mosmix {
    internal const val MAX_XML_BYTES = 1_048_576
    internal const val MAX_ARCHIVE_BYTES = 4_194_304
    internal const val MAX_ARCHIVE_ENTRIES = 16
    internal const val MAX_TEXT_CHARS = 65_536
    internal const val MAX_TIMESTEPS = 336
    internal const val MAX_VALUES_PER_FIELD = 336

    private val fields =
        setOf(
            "TTT",
            "TX",
            "TN",
            "ww",
            "FF",
            "DD",
            "RR1c",
            "SunD1",
            "RF",
            "RELH",
            "Td",
            "N",
            "R101",
            "FX1",
            "PPPP",
        )

    fun parseKmz(input: InputStream): Forecast =
        ZipInputStream(input).use { zip ->
            val budget = InputBudget(MAX_ARCHIVE_BYTES, "DWD archive")
            val buffer = ByteArray(8192)
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: error("DWD archive contains no KML")
                require(++entries <= MAX_ARCHIVE_ENTRIES) {
                    "DWD archive has more than $MAX_ARCHIVE_ENTRIES entries"
                }
                if (entry.name.endsWith(".kml", true)) {
                    return@use parseXml(CountedInputStream(zip, budget))
                }
                // Skipped entries still consume the decompressed archive budget.
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    budget.add(read)
                }
            }
            @Suppress("UNREACHABLE_CODE") error("No KML")
        }

    fun parseXml(input: InputStream): Forecast {
        val times = mutableListOf<Long>()
        val values = mutableMapOf<String, List<Double?>>()
        var issue: Long? = null
        var id = ""
        var label = ""
        var coords = ""
        var insidePlace = false
        var field: String? = null
        val text = StringBuilder()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newSAXParser().xmlReader
        parser.setFeatureIfSupported(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        parser.setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
        parser.setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
        parser.setFeatureIfSupported(
            "http://xml.org/sax/features/external-parameter-entities",
            false,
        )
        parser.contentHandler =
            object : DefaultHandler() {
                override fun startElement(
                    uri: String?,
                    local: String,
                    q: String?,
                    attrs: Attributes,
                ) {
                    text.setLength(0)
                    if (local == "Placemark") insidePlace = true
                    if (local == "Forecast")
                        field =
                            (0 until attrs.length)
                                .firstOrNull { attrs.getLocalName(it) == "elementName" }
                                ?.let { attrs.getValue(it) }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    text.append(ch, start, length)
                    require(text.length <= MAX_TEXT_CHARS) {
                        "DWD forecast text value exceeds $MAX_TEXT_CHARS characters"
                    }
                }

                override fun endElement(uri: String?, local: String, q: String?) {
                    val v = text.toString().trim()
                    when (local) {
                        "IssueTime" -> issue = Times.parse(v)
                        "TimeStep" -> {
                            times.add(Times.parse(v))
                            require(times.size <= MAX_TIMESTEPS) {
                                "DWD forecast has more than $MAX_TIMESTEPS time steps"
                            }
                        }
                        "name" -> if (insidePlace) id = v
                        "description" -> if (insidePlace) label = v
                        "coordinates" -> if (insidePlace) coords = v
                        "value" ->
                            if (field in fields) {
                                val list =
                                    v.split(Regex("\\s+")).map {
                                        it.toDoubleOrNull()?.takeIf(Double::isFinite)
                                    }
                                require(list.size <= MAX_VALUES_PER_FIELD) {
                                    "DWD forecast +$field has more than " +
                                        "$MAX_VALUES_PER_FIELD values"
                                }
                                values[field!!] = list
                            }
                        "Forecast" -> field = null
                        "Placemark" -> insidePlace = false
                    }
                }
            }
        val bounded = LimitedInputStream(MarkupGuardStream(input), MAX_XML_BYTES, "DWD KML")
        parser.parse(org.xml.sax.InputSource(bounded))
        val c = coords.split(',').map { it.trim().toDoubleOrNull() }
        require(times.isNotEmpty() && issue != null && id.isNotBlank() && c.size >= 2) {
            "Incomplete DWD forecast"
        }
        require(validCoordinates(c[1]!!, c[0]!!))
        require(times.zipWithNext().all { (a, b) -> b > a }) { "DWD times are not ordered" }
        require(values.values.all { it.size == times.size }) { "DWD forecast length mismatch" }
        return Forecast(
            Station(id, label, c[1]!!, c[0]!!, c.getOrNull(2)),
            issue!!,
            times.mapIndexed { i, t ->
                Hour(t, values.mapNotNull { (k, v) -> v[i]?.let { k to it } }.toMap())
            },
        )
    }
}

private class InputBudget(private val limit: Int, private val label: String) {
    private var used = 0

    fun add(count: Int) {
        used += count
        require(used <= limit) { "$label exceeds $limit decompressed bytes" }
    }
}

private class CountedInputStream(input: InputStream, private val budget: InputBudget) :
    FilterInputStream(input) {
    override fun read(): Int = super.read().also { if (it >= 0) budget.add(1) }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        super.read(bytes, offset, length).also { if (it > 0) budget.add(it) }
}

private class LimitedInputStream(
    input: InputStream,
    private val limit: Int,
    private val label: String,
) : FilterInputStream(input) {
    private var used = 0

    override fun read(): Int = super.read().also { if (it >= 0) count(1) }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        super.read(bytes, offset, length).also { if (it > 0) count(it) }

    private fun count(count: Int) {
        used += count
        require(used <= limit) { "$label exceeds $limit decompressed bytes" }
    }
}

/**
 * Rejects DTD declarations even where the platform parser cannot disable them.
 *
 * NUL bytes are ignored so UTF-16 declarations cannot bypass the check; valid DWD KML never
 * contains markup declarations.
 */
private class MarkupGuardStream(input: InputStream) : FilterInputStream(input) {
    private var doctype = 0
    private var entity = 0

    override fun read(): Int = super.read().also { if (it >= 0) inspect(it) }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(bytes, offset, length)
        for (index in 0 until read) inspect(bytes[offset + index].toInt() and 0xff)
        return read
    }

    private fun inspect(value: Int) {
        if (value == 0) return
        val next = value.toChar().lowercaseChar()
        doctype = advance(DOCTYPE, doctype, next)
        entity = advance(ENTITY, entity, next)
    }

    private fun advance(pattern: String, index: Int, next: Char): Int {
        val advanced = if (next == pattern[index]) index + 1 else if (next == '<') 1 else 0
        require(advanced < pattern.length) {
            "DWD XML contains a DOCTYPE or entity declaration"
        }
        return advanced
    }

    private companion object {
        const val DOCTYPE = "<!doctype"
        const val ENTITY = "<!entity"
    }
}

private fun XMLReader.setFeatureIfSupported(name: String, value: Boolean) {
    try {
        setFeature(name, value)
    } catch (_: SAXNotRecognizedException) {
        // The platform parser cannot express this feature; MarkupGuardStream still rejects DTDs.
    } catch (_: SAXNotSupportedException) {
        // Same fallback: the declaration scan is independent of parser features.
    }
}
