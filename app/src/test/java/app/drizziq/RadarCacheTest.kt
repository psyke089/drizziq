package app.drizziq

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RadarCacheTest {
    @get:Rule val temp = TemporaryFolder()

    private val issue = Times.parse("2026-10-22T20:15:00Z")
    private val grid = RadarGrid(1100, 1200, -500.0, 500.0, 1.0, 1.0)

    private fun resource(path: String): ByteArray =
        javaClass.getResourceAsStream(path)!!.use { it.readBytes() }

    private fun frame(directory: File, name: String, bytes: ByteArray): File =
        File(directory, name).apply { writeBytes(bytes) }

    private fun sequence(file: File): RadarSequence =
        RadarSequence(
            listOf(RadarFrame(issue, issue, 0, grid, file.path)),
            issue,
            historyComplete = false,
        )

    private fun writeManifest(directory: File, sequence: RadarSequence, historyComplete: Boolean) {
        File(directory, "radar.json").writeText(RadarCache.encode(sequence, historyComplete))
    }

    @Test
    fun corruptFrame_preservesValidNeighborsAndDisablesConditionalLatestUntilRepaired() {
        val directory = temp.newFolder("partial")
        val first = frame(directory, "rv-$issue-0.png", resource("/radar-1100x1200.png"))
        val next = frame(directory, "rv-$issue-5.png", resource("/radar-1100x1200.png"))
        val frames =
            listOf(
                RadarFrame(issue, issue, 0, grid, first.path),
                RadarFrame(issue + 300_000, issue, 5, grid, next.path),
            )
        RadarCache.save(directory, RadarSnapshot(RadarSequence(frames, issue, false), 42, "v1"))
        next.writeText("broken")
        val read = RadarCache.read(directory)
        assertEquals(listOf(frames.first()), read.sequence!!.frames)
        assertFalse(read.sequence!!.historyComplete)
        assertFalse(read.snapshot!!.latestComplete)
        assertEquals(42L, read.snapshot!!.downloadedAt)
        assertEquals(listOf(next), read.invalid)
        read.removeInvalid()
        assertTrue(first.isFile)
        assertFalse(next.exists())
    }

    @Test
    fun manifest_rejectsInvalidTimeLeadAndGridWhileKeepingOtherValidFrames() {
        val directory = temp.newFolder("semantics")
        val png = frame(directory, "rv-$issue-0.png", resource("/radar-1100x1200.png"))
        for ((field, bad) in
            listOf(
                "time" to issue + 1,
                "lead" to 3,
                "lead" to 125,
                "dx" to 0.0,
                "dy" to -1.0,
                "left" to "NaN",
            )) {
            writeManifest(directory, sequence(png), false)
            val manifest = File(directory, "radar.json")
            val json = JSONObject(manifest.readText())
            json.getJSONArray("frames").getJSONObject(0).put(field, bad)
            manifest.writeText(json.toString())
            assertNull("$field=$bad", RadarCache.read(directory).sequence)
        }
    }

    @Test
    fun manifest_sortsFramesAndRejectsDuplicateMembers() {
        val directory = temp.newFolder("ordering")
        val png = frame(directory, "rv-$issue-0.png", resource("/radar-1100x1200.png"))
        val observed = sequence(png).frames.single()
        val future = observed.copy(time = issue + 300_000, lead = 5)
        writeManifest(directory, RadarSequence(listOf(future, observed), issue, false), false)
        assertEquals(listOf(observed, future), RadarCache.read(directory).sequence!!.frames)
        writeManifest(directory, RadarSequence(listOf(observed, observed), issue, false), false)
        assertNull(RadarCache.read(directory).sequence)
    }

    @Test
    fun saveAndRead_preservesDownloadTimeAndHttpValidatorsWithoutUsingManifestMtime() {
        val directory = temp.newFolder("receipts")
        val png = frame(directory, "rv-$issue-0.png", resource("/radar-1100x1200.png"))
        val snapshot =
            RadarSnapshot(sequence(png), 123_456L, "etag-v1", "old-date", latestComplete = false)
        RadarCache.save(directory, snapshot)
        File(directory, "radar.json").setLastModified(999_999L)

        assertEquals(snapshot, RadarCache.read(directory).snapshot)
    }

    @Test
    fun clear_removesOnlyRadarManifestImagesAndTheirTemporaryFiles() {
        val directory = temp.newFolder("clear")
        val radarNames =
            listOf(
                "radar.json",
                "radar.json.123.part",
                "rv-$issue-0.png",
                "rv-$issue-0.png.123.part",
            )
        val retained =
            listOf("forecast-10865.kmz", "forecast-10865.kmz.123.part", "tile.png", "places.json")
        for (name in radarNames + retained) File(directory, name).writeText("data")

        RadarCache.clear(directory)

        assertEquals(retained.sorted(), directory.list()!!.sorted())
    }

    @Test
    fun encodeAndRead_validVersionedFramesRoundTrip() {
        val directory = temp.newFolder("data")
        val png = frame(directory, "rv-$issue-0.png", resource("/radar-1100x1200.png"))
        val sequence = sequence(png)
        writeManifest(directory, sequence, historyComplete = false)

        val read = RadarCache.read(directory)

        assertEquals(sequence.copy(historyComplete = false), read.sequence)
        assertEquals(emptyList<File>(), read.invalid)
        val json = JSONObject(File(directory, "radar.json").readText())
        assertEquals(RadarCache.VERSION, json.getInt("version"))
        assertEquals(RadarCache.PALETTE_VERSION, json.getInt("palette"))
        assertFalse(json.getBoolean("historyComplete"))
    }

    @Test
    fun read_validationDoesNotChangeFrameReceiptTimestamps() {
        val directory = temp.newFolder("data")
        val receipt = 1_700_000_000_000L
        val png =
            frame(directory, "rv-$issue-0.png", resource("/radar-1100x1200.png")).apply {
                setLastModified(receipt)
            }
        writeManifest(directory, sequence(png), historyComplete = true)

        RadarCache.read(directory)

        assertEquals(receipt, png.lastModified())
    }

    @Test
    fun read_versionMismatchInvalidatesTheManifestAndReferencedFrames() {
        val directory = temp.newFolder("data")
        val png = frame(directory, "rv-$issue-0.png", resource("/radar-1100x1200.png"))
        writeManifest(directory, sequence(png), historyComplete = true)
        val manifest = File(directory, "radar.json")
        val json = JSONObject(manifest.readText()).put("version", RadarCache.VERSION + 1)
        manifest.writeText(json.toString())

        val read = RadarCache.read(directory)

        assertNull(read.sequence)
        assertEquals(listOf(png), read.invalid)
    }

    @Test
    fun read_paletteMismatchInvalidatesTheManifestAndReferencedFrames() {
        val directory = temp.newFolder("data")
        val png = frame(directory, "rv-$issue-0.png", resource("/radar-1100x1200.png"))
        writeManifest(directory, sequence(png), historyComplete = true)
        val manifest = File(directory, "radar.json")
        val json = JSONObject(manifest.readText()).put("palette", RadarCache.PALETTE_VERSION + 1)
        manifest.writeText(json.toString())

        val read = RadarCache.read(directory)

        assertNull(read.sequence)
        assertEquals(listOf(png), read.invalid)
    }

    @Test
    fun read_corruptPngInvalidatesTheCacheAndNamesTheBadFrame() {
        val directory = temp.newFolder("data")
        val png = frame(directory, "rv-$issue-0.png", resource("/radar-1100x1200.png"))
        writeManifest(directory, sequence(png), historyComplete = true)
        png.writeBytes(png.readBytes().let { it.copyOf(it.size - 5) })

        val read = RadarCache.read(directory)

        assertNull(read.sequence)
        assertEquals(listOf(png), read.invalid)
    }

    @Test
    fun read_wrongDimensionsInvalidateTheCacheAndNameTheBadFrame() {
        val directory = temp.newFolder("data")
        val png = frame(directory, "rv-$issue-0.png", resource("/radar-1x1.png"))
        writeManifest(directory, sequence(png), historyComplete = true)

        val read = RadarCache.read(directory)

        assertNull(read.sequence)
        assertEquals(listOf(png), read.invalid)
    }

    @Test
    fun read_missingFrameInvalidatesTheCache() {
        val directory = temp.newFolder("data")
        val missing = File(directory, "rv-$issue-0.png")
        writeManifest(directory, sequence(missing), historyComplete = true)

        val read = RadarCache.read(directory)

        assertNull(read.sequence)
        assertEquals(listOf(missing), read.invalid)
    }

    @Test
    fun read_emptyFrameListIsRejected() {
        val directory = temp.newFolder("data")
        File(directory, "radar.json")
            .writeText(
                JSONObject()
                    .put("version", RadarCache.VERSION)
                    .put("palette", RadarCache.PALETTE_VERSION)
                    .put("issue", issue)
                    .put("historyComplete", true)
                    .put("frames", org.json.JSONArray())
                    .toString()
            )

        assertNull(RadarCache.read(directory).sequence)
    }

    @Test
    fun png_dimensionsRejectsTruncatedAndCorruptedChunks() {
        val complete = resource("/radar-1100x1200.png")

        assertNull(Png.dimensions(complete.copyOf(complete.size - 1)))
        assertNull(Png.dimensions(complete.copyOf(16)))
        val flipped = complete.copyOf()
        flipped[40] = (flipped[40].toInt() xor 0xff).toByte()
        assertNull(Png.dimensions(flipped))
        assertNull(Png.dimensions("not a png".toByteArray()))
    }

    @Test
    fun read_rejectsFrameNamesOutsideTheCacheDirectory() {
        val directory = temp.newFolder("data")
        File(directory, "radar.json")
            .writeText(
                JSONObject()
                    .put("version", RadarCache.VERSION)
                    .put("palette", RadarCache.PALETTE_VERSION)
                    .put("issue", issue)
                    .put("historyComplete", true)
                    .put(
                        "frames",
                        org.json
                            .JSONArray()
                            .put(
                                JSONObject()
                                    .put("time", issue)
                                    .put("issue", issue)
                                    .put("lead", 0)
                                    .put("file", "../outside.png")
                                    .put("w", grid.width)
                                    .put("h", grid.height)
                                    .put("left", grid.left)
                                    .put("top", grid.top)
                                    .put("dx", grid.dx)
                                    .put("dy", grid.dy)
                            ),
                    )
                    .toString()
            )

        assertNull(RadarCache.read(directory).sequence)
    }

    private fun file(directory: File, name: String, bytes: ByteArray): File =
        File(directory, name).apply { writeBytes(bytes) }
}
