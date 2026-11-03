package app.drizziq

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class HttpConditionalTest {
    private val tile = "https://tile.openstreetmap.org/7/64/42.png"

    private class Connection(
        url: URL,
        private val code: Int = 200,
        private val bytes: ByteArray = "tile".toByteArray(),
        private val fields: Map<String, String> = emptyMap(),
    ) : HttpURLConnection(url) {
        var disconnected = false
        var bodyRead = false

        override fun connect() {}

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = code

        override fun getInputStream() = ByteArrayInputStream(bytes).also { bodyRead = true }

        override fun getHeaderField(name: String) =
            fields.entries.firstOrNull { it.key.equals(name, true) }?.value
    }

    @Test
    fun conditionalGet_sendsBothValidatorsAndReturnsFreshMetadata() {
        lateinit var made: Connection
        val reply =
            Http.conditional(
                tile,
                1500000,
                "\"v1\"",
                "Mon, 02 Nov 2026 10:00:00 GMT",
            ) { url ->
                Connection(
                        url,
                        bytes = "fresh".toByteArray(),
                        fields =
                            mapOf(
                                "ETag" to "\"v2\"",
                                "Last-Modified" to "Tue, 03 Nov 2026 10:00:00 GMT",
                            ),
                    )
                    .also { made = it }
            }

        assertArrayEquals("fresh".toByteArray(), (reply as HttpReply.Ok).bytes)
        assertEquals("\"v2\"", reply.etag)
        assertEquals("Tue, 03 Nov 2026 10:00:00 GMT", reply.lastModified)
        assertEquals("\"v1\"", made.getRequestProperty("If-None-Match"))
        assertEquals("Mon, 02 Nov 2026 10:00:00 GMT", made.getRequestProperty("If-Modified-Since"))
        assertTrue(made.getRequestProperty("User-Agent").startsWith("Drizziq/"))
        assertTrue(made.disconnected)
    }

    @Test
    fun notModified_doesNotReadTheBodyAndKeepsResponseValidators() {
        lateinit var made: Connection
        val reply =
            Http.conditional(tile, 1500000, "\"v1\"", "old") { url ->
                Connection(url, code = 304, fields = mapOf("ETag" to "\"v2\"")).also { made = it }
            }

        assertTrue(reply is HttpReply.NotModified)
        assertEquals("\"v2\"", (reply as HttpReply.NotModified).etag)
        assertEquals("old", reply.lastModified)
        assertFalse(made.bodyRead)
        assertTrue(made.disconnected)
    }

    @Test
    fun absentValidators_areNotSent() {
        lateinit var made: Connection
        Http.conditional(tile) { url -> Connection(url).also { made = it } }

        assertNull(made.getRequestProperty("If-None-Match"))
        assertNull(made.getRequestProperty("If-Modified-Since"))
    }

    @Test
    fun redirects_areRecheckedAgainstTheAllowlistAndBounded() {
        var count = 0
        assertThrows(IllegalArgumentException::class.java) {
            Http.conditional(tile) { url ->
                count++
                Connection(url, code = 302, fields = mapOf("Location" to "https://evil.test/t.png"))
            }
        }
        assertEquals(1, count)

        count = 0
        assertThrows(IllegalArgumentException::class.java) {
            Http.conditional(tile) { url ->
                count++
                Connection(url, code = 302, fields = mapOf("Location" to "/7/64/42.png"))
            }
        }
        assertEquals(4, count)
    }

    @Test
    fun sizeLimitIsEnforcedAndNonSuccessStatusesFail() {
        assertThrows(IllegalArgumentException::class.java) {
            Http.conditional(tile, limit = 4) { url ->
                Connection(url, bytes = ByteArray(5) { 1 })
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            Http.conditional(tile) { url -> Connection(url, code = 500) }
        }
    }

    @Test
    fun allowlist_stillRejectsPlainHttpAndForeignHosts() {
        assertTrue(Http.allowed(URL(tile)))
        assertFalse(Http.allowed(URL("http://tile.openstreetmap.org/7/64/42.png")))
        assertFalse(Http.allowed(URL("https://tiles.example/7/64/42.png")))
    }
}
