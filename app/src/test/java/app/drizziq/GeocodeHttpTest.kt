package app.drizziq

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class GeocodeHttpTest {
    private class Connection(
        url: URL,
        val code: Int = 200,
        val bytes: ByteArray = "{}".toByteArray(),
        val fields: Map<String, String> = emptyMap(),
    ) : HttpURLConnection(url) {
        var disconnected = false

        override fun connect() {}

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = code

        override fun getInputStream() = ByteArrayInputStream(bytes)

        override fun getHeaderField(name: String) =
            fields.entries.firstOrNull { it.key.equals(name, true) }?.value

        override fun getContentEncoding() = getHeaderField("Content-Encoding")
    }

    @Test
    fun fetch_configuresTimeoutsUserAgentAndNoImplicitRedirects_andClosesConnection() {
        lateinit var connection: Connection
        val http = GeocodeHttp { url -> Connection(url).also { connection = it } }
        assertEquals(200, http.fetch(Geocoding.url("Berlin")).status)
        assertEquals(10000, connection.connectTimeout)
        assertEquals(20000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.getRequestProperty("User-Agent").startsWith("Drizziq/"))
        assertTrue(connection.disconnected)
    }

    @Test
    fun redirects_recheckHostPathAndBoundEveryHop() {
        var count = 0
        val redirect = GeocodeHttp { url ->
            count++
            Connection(url, 302, fields = mapOf("Location" to "https://evil.test/v1/search"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            redirect.fetch(Geocoding.url("Berlin"))
        }
        assertEquals(1, count)
        count = 0
        val loop = GeocodeHttp { url ->
            count++
            Connection(url, 302, fields = mapOf("Location" to "/v1/search?name=Berlin"))
        }
        assertThrows(IllegalArgumentException::class.java) { loop.fetch(Geocoding.url("Berlin")) }
        assertEquals(4, count)
    }

    @Test
    fun decodedResponseLimit_appliesAfterGzipDecompression_andStatusRetainsRetryAfter() {
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(ByteArray(262145)) }
        lateinit var connection: Connection
        val http = GeocodeHttp { url ->
            Connection(url, bytes = out.toByteArray(), fields = mapOf("Content-Encoding" to "gzip"))
                .also { connection = it }
        }
        assertThrows(IllegalArgumentException::class.java) { http.fetch(Geocoding.url("Berlin")) }
        assertTrue(connection.disconnected)
        val limited = GeocodeHttp { url ->
            Connection(url, 429, fields = mapOf("Retry-After" to "12"))
        }
        assertEquals("12", limited.fetch(Geocoding.url("Berlin")).retryAfter)
    }
}
