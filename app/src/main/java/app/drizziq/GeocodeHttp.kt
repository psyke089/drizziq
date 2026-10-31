package app.drizziq

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/** Search-only HTTPS transport: every redirect must remain on the exact geocoding endpoint. */
class GeocodeHttp(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : GeocodeTransport {
    private val connection = AtomicReference<HttpURLConnection?>()

    override fun fetch(address: String): GeocodeReply {
        var url = URL(address)
        repeat(4) {
            require(Geocoding.allowed(url)) { "Unapproved geocoding endpoint" }
            val c = open(url)
            connection.set(c)
            try {
                c.connectTimeout = 10000
                c.readTimeout = 20000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", Http.USER_AGENT)
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Accept-Encoding", "gzip")
                val status = c.responseCode
                if (status in 300..399) {
                    url = URL(url, c.getHeaderField("Location") ?: error("Missing redirect target"))
                } else {
                    val retry = c.getHeaderField("Retry-After")
                    if (status != 200) return GeocodeReply(status, byteArrayOf(), retry)
                    val raw = c.inputStream
                    val decoded =
                        if (c.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
                    val bytes = decoded.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(out.size() + n <= Geocoding.MAX_BYTES) {
                                "Geocoding response too large"
                            }
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    return GeocodeReply(status, bytes, retry)
                }
            } finally {
                connection.compareAndSet(c, null)
                c.disconnect()
            }
        }
        throw IllegalArgumentException("Too many geocoding redirects")
    }

    fun cancel() {
        connection.getAndSet(null)?.disconnect()
    }
}
