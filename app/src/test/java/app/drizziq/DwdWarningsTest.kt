package app.drizziq

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class DwdWarningsTest {
    private val xml = javaClass.getResource("/cap-munich-test.xml")!!.readText()
    private val now = Instant.parse("2026-10-22T20:05:00Z").toEpochMilli()

    private fun archive(source: String): ByteArray =
        ByteArrayOutputStream()
            .apply {
                ZipOutputStream(this).use {
                    it.putNextEntry(ZipEntry("warning.xml"))
                    it.write(source.toByteArray())
                }
            }
            .toByteArray()

    @Test
    fun multilingualArchiveKeepsOnlyOneWarningAndFallsBackWhenTranslationIsMissing() {
        val german = xml.substringAfter("<info>").substringBefore("</info>")
        val english = german.replace("Fixture wind warning", "English wind warning")
        val incomplete = german.replace("<instruction>Secure loose items.</instruction>", "")
        val multilingual =
            xml.replace("<info>", "<code>id:wind</code><info><language>de-DE</language>")
                .replace(
                    "</alert>",
                    "<info><language>en-GB</language>$english</info>" +
                        "<info><language>fr</language>$incomplete</info></alert>",
                )
        val warning =
            DwdWarnings.at(archive(multilingual), Place.MUNICH, now, multilingual = true).single()
        assertEquals("id:wind", warning.id)
        assertEquals(
            "English wind warning",
            warning.translations[WarningLanguage.ENGLISH]?.headline,
        )
        assertNull(warning.translations[WarningLanguage.FRENCH])
        assertNull(warning.translations[WarningLanguage.POLISH])
    }

    @Test
    fun publishedUpdateIncludesFutureOnsetAndHonoursExpiryAndGeometry() {
        val zip = archive(xml)
        val warning = DwdWarnings.at(zip, Place.MUNICH, now).single()
        assertTrue(warning.starts > now)
        assertEquals("Secure loose items.", warning.instruction)
        assertTrue(DwdWarnings.at(zip, Place.MUNICH, warning.expires).isEmpty())
        assertTrue(DwdWarnings.at(zip, Place("Excluded", 48.55, 11.15), now).isEmpty())
        assertTrue(DwdWarnings.at(zip, Place("Berlin", 52.52, 13.405), now).isEmpty())
        assertEquals(1, DwdWarnings.at(zip, Place("Boundary", 48.0, 11.5), now).size)
    }

    @Test
    fun ignoresTestMessagesAndRejectsDocumentTypes() {
        assertTrue(
            DwdWarnings.at(archive(xml.replace("Actual", "Test")), Place.MUNICH, now).isEmpty()
        )
        assertThrows(IllegalArgumentException::class.java) {
            DwdWarnings.at(archive("<!DOCTYPE alert>" + xml), Place.MUNICH, now)
        }
    }
}
