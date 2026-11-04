package app.drizziq

import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.xml.parsers.SAXParserFactory
import kotlin.math.*
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

internal enum class MapProduct(val title: String, val icon: String, val maxZoom: Double) {
    RADAR("Radar", "metric-rain", 17.0),
    SATELLITE("Satellite", "metric-sky", 9.0),
    TEMPERATURE("Temperature", "metric-temperature", 8.0);

    val cadence: Long
        get() = if (this == SATELLITE) 600_000L else 3600_000L

    val endpoint: String
        get() =
            if (this == SATELLITE) "https://view.eumetsat.int/geoserver/wms"
            else "https://maps.dwd.de/geoserver/dwd/wms"

    val layer: String
        get() = if (this == SATELLITE) "mtg_fd:rgb_geocolour" else "Icon_reg025_fd_sl_T2M"

    val capabilitiesUrl: String
        get() = "$endpoint?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities"
}

internal data class MapSelection(val product: MapProduct, val time: Long, val run: Long? = null)

internal data class MapAvailability(val times: List<Long>, val runs: List<Long> = emptyList())

/** Normalized Web Mercator bounds, independent of Android pixel coordinates. */
internal data class MapArea(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val width: Int,
    val height: Int,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right > left && bottom > top)
        require(width in 1..1280 && height in 1..1280)
    }

    fun covers(other: MapArea): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    companion object {
        fun viewport(
            x: Double,
            y: Double,
            zoom: Double,
            width: Int,
            height: Int,
            margin: Double = 1.0,
        ): MapArea {
            require(width > 0 && height > 0 && zoom in 2.0..17.0 && margin in 1.0..1.5)
            val world = 256 * 2.0.pow(zoom)
            val dx = width * margin / world / 2
            val dy = height * margin / world / 2
            val left = (x - dx).coerceIn(0.0, 1.0 - 1e-9)
            val top = (y - dy).coerceIn(0.0, 1.0 - 1e-9)
            val right = (x + dx).coerceIn(left + 1e-9, 1.0)
            val bottom = (y + dy).coerceIn(top + 1e-9, 1.0)
            val scale = min(1.0, 1280.0 / max(width * margin, height * margin))
            return MapArea(
                left,
                top,
                right,
                bottom,
                max(1, ((right - left) * world * scale).roundToInt()).coerceAtMost(1280),
                max(1, ((bottom - top) * world * scale).roundToInt()).coerceAtMost(1280),
            )
        }
    }
}

/** A pending request never relabels the image that is still on screen. */
internal class MapPresentation {
    var displayed = MapProduct.RADAR
        private set

    var requested = MapProduct.RADAR
        private set

    private var generation = 0

    fun request(product: MapProduct): Int {
        requested = product
        return ++generation
    }

    fun commit(product: MapProduct, token: Int): Boolean {
        if (token != generation || product != requested) return false
        displayed = product
        return true
    }
}

internal object MapWms {
    private const val WORLD = 20037508.342789244

    private fun iso(time: Long) = Instant.ofEpochMilli(time).toString()

    private fun query(endpoint: String, values: Map<String, String>) =
        endpoint +
            "?" +
            values.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

    /** Expand only the useful window, even when a satellite catalogue advertises years. */
    private fun instants(value: String, from: Long, until: Long): List<Long> =
        value
            .split(',')
            .flatMap { item ->
                val parts = item.trim().split('/')
                if (parts.size == 1)
                    listOf(Instant.parse(parts[0]).toEpochMilli()).filter { it in from..until }
                else {
                    require(parts.size == 3)
                    val start = Instant.parse(parts[0]).toEpochMilli()
                    val end = Instant.parse(parts[1]).toEpochMilli()
                    val step = Duration.parse(parts[2]).toMillis()
                    require(step > 0 && end >= start)
                    val first = start + max(0L, (from - start + step - 1) / step) * step
                    val last = min(end, until)
                    require(first > last || (last - first) / step <= 10000)
                    generateSequence(first) { it + step }.takeWhile { it <= last }.toList()
                }
            }
            .distinct()
            .sorted()

    fun capabilities(bytes: ByteArray, product: MapProduct, now: Long): MapAvailability {
        require(bytes.size <= 4 * 1024 * 1024 && product != MapProduct.RADAR)
        val raw = bytes.toString(Charsets.UTF_8)
        require(!raw.contains("<!DOCTYPE", true) && !raw.contains("<!ENTITY", true))
        val reader =
            SAXParserFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newSAXParser()
                .xmlReader
        for ((feature, value) in
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            )) {
            try {
                reader.setFeature(feature, value)
            } catch (_: org.xml.sax.SAXException) {}
        }
        val stack = mutableListOf<MutableMap<String, String>>()
        val path = mutableListOf<String>()
        val text = StringBuilder()
        var dimension = ""
        var result: MapAvailability? = null
        reader.contentHandler =
            object : DefaultHandler() {
                override fun startElement(
                    uri: String?,
                    local: String,
                    q: String?,
                    attrs: Attributes,
                ) {
                    text.setLength(0)
                    if (local == "Layer") stack.add(mutableMapOf())
                    if (local == "Dimension" && path.lastOrNull() == "Layer")
                        dimension = attrs.getValue("name")?.lowercase(java.util.Locale.US).orEmpty()
                    path.add(local)
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    require(text.length + length <= 1_000_000)
                    text.append(ch, start, length)
                }

