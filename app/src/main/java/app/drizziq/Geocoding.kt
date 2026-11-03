package app.drizziq

import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

fun compactPlaceLabel(value: String): String {
    var end = minOf(60, value.length)
    if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
    return value.substring(0, end)
}

data class GeocodeCandidate(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val admin1: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val timezone: String? = null,
) {
    val fullLabel
        get() = listOfNotNull(name, admin1, country ?: countryCode).distinct().joinToString(", ")

    val label
        get() = compactPlaceLabel(fullLabel)

    fun toPlace(offline: (Double, Double) -> String?): Place? {
        val zone = Geocoding.zone(timezone, lat, lon, offline) ?: return null
        return Place(label, lat, lon, zone, "open-meteo:$id")
    }
}

object Geocoding {
    const val ENDPOINT = "https://geocoding-api.open-meteo.com/v1/search"
    const val MAX_BYTES = 256 * 1024

    fun query(input: String): String {
        val normalized =
            Normalizer.normalize(input, Normalizer.Form.NFC)
                .replace(Regex("[\\p{javaWhitespace}\\p{Z}]+"), " ")
                .trim()
        require(normalized.codePointCount(0, normalized.length) in 2..120) {
            "Enter 2–120 characters for a city name"
        }
        return normalized
    }

    fun key(input: String) =
        "open-meteo/search-v1/de/count10/json|" + query(input).lowercase(Locale.ROOT)

    fun url(input: String) =
        "$ENDPOINT?name=${URLEncoder.encode(query(input),"UTF-8")}&count=10&language=de&format=json"

    fun allowed(url: URL) =
        url.protocol == "https" &&
            url.host.equals("geocoding-api.open-meteo.com", true) &&
            url.path == "/v1/search" &&
            url.port in listOf(-1, 443) &&
            url.userInfo == null &&
            url.ref == null

    fun zone(
        returned: String?,
        lat: Double,
        lon: Double,
        offline: (Double, Double) -> String?,
    ): String? {
        Zones.canonical(returned)?.let {
            return it
        }
        return runCatching { offline(lat, lon) }.getOrNull()?.let { Zones.canonical(it) }
    }

    fun parse(text: String): List<GeocodeCandidate> {
        try {
            val tokener = JSONTokener(text)
            val root = tokener.nextValue() as? JSONObject ?: error("Invalid response")
            require(tokener.nextClean() == '\u0000')
            require(!root.has("error") || root.opt("error") == false)
            if (!root.has("results")) return emptyList()
            val array = root.opt("results") as? JSONArray ?: error("Invalid results")
            val result = mutableListOf<GeocodeCandidate>()
            val ids = HashSet<Long>()
            fun optional(j: JSONObject, key: String) =
                (j.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
            for (i in 0 until array.length()) {
                val j = array.opt(i) as? JSONObject ?: continue
                val rawId = j.opt("id")
                if (rawId !is Int && rawId !is Long) continue
                val id = (rawId as Number).toLong()
                val name = optional(j, "name") ?: continue
                val lat = (j.opt("latitude") as? Number)?.toDouble() ?: continue
                val lon = (j.opt("longitude") as? Number)?.toDouble() ?: continue
                if (id <= 0 || !validCoordinates(lat, lon) || !ids.add(id)) continue
                result +=
                    GeocodeCandidate(
                        id,
                        name,
                        lat,
                        lon,
                        optional(j, "admin1"),
                        optional(j, "country"),
                        optional(j, "country_code"),
                        optional(j, "timezone"),
                    )
                if (result.size == 10) break
            }
            require(array.length() == 0 || result.isNotEmpty())
            return result
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid geocoding response", error)
        }
    }

    fun retryDelay(value: String?, now: Long): Long? {
        val raw = value?.trim() ?: return null
        if (raw.matches(Regex("[0-9]+"))) {
            val seconds = raw.toLongOrNull() ?: return Long.MAX_VALUE
            return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
        }
        val format =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("GMT")
            }
        val position = ParsePosition(0)
        val date = format.parse(raw, position) ?: return null
        if (position.index != raw.length) return null
        return (date.time - now).coerceAtLeast(0)
    }
}

