package app.drizziq

import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

internal enum class Overlay(
    val layer: String,
    val style: String,
    val cadence: Long,
    val maxZoom: Int,
    val alpha: Int,
    val ttl: Long,
) {
    TEMPERATURE("Icon_reg025_fd_sl_T2M", "", 3600000L, 5, 120, 2 * 3600000L),
    CLOUDS(
        "Satellite_worldmosaic_3km_world_ir108_3h",
        "satellite_worldmosaic_3km_world_ir108_3h",
        10800000L,
        7,
        110,
        6 * 3600000L,
    ),
}

internal data class OverlaySelection(val overlay: Overlay, val time: Long)

internal data class OverlayAvailability(val times: List<Long>)

internal object OverlayModel {
    private const val WORLD = 20037508.342789244
    private val TIME_FORMAT =
        java.time.format.DateTimeFormatter.ofPattern(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                java.util.Locale.US,
            )
            .withZone(java.time.ZoneOffset.UTC)

    private fun iso(time: Long) = TIME_FORMAT.format(Instant.ofEpochMilli(time))

    fun bounds(z: Int, x: Int, y: Int): DoubleArray {
        require(z in 0..22 && x in 0 until (1 shl z) && y in 0 until (1 shl z))
        val size = 2 * WORLD / (1 shl z)
        return doubleArrayOf(
            -WORLD + x * size,
            WORLD - (y + 1) * size,
            -WORLD + (x + 1) * size,
            WORLD - y * size,
        )
    }

    private fun instants(value: String): List<Long> =
        value.split(',').flatMap { item ->
            val parts = item.trim().split('/')
            if (parts.size == 1) listOf(Instant.parse(parts[0]).toEpochMilli())
            else {
                require(parts.size == 3)
                val start = Instant.parse(parts[0]).toEpochMilli()
                val end = Instant.parse(parts[1]).toEpochMilli()
                val step = Duration.parse(parts[2]).toMillis()
                require(step > 0 && end >= start && (end - start) / step <= 10000)
                generateSequence(start) { previous -> (previous + step).takeIf { it <= end } }
                    .toList()
            }
        }

    fun capabilities(bytes: ByteArray): Map<Overlay, OverlayAvailability> {
        require(bytes.size <= 4 * 1024 * 1024)
        val result = mutableMapOf<Overlay, OverlayAvailability>()
        val stack = mutableListOf<MutableMap<String, String>>()
        var dimension = ""
        val text = StringBuilder()
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
        reader.contentHandler =
            object : DefaultHandler() {
                private val path = mutableListOf<String>()

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
                    if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                    if (stack.isEmpty()) {
                        text.setLength(0)
                        return
                    }
                    val layer = stack.last()
                    val parent = path.lastOrNull()
                    if (local == "Name" && parent == "Layer") layer["name"] = text.toString().trim()
                    if (local == "Dimension" && parent == "Layer")
                        layer[dimension] = text.toString().trim()
                    if (local == "Layer") {
                        val overlay = Overlay.entries.firstOrNull { it.layer == layer["name"] }
                        if (overlay != null)
                            result[overlay] =
                                OverlayAvailability(
                                    instants(requireNotNull(layer["time"])).sorted()
                                )
                        stack.removeAt(stack.lastIndex)
                    }
                    text.setLength(0)
                }
            }
        val raw = bytes.toString(Charsets.UTF_8)
        require(!raw.contains("<!DOCTYPE", true) && !raw.contains("<!ENTITY", true))
        reader.parse(org.xml.sax.InputSource(bytes.inputStream()))
        return result
    }

    /** Recent slot times, newest first, for resolving around stale advertised windows. */
    fun candidates(overlay: Overlay, now: Long): List<Long> {
        require(now > 0)
        val limit = if (overlay == Overlay.CLOUDS) 16 else 24
        val latest = now / overlay.cadence * overlay.cadence
        return (0 until limit).map { latest - it * overlay.cadence }
    }

    /** DWD answers an unavailable time with a dimension or coverage error. */
    fun timeUnavailable(message: String?): Boolean {
        if (message == null) return false
        val lower = message.lowercase(java.util.Locale.US)
        return "invalid dimension value" in lower ||
            "could not find a match for 'time'" in lower ||
            "no data available" in lower ||
            "could not read coverage data" in lower
    }

    fun select(
        overlay: Overlay,
        target: Long,
        now: Long,
        caps: Map<Overlay, OverlayAvailability>,
        resolved: Long? = null,
    ): OverlaySelection? {
        val available = caps[overlay] ?: return null
        if (resolved != null) return OverlaySelection(overlay, resolved)
        val limit = if (overlay == Overlay.CLOUDS) now else target
        if (
            available.times.isEmpty() ||
                limit < available.times.first() ||
                (overlay != Overlay.CLOUDS && limit >= available.times.last() + overlay.cadence)
        )
            return null
        val time = available.times.lastOrNull { it <= limit } ?: return null
        return OverlaySelection(overlay, time)
    }

    fun key(selection: OverlaySelection, z: Int, x: Int, y: Int) =
        "${selection.overlay.name}-${selection.overlay.layer}-${selection.time}-$z-$x-$y"

    fun url(selection: OverlaySelection, z: Int, x: Int, y: Int): String {
        val bounds = bounds(z, x, y)
        val values =
            linkedMapOf(
                "SERVICE" to "WMS",
                "VERSION" to "1.3.0",
                "REQUEST" to "GetMap",
                "LAYERS" to selection.overlay.layer,
                "STYLES" to selection.overlay.style,
                "CRS" to "EPSG:3857",
                "BBOX" to bounds.joinToString(",") { java.lang.Double.toString(it) },
                "WIDTH" to "256",
                "HEIGHT" to "256",
                "FORMAT" to "image/png",
                "TRANSPARENT" to "true",
                "TIME" to iso(selection.time),
            )
        return "https://maps.dwd.de/geoserver/dwd/wms?" +
            values.entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
    }

    fun serviceError(bytes: ByteArray): String? {
        val text = bytes.toString(Charsets.UTF_8)
        return Regex(
                "<(?:(?:[A-Za-z0-9_-]+):)?ServiceException\\b[^>]*>([\\s\\S]*?)</(?:(?:[A-Za-z0-9_-]+):)?ServiceException>",
                RegexOption.IGNORE_CASE,
            )
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace("&apos;", "'")
            ?.replace("&quot;", "\"")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&amp;", "&")
            ?.trim()
            ?.take(300)
    }
}