                override fun endElement(uri: String?, local: String, q: String?) {
                    path.removeAt(path.lastIndex)
                    if (stack.isNotEmpty()) {
                        val layer = stack.last()
                        if (path.lastOrNull() == "Layer") {
                            if (local == "Name") layer["name"] = text.toString().trim()
                            if (local == "Dimension") layer[dimension] = text.toString().trim()
                        }
                        if (local == "Layer") {
                            if (layer["name"] == product.layer)
                                result =
                                    MapAvailability(
                                        instants(
                                            requireNotNull(layer["time"]),
                                            now - 24 * 3600_000L,
                                            now + 25 * 3600_000L,
                                        ),
                                        layer["reference_time"]
                                            ?.let { instants(it, now - 48 * 3600_000L, now) }
                                            .orEmpty(),
                                    )
                            stack.removeAt(stack.lastIndex)
                        }
                    }
                    text.setLength(0)
                }
            }
        reader.parse(org.xml.sax.InputSource(bytes.inputStream()))
        return requireNotNull(result) { "Product not advertised" }
            .also { require(it.times.isNotEmpty()) { "No recent product times" } }
    }

    fun times(product: MapProduct, available: MapAvailability, now: Long): List<Long> =
        available.times.filter {
            when (product) {
                MapProduct.SATELLITE ->
                    it <= now &&
                        it >= (available.times.lastOrNull { t -> t <= now } ?: now) - 6 * 3600_000L
                MapProduct.TEMPERATURE ->
                    it >= now / 3600_000L * 3600_000L &&
                        it <= now + 24 * 3600_000L &&
                        available.runs.any { run -> run <= it && run <= now }
                else -> false
            }
        }

    fun select(
        product: MapProduct,
        target: Long,
        now: Long,
        available: MapAvailability,
    ): MapSelection? {
        val time =
            available.times.lastOrNull {
                it <= target && (product != MapProduct.SATELLITE || it <= now)
            } ?: return null
        val run =
            if (product == MapProduct.TEMPERATURE)
                available.runs.lastOrNull { it <= time && it <= now } ?: return null
            else null
        return MapSelection(product, time, run)
    }

    fun imageUrl(selection: MapSelection, area: MapArea): String {
        val values =
            linkedMapOf(
                "SERVICE" to "WMS",
                "VERSION" to "1.3.0",
                "REQUEST" to "GetMap",
                "LAYERS" to selection.product.layer,
                "STYLES" to "",
                "CRS" to "EPSG:3857",
                "BBOX" to
                    listOf(
                            (area.left * 2 - 1) * WORLD,
                            (1 - area.bottom * 2) * WORLD,
                            (area.right * 2 - 1) * WORLD,
                            (1 - area.top * 2) * WORLD,
                        )
                        .joinToString(","),
                "WIDTH" to area.width.toString(),
                "HEIGHT" to area.height.toString(),
                "FORMAT" to "image/png",
                "TRANSPARENT" to "true",
                "TIME" to iso(selection.time),
            )
        selection.run?.let { values["DIM_REFERENCE_TIME"] = iso(it) }
        return query(selection.product.endpoint, values)
    }

    fun key(selection: MapSelection, area: MapArea): String =
        MessageDigest.getInstance("SHA-256")
            .digest(("map-v1|" + imageUrl(selection, area)).toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun serviceError(bytes: ByteArray): String? {
        if (bytes.firstOrNull() == 0x89.toByte()) return null
        return Regex(
                "<(?:[A-Za-z0-9_-]+:)?(?:ServiceException|ExceptionText)\\b[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9_-]+:)?(?:ServiceException|ExceptionText)>",
                RegexOption.IGNORE_CASE,
            )
            .find(bytes.toString(Charsets.UTF_8))
            ?.groupValues
            ?.get(1)
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace("&apos;", "'")
            ?.trim()
            ?.take(300)
    }

    fun timeUnavailable(message: String?): Boolean =
        message?.lowercase(java.util.Locale.US)?.let {
            "invalid dimension" in it ||
                "could not find a match for 'time'" in it ||
                "no data available" in it ||
                "could not read coverage" in it
        } == true
}