class SearchCache(
    private val clock: () -> Long,
    private val capacity: Int = 20,
    private val ttlMillis: Long = 1800000,
) {
    private data class Entry(val time: Long, val candidates: List<GeocodeCandidate>)

    private val entries = java.util.LinkedHashMap<String, Entry>(capacity, .75f, true)

    init {
        require(capacity > 0 && ttlMillis >= 0)
    }

    private fun expire() {
        val now = clock()
        entries.entries.removeAll { now < it.value.time || now - it.value.time > ttlMillis }
    }

    @Synchronized
    fun put(key: String, candidates: List<GeocodeCandidate>) {
        expire()
        if (candidates.isEmpty()) {
            entries.remove(key)
            return
        }
        entries[key] = Entry(clock(), candidates.toList())
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun get(key: String): List<GeocodeCandidate>? {
        expire()
        return entries[key]?.candidates
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

data class GeocodeReply(val status: Int, val body: ByteArray, val retryAfter: String? = null)

fun interface GeocodeTransport {
    fun fetch(address: String): GeocodeReply
}

sealed class SearchResult {
    data class Results(val candidates: List<GeocodeCandidate>, val cached: Boolean) : SearchResult()

    data class Failure(
        val message: String,
        val candidates: List<GeocodeCandidate> = emptyList(),
        val retryInMillis: Long? = null,
    ) : SearchResult()

    data object Empty : SearchResult()

    data object Obsolete : SearchResult()
}

/** Called on the owned search worker. Cache operations remain safe for UI clearing. */
class CitySearch(
    private val transport: GeocodeTransport,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
) {
    private val cache = SearchCache(clock)
    @Volatile private var retryAt = 0L

    fun search(
        query: String,
        refresh: Boolean = false,
        online: Boolean = true,
        isCurrent: () -> Boolean = { true },
        commitIfCurrent: ((() -> Unit) -> Boolean) = { action ->
            if (isCurrent()) {
                action()
                true
            } else false
        },
    ): SearchResult {
        if (!isCurrent()) return SearchResult.Obsolete
        val key = Geocoding.key(query)
        val cached = cache.get(key)
        if (cached != null && !refresh) return SearchResult.Results(cached, true)
        if (!online)
            return SearchResult.Failure(
                "City search needs a connection. Use saved places or retry when online.",
                cache.get(key) ?: emptyList(),
            )
        val wait = (retryAt - clock()).coerceAtLeast(0)
        if (wait > 0)
            return SearchResult.Failure(
                "Search rate limited. Try again in ${wait/1000+if(wait%1000==0L)0 else 1} seconds.",
                cache.get(key) ?: emptyList(),
                wait,
            )
        try {
            val response = transport.fetch(Geocoding.url(query))
            Geocoding.retryDelay(response.retryAfter, wallClock())?.let { delay ->
                val now = clock()
                retryAt = if (delay > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delay
            }
            if (!isCurrent()) return SearchResult.Obsolete
            if (response.status != 200) {
                val remaining = (retryAt - clock()).coerceAtLeast(0)
                return SearchResult.Failure(
                    if (remaining > 0)
                        "Search rate limited. Try again in ${remaining/1000+if(remaining%1000==0L)0 else 1} seconds."
                    else "City search unavailable. Please retry.",
                    cache.get(key) ?: emptyList(),
                    remaining.takeIf { it > 0 },
                )
            }
            require(response.body.size <= Geocoding.MAX_BYTES)
            val decoder =
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            val candidates =
                Geocoding.parse(decoder.decode(java.nio.ByteBuffer.wrap(response.body)).toString())
            if (!isCurrent()) return SearchResult.Obsolete
            if (!commitIfCurrent { cache.put(key, candidates) }) return SearchResult.Obsolete
            return if (candidates.isEmpty()) SearchResult.Empty
            else SearchResult.Results(candidates, false)
        } catch (error: Exception) {
            if (!isCurrent()) return SearchResult.Obsolete
            return SearchResult.Failure(
                "City search unavailable. Check your connection and retry.",
                cache.get(key) ?: emptyList(),
            )
        }
    }

    fun clearCache() {
        cache.clear()
    }
}
