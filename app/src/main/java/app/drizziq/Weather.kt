package app.drizziq

import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import kotlin.math.*

data class Place(val label: String, val lat: Double, val lon: Double, val zone: String = "Europe/Berlin") {
    init { require(validCoordinates(lat, lon)) }
    companion object { val MUNICH = Place("Munich", 48.138108, 11.574726) }
}

fun validCoordinates(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

data class Station(val id: String, val name: String, val lat: Double, val lon: Double, val elevation: Double?) {
    fun distance(place: Place): Double {
        val a = sin(Math.toRadians(place.lat - lat) / 2).pow(2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(place.lat)) * sin(Math.toRadians(place.lon - lon) / 2).pow(2)
        return 6371.0088 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

enum class Freshness { LOADING, LIVE, CACHED, STALE, ERROR }

data class Reading<T>(val data: T? = null, val state: Freshness = Freshness.LOADING,
                      val fetched: Long? = null, val message: String? = null, val refreshing: Boolean = false)

object Times {
    val utc: TimeZone = TimeZone.getTimeZone("UTC")
    fun parse(text: String): Long = SimpleDateFormat(if (text.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = utc; isLenient = false }.parse(text)!!.time
    fun format(time: Long, pattern: String = "HH:mm", zone: String = "UTC"): String =
        SimpleDateFormat(pattern, Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(time))
    fun day(time: Long, zone: String) = format(time, "yyyy-MM-dd", zone)
}

data class Hour(val time: Long, val values: Map<String, Double>) {
    fun get(name: String) = values[name]
    val temperature get() = get("TTT")?.minus(273.15)
    val wind get() = get("FF")?.times(3.6)
    val condition get() = get("ww")?.toInt()
    // Relative humidity is intentionally not derived from temperature/dewpoint.
    val humidity get() = get("RF") ?: get("RELH")
}

data class Day(val time: Long, val hours: List<Hour>, val complete: Boolean) {
    val high get() = hours.mapNotNull { it.temperature }.maxOrNull()
    val low get() = hours.mapNotNull { it.temperature }.minOrNull()
    val wind get() = hours.mapNotNull { it.wind }.maxOrNull()
    // Missing intervals never turn into a partial total advertised as a daily total.
    fun total(field: String): Double? = if (complete && hours.isNotEmpty() && hours.all { it.get(field) != null }) hours.sumOf { it.get(field)!! } else null
    val rain get() = total("RR1c")
    val sun get() = total("SunD1")?.div(3600)
    val condition get() = hours.filter { it.condition != null }.maxByOrNull { weatherSeverity(it.condition!!) }?.condition
}

data class Forecast(val station: Station, val issue: Long, val hours: List<Hour>) {
    fun current(now: Long): Hour? = hours.minByOrNull { abs(it.time - now) }?.takeIf { abs(it.time - now) <= 3600000 }
    fun days(now: Long, zone: String): List<Day> = hours.filter { Times.day(it.time - 1, zone) >= Times.day(now, zone) }
        .groupBy { Times.day(it.time - 1, zone) }.values.take(7).map { h ->
            val start = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
                timeInMillis = h.first().time - 1; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val startMillis = start.timeInMillis
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            val complete = h.first().time == startMillis + 3600000 && h.last().time == end &&
                h.zipWithNext().all { (a,b) -> b.time - a.time == 3600000L }
            Day(startMillis, h, complete)
        }
}

fun weatherSeverity(code: Int): Int = when (code) { in 95..99 -> 9; in 80..94 -> 8; in 70..79 -> 7; in 60..69 -> 6; in 50..59 -> 5; in 40..49 -> 4; else -> code.coerceIn(0, 3) }
fun weatherLabel(code: Int?): String? = when(code) {
    null -> null
    0 -> "Clear sky"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
    in 4..19 -> "Haze / visibility"; in 20..29 -> "Recent precipitation"; in 30..39 -> "Blowing snow / dust"
    in 40..49 -> "Fog"; in 50..59 -> "Drizzle"; in 60..69 -> "Rain"; in 70..79 -> "Snow"
    in 80..84 -> "Rain showers"; in 85..94 -> "Snow / mixed showers"; in 95..99 -> "Thunderstorm"
    else -> "DWD weather code $code"
}

object Mosmix {
    private val fields = setOf("TTT", "TX", "TN", "ww", "FF", "DD", "RR1c", "SunD1", "RF", "RELH", "Td", "N")
    fun parseKmz(input: InputStream): Forecast = ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: error("DWD archive contains no KML")
            if (entry.name.endsWith(".kml", true)) return@use parseXml(zip)
        }
        @Suppress("UNREACHABLE_CODE") error("No KML")
    }
    fun parseXml(input: InputStream): Forecast {
        val times = mutableListOf<Long>(); val values = mutableMapOf<String,List<Double?>>()
        var issue: Long? = null; var id = ""; var label = ""; var coords = ""; var insidePlace = false
        var field: String? = null; val text = StringBuilder()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newSAXParser().xmlReader
        parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        parser.contentHandler = object : DefaultHandler() {
            override fun startElement(uri:String?, local:String, q:String?, attrs:Attributes) {
                text.setLength(0)
                if (local == "Placemark") insidePlace = true
                if (local == "Forecast") field = (0 until attrs.length).firstOrNull { attrs.getLocalName(it) == "elementName" }?.let { attrs.getValue(it) }
            }
            override fun characters(ch:CharArray, start:Int, length:Int) { text.append(ch, start, length) }
            override fun endElement(uri:String?, local:String, q:String?) {
                val v = text.toString().trim()
                when(local) {
                    "IssueTime" -> issue = Times.parse(v)
                    "TimeStep" -> times.add(Times.parse(v))
                    "name" -> if(insidePlace) id = v
                    "description" -> if(insidePlace) label = v
                    "coordinates" -> if(insidePlace) coords = v
                    "value" -> if(field in fields) values[field!!] = v.split(Regex("\\s+")).map { it.toDoubleOrNull()?.takeIf(Double::isFinite) }
                    "Forecast" -> field = null
                    "Placemark" -> insidePlace = false
                }
            }
        }
        parser.parse(org.xml.sax.InputSource(input))
        val c = coords.split(',').map { it.trim().toDoubleOrNull() }
        require(times.isNotEmpty() && issue != null && id.isNotBlank() && c.size >= 2) { "Incomplete DWD forecast" }
        require(validCoordinates(c[1]!!, c[0]!!))
        require(times.zipWithNext().all { (a,b) -> b > a }) { "DWD times are not ordered" }
        require(values.values.all { it.size == times.size }) { "DWD forecast length mismatch" }
        return Forecast(Station(id,label,c[1]!!,c[0]!!,c.getOrNull(2)),issue!!,
            times.mapIndexed { i,t -> Hour(t,values.mapNotNull { (k,v) -> v[i]?.let { k to it } }.toMap()) })
    }
}
