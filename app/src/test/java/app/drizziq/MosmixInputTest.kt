package app.drizziq

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class MosmixInputTest {
    @Test
    fun checkedInProductMeasuresWithinDecompressedLimits() {
        val inflated =
            ZipInputStream(javaClass.getResourceAsStream("/munich-20261022.kmz")!!.buffered())
                .use { zip ->
                    assertTrue(zip.nextEntry.name.endsWith(".kml"))
                    zip.readBytes().size
                }
        assertEquals(344_442, inflated)
        assertTrue(inflated <= Mosmix.MAX_XML_BYTES)
        assertTrue(inflated <= Mosmix.MAX_ARCHIVE_BYTES)
    }

    @Test
    fun xmlAtDecompressedLimit_isAccepted() {
        val forecast = Mosmix.parseXml(xmlOfSize(Mosmix.MAX_XML_BYTES).inputStream())
        assertEquals("10865", forecast.station.id)
        assertEquals(1, forecast.hours.size)
    }

    @Test
    fun xmlOverDecompressedLimit_isRejected() {
        val error =
            assertThrows(Exception::class.java) {
                Mosmix.parseXml(xmlOfSize(Mosmix.MAX_XML_BYTES + 1).inputStream())
            }
        assertTrue(error.message.orEmpty().contains("KML", ignoreCase = true))
    }

    @Test
    fun textAtAccumulationLimit_isAccepted() {
        val label = "x".repeat(Mosmix.MAX_TEXT_CHARS)
        val forecast = Mosmix.parseXml(forecastXml(label).byteInputStream())
        assertEquals(Mosmix.MAX_TEXT_CHARS, forecast.station.name.length)
    }

    @Test
    fun textOverAccumulationLimit_isRejected() {
        val error =
            assertThrows(Exception::class.java) {
                Mosmix.parseXml(
                    forecastXml("x".repeat(Mosmix.MAX_TEXT_CHARS + 1)).byteInputStream()
                )
            }
        assertTrue(error.message.orEmpty().contains("text", ignoreCase = true))
    }

    @Test
    fun timestepAndValueCountsAtLimit_areAccepted() {
        val forecast =
            Mosmix.parseXml(
                countXml(Mosmix.MAX_TIMESTEPS, Mosmix.MAX_VALUES_PER_FIELD).byteInputStream()
            )
        assertEquals(Mosmix.MAX_TIMESTEPS, forecast.hours.size)
        assertEquals(1, forecast.hours.first().values.size)
        assertTrue(forecast.hours.all { it.get("TTT") == 1.0 })
    }

    @Test
    fun timestepCountOverLimit_isRejected() {
        val error =
            assertThrows(Exception::class.java) {
                Mosmix.parseXml(
                    countXml(Mosmix.MAX_TIMESTEPS + 1, Mosmix.MAX_VALUES_PER_FIELD)
                        .byteInputStream()
                )
            }
        assertTrue(error.message.orEmpty().contains("time steps", ignoreCase = true))
    }

    @Test
    fun valueCountOverLimit_isRejected() {
        val error =
            assertThrows(Exception::class.java) {
                Mosmix.parseXml(
                    countXml(Mosmix.MAX_TIMESTEPS, Mosmix.MAX_VALUES_PER_FIELD + 1)
                        .byteInputStream()
                )
            }
        assertTrue(error.message.orEmpty().contains("values", ignoreCase = true))
    }

    @Test
    fun archiveEntryCountAtLimit_isAccepted() {
        val entries =
            (1 until Mosmix.MAX_ARCHIVE_ENTRIES).map {
                "ignored-$it.txt" to ByteArray(0)
            } + ("forecast.kml" to forecastXml().toByteArray())
        val forecast = Mosmix.parseKmz(kmz(entries).inputStream())
        assertEquals("10865", forecast.station.id)
    }

    @Test
    fun archiveEntryCountOverLimit_isRejected() {
        val entries =
            (1..Mosmix.MAX_ARCHIVE_ENTRIES).map {
                "ignored-$it.txt" to ByteArray(0)
            } + ("forecast.kml" to forecastXml().toByteArray())
        val error =
            assertThrows(Exception::class.java) {
                Mosmix.parseKmz(kmz(entries).inputStream())
            }
        assertTrue(error.message.orEmpty().contains("entries", ignoreCase = true))
    }

    @Test
    fun kmlAtDecompressedLimitInsideArchive_isAccepted() {
        val forecast =
            Mosmix.parseKmz(
                kmz(listOf("forecast.kml" to xmlOfSize(Mosmix.MAX_XML_BYTES))).inputStream()
            )
        assertEquals("10865", forecast.station.id)
    }

    @Test
    fun kmlOverDecompressedLimitInsideArchive_isRejected() {
        val error =
            assertThrows(Exception::class.java) {
                Mosmix.parseKmz(
                    kmz(listOf("forecast.kml" to xmlOfSize(Mosmix.MAX_XML_BYTES + 1))).inputStream()
                )
            }
        assertTrue(error.message.orEmpty().contains("KML", ignoreCase = true))
    }

    @Test
    fun skippedEntryBytesCountTowardInflatedArchiveLimit() {
        val skipped = ByteArray(Mosmix.MAX_ARCHIVE_BYTES + 1)
        val error =
            assertThrows(Exception::class.java) {
                Mosmix.parseKmz(
                    kmz(
                            listOf(
                                "ignored.bin" to skipped,
                                "forecast.kml" to forecastXml().toByteArray(),
                            )
                        )
                        .inputStream()
                )
            }
        assertTrue(error.message.orEmpty().contains("archive", ignoreCase = true))
    }

    @Test
    fun doctypeAndExternalEntityDeclarations_areRejected() {
        val xml =
            forecastXml()
                .replace(
                    "<kml ",
                    "<!DOCTYPE kml [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]> <kml ",
                )
        val error =
            assertThrows(Exception::class.java) {
                Mosmix.parseXml(xml.byteInputStream())
            }
        assertTrue(error.message.orEmpty().contains("DOCTYPE", ignoreCase = true))
    }

    private fun xmlOfSize(size: Int): ByteArray {
        val declaration = """<?xml version="1.0" encoding="ISO-8859-1"?>"""
        val prefix = "${declaration}<!--"
        val suffix = "-->" + forecastXml().removePrefix(declaration)
        val padding = size - prefix.toByteArray().size - suffix.toByteArray().size
        assertTrue("Requested XML size is too small", padding >= 0)
        return (prefix + "x".repeat(padding) + suffix).toByteArray(Charsets.ISO_8859_1)
    }

    private fun countXml(times: Int, values: Int): String {
        val start = Times.parse("2026-10-22T15:00:00Z")
        val steps =
            (0 until times).joinToString("") {
                "<d:TimeStep>${Instant.ofEpochMilli(start + it * 3600000L)}</d:TimeStep>"
            }
        val data = (0 until values).joinToString(" ") { "1" }
        return """<?xml version="1.0" encoding="ISO-8859-1"?><kml xmlns="http://www.opengis.net/kml/2.2" xmlns:d="urn:test"><Document><d:IssueTime>2026-10-22T15:00:00Z</d:IssueTime>$steps<Placemark><name>10865</name><description>Munich</description><Point><coordinates>11.533,48.167,515</coordinates></Point><d:Forecast d:elementName="TTT"><d:value>$data</d:value></d:Forecast></Placemark></Document></kml>"""
    }

    private fun forecastXml(description: String = "Munich"): String =
        """<?xml version="1.0" encoding="ISO-8859-1"?><kml xmlns="http://www.opengis.net/kml/2.2" xmlns:d="urn:test"><Document><d:IssueTime>2026-10-22T15:00:00Z</d:IssueTime><d:TimeStep>2026-10-22T16:00:00Z</d:TimeStep><Placemark><name>10865</name><description>$description</description><Point><coordinates>11.533,48.167,515</coordinates></Point></Placemark></Document></kml>"""

    private fun kmz(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
