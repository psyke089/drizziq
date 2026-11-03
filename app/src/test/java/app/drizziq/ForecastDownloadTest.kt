package app.drizziq

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ForecastDownloadTest {
    private val point = Station("10865", "Munich", 48.138108, 11.574726, 515.0)

    private val fixture: ByteArray by lazy {
        javaClass.getResourceAsStream("/munich-20261022.kmz")!!.use { it.readBytes() }
    }

    private val issue = Times.parse("2026-10-22T15:00:00.000Z")

    @Test
    fun latest_successfulDownloadIsRetainedWhenCacheWriteFails() {
        var writes = 0
        val result =
            ForecastDownload.latest(
                point,
                download = { fixture },
                cache = {
                    writes++
                    throw IOException("cache directory is read-only")
                },
            )

        assertEquals(1, writes)
        assertEquals("10865", result.station.id)
        assertEquals(issue, result.issue)
        assertEquals(Times.parse("2026-10-22T16:00:00Z"), result.hours.first().time)
        assertEquals(283.75, result.hours.first().get("TTT")!!, 0.0)
        assertEquals(3.09, result.hours.first().get("FF")!!, 0.0)
    }

    @Test
    fun historical_successfulDownloadIsRetainedWhenCacheWriteFails() {
        var writes = 0
        val result =
            ForecastDownload.historical(
                point,
                issue,
                download = { fixture },
                cache = {
                    writes++
                    throw IOException("cache directory is full")
                },
            )

        assertEquals(1, writes)
        assertEquals("10865", result.station.id)
        assertEquals(issue, result.issue)
        assertEquals(Times.parse("2026-10-22T16:00:00Z"), result.hours.first().time)
        assertEquals(283.75, result.hours.first().get("TTT")!!, 0.0)
        assertEquals(3.09, result.hours.first().get("FF")!!, 0.0)
    }

    @Test
    fun downloadParseAndValidationFailuresStillPropagateWithoutCaching() {
        var writes = 0
        val cache: (ByteArray) -> Unit = {
            writes++
            throw IOException("must not be reached")
        }

        assertThrows(IOException::class.java) {
            ForecastDownload.latest(point, { throw IOException("offline") }, cache)
        }
        assertThrows(Exception::class.java) {
            ForecastDownload.latest(point, { byteArrayOf(1, 2, 3) }, cache)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForecastDownload.latest(point.copy(id = "OTHER"), { fixture }, cache)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForecastDownload.historical(point, issue + 1, { fixture }, cache)
        }
        assertEquals(0, writes)
    }
}
