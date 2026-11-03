package app.drizziq

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun write_overlappingSameTargetWritersUseUniqueTempsAndLeaveOneCompletePayload() {
        val target = temp.newFile("forecast.kmz").apply { writeBytes(byteArrayOf(0)) }
        val payloads =
            (0 until 8).map { index ->
                ByteArray(64 * 1024) { ((it + index) % 251).toByte() }
            }
        val temps = Collections.synchronizedList(mutableListOf<java.io.File>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val barrier = CyclicBarrier(payloads.size)
        val threads = payloads.map { payload ->
            Thread {
                try {
                    AtomicFiles.write(target) { part ->
                        temps += part
                        barrier.await(10, TimeUnit.SECONDS)
                        part.writeBytes(payload)
                    }
                } catch (error: Throwable) {
                    errors += error
                }
            }
                .apply { start() }
        }
        threads.forEach { it.join(20000) }

        assertTrue("writer failures: $errors", errors.isEmpty())
        assertEquals(payloads.size, temps.toSet().size)
        val written = target.readBytes()
        assertTrue(
            "target contains a partial/mixed payload",
            payloads.any { it.contentEquals(written) },
        )
        assertEquals(
            "temporary files were left behind",
            emptyList<java.io.File>(),
            target.parentFile!!.listFiles()!!.filter { it.name.endsWith(".part") },
        )
    }

    @Test
    fun write_failedFillRetainsPreviousContentsAndRemovesItsTemp() {
        val target = temp.newFile("radar.json").apply { writeBytes("old".toByteArray()) }

        assertThrows(IOException::class.java) {
            AtomicFiles.write(target) { part ->
                part.writeBytes("partial".toByteArray())
                throw IOException("disk full")
            }
        }

        assertEquals("old", target.readText())
        assertEquals(
            emptyList<java.io.File>(),
            target.parentFile!!.listFiles()!!.filter { it.name.endsWith(".part") },
        )
    }

    @Test
    fun write_successfulReplacementReplacesOldContents() {
        val target = temp.newFile("tile.png").apply { writeText("old") }

        AtomicFiles.write(target, "complete new payload".toByteArray())

        assertEquals("complete new payload", target.readText())
        assertEquals(
            emptyList<java.io.File>(),
            target.parentFile!!.listFiles()!!.filter { it.name.endsWith(".part") },
        )
    }

    @Test
    fun cleanup_abandonedTempsOnlyAfterTheAgeBoundary() {
        val now = 1_800_000_000_000L
        val directory = temp.newFolder("cache")
        val justOver =
            java.io.File(directory, "a.png.part").apply {
                writeText("stale")
                setLastModified(now - CachePolicy.ABANDONED_TEMP_MS - 1)
            }
        val exactly =
            java.io.File(directory, "b.png.part").apply {
                writeText("boundary")
                setLastModified(now - CachePolicy.ABANDONED_TEMP_MS)
            }
        val recent =
            java.io.File(directory, "c.png.part").apply {
                writeText("recent")
                setLastModified(now - 1)
            }
        val data =
            java.io.File(directory, "d.png").apply {
                writeText("data")
                setLastModified(now - CachePolicy.ABANDONED_TEMP_MS - 1)
            }

        AtomicFiles.cleanup(directory, now)

        assertFalse(justOver.exists())
        assertTrue(exactly.exists())
        assertTrue(recent.exists())
        assertTrue(data.exists())
    }
}
