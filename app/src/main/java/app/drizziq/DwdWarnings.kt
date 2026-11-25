package app.drizziq

import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.time.OffsetDateTime
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException

internal enum class WarningLanguage(val code: String, val title: String) {
    GERMAN("de", "Deutsch"),
    ENGLISH("en", "English"),
    FRENCH("fr", "Français"),
    SPANISH("es", "Español"),
    ARABIC("ar", "العربية"),
    RUSSIAN("ru", "Русский"),
    TURKISH("tr", "Türkçe"),
    POLISH("pl", "Polski");

    companion object {
        fun fromTag(tag: String): WarningLanguage? = entries.firstOrNull {
            it.code == tag.substringBefore('-').lowercase()
        }
    }
}

internal data class WarningText(
    val headline: String,
    val description: String,
    val instruction: String,
)

internal data class DwdWarning(
    val headline: String,
    val description: String,
    val instruction: String,
    val starts: Long,
    val expires: Long,
    val geometryCredit: String,
    val id: String?,
    val translations: Map<WarningLanguage, WarningText>,
)

internal object DwdWarnings {
    const val PATH =
        "alerts/cap/COMMUNEUNION_DWD_STAT/Z_CAP_C_EDZW_LATEST_PVW_STATUS_PREMIUMDWD_COMMUNEUNION_DE.zip"
    const val MULTILINGUAL_PATH =
        "alerts/cap/COMMUNEUNION_DWD_STAT/Z_CAP_C_EDZW_LATEST_PVW_STATUS_PREMIUMDWD_COMMUNEUNION_MUL.zip"
    const val DOWNLOAD_LIMIT = 8 * 1024 * 1024
    const val MULTILINGUAL_DOWNLOAD_LIMIT = 16 * 1024 * 1024
    private const val XML_LIMIT = 32 * 1024 * 1024
    private const val MULTILINGUAL_XML_LIMIT = 96 * 1024 * 1024
    private const val XML_ENTRY_LIMIT = 16 * 1024 * 1024
    private const val NS = "urn:oasis:names:tc:emergency:cap:1.2"
    private val whitespace = Regex("\\s+")

    fun at(
        archive: ByteArray,
        place: Place,
        now: Long,
        multilingual: Boolean = false,
    ): List<DwdWarning> {
        require(archive.size >= 22 && archive[0] == 0x50.toByte() && archive[1] == 0x4b.toByte()) {
            "Expected a DWD CAP ZIP archive"
        }
        val parser =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
        parser.setEntityResolver { _, _ -> throw SAXException("External CAP entity") }
        return ZipInputStream(archive.inputStream()).use { zip ->
            var remaining = if (multilingual) MULTILINGUAL_XML_LIMIT else XML_LIMIT
            var count = 0
            val buffer = ByteArray(8192)
            buildList {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 512) { "Too many CAP entries" }
                    val xml = ByteArrayOutputStream()
                    val isXml = entry.name.endsWith(".xml", ignoreCase = true)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        remaining -= read
                        require(remaining >= 0) { "CAP archive exceeds expanded size limit" }
                        if (isXml) {
                            require(xml.size() + read <= XML_ENTRY_LIMIT) {
                                "CAP entry is too large"
                            }
                            xml.write(buffer, 0, read)
                        }
                    }
                    if (!isXml) continue
                    // Read the DWD's UTF-8 as characters and reject DTDs on every Android version.
                    val source = xml.toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF")
                    require("<!DOCTYPE" !in source) { "Unexpected CAP document type" }
                    val alert = parser.parse(InputSource(StringReader(source))).documentElement
                    require(alert.localName == "alert" && alert.namespaceURI == NS)
                    if (
                        alert.text("status") != "Actual" ||
                            alert.text("scope") != "Public" ||
                            alert.text("msgType") !in listOf("Alert", "Update")
                    )
                        continue
                    val id =
                        alert
                            .children("code")
                            .map { it.textContent.trim() }
                            .firstOrNull { it.startsWith("id:") && it.length > 3 }
                    val infos = alert.children("info").toList()
                    for (info in infos) {
                        val tag = info.text("language")
                        if (
                            tag.isNotEmpty() &&
                                WarningLanguage.fromTag(tag) != WarningLanguage.GERMAN
                        )
                            continue
                        val expires = parseTime(info.text("expires")) ?: continue
                        val effective = parseTime(info.text("effective")) ?: continue
                        val starts = parseTime(info.text("onset")) ?: effective
                        if (effective > now || expires <= now || starts >= expires) continue
                        val text = info.warningText()
                        if (
                            text.headline.isBlank() ||
                                !info.children("area").any { area ->
                                    area.children("polygon").any {
                                        contains(it.textContent, place)
                                    } &&
                                        area.children("geocode").none {
                                            it.text("valueName") == "EXCLUDE_POLYGON" &&
                                                contains(it.text("value"), place)
                                        }
                                }
                        )
                            continue
                        val credit =
                            info
                                .children("eventCode")
                                .firstOrNull { it.text("valueName") == "LICENSE" }
                                ?.text("value")
                                .orEmpty()
                        val translations =
                            if (multilingual && id != null) translationsOf(info, infos)
                            else emptyMap()
                        add(
                            DwdWarning(
                                text.headline,
                                text.description,
                                text.instruction,
                                starts,
                                expires,
                                credit,
                                id,
                                translations,
                            )
                        )
                    }
                }
            }
                .distinct()
                .sortedBy { it.starts }
        }
    }

    private fun Element.children(name: String): Sequence<Element> =
        generateSequence(firstChild) { it.nextSibling }
            .filterIsInstance<Element>()
            .filter { it.localName == name && it.namespaceURI == NS }

    private fun Element.text(name: String): String =
        children(name).firstOrNull()?.textContent?.trim().orEmpty()

    private fun Element.warningText() =
        WarningText(
            text("headline").ifBlank { text("event") },
            text("description"),
            text("instruction"),
        )

    private fun translationsOf(
        info: Element,
        infos: List<Element>,
    ): Map<WarningLanguage, WarningText> = buildMap {
        val original = info.warningText()
        for (candidate in infos) {
            val language = WarningLanguage.fromTag(candidate.text("language")) ?: continue
            if (language == WarningLanguage.GERMAN) continue
            if (
                candidate.text("expires") != info.text("expires") ||
                    candidate.text("effective") != info.text("effective") ||
                    candidate.text("onset") != info.text("onset")
            )
                continue
            val translation = candidate.warningText()
            if (
                translation.headline.isNotBlank() &&
                    (original.description.isBlank() || translation.description.isNotBlank()) &&
                    (original.instruction.isBlank() || translation.instruction.isNotBlank())
            )
                put(language, translation)
        }
    }

    private fun parseTime(value: String): Long? = runCatching {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }
        .getOrNull()

    private fun contains(value: String, place: Place): Boolean {
        val points =
            value.trim().split(whitespace).map {
                val lat = it.substringBefore(',').toDoubleOrNull() ?: return false
                val lon = it.substringAfter(',', "").toDoubleOrNull() ?: return false
                if (!validCoordinates(lat, lon)) return false
                lat to lon
            }
        if (points.size < 4 || points.first() != points.last()) return false
        var inside = false
        for (index in 1 until points.size) {
            val (aLat, aLon) = points[index - 1]
            val (bLat, bLon) = points[index]
            if (
                place.lat in minOf(aLat, bLat)..maxOf(aLat, bLat) &&
                    place.lon in minOf(aLon, bLon)..maxOf(aLon, bLon) &&
                    kotlin.math.abs(
                        (place.lat - aLat) * (bLon - aLon) - (place.lon - aLon) * (bLat - aLat)
                    ) < 1e-10
            )
                return true
            if (
                (aLat > place.lat) != (bLat > place.lat) &&
                    place.lon < aLon + (bLon - aLon) * (place.lat - aLat) / (bLat - aLat)
            )
                inside = !inside
        }
        return inside
    }
}
