package app.drizziq

import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RadarRefreshTest {
    private val issue = Times.parse("2026-11-05T14:00:00Z")
    private val grid = RadarGrid(1100, 1200, 0.0, 0.0, 1000.0, 1000.0)

    private fun entry(issue: Long = this.issue, lead: Int = 0) =
        Tar.Entry(
            "composite_rv_${Times.format(issue, "yyyyMMdd_HHmm")}_${lead.toString().padStart(3, '0')}-hd5",
            byteArrayOf(1, 2),
        )

    private fun frame(issue: Long = this.issue, lead: Int = 0) =
        RadarFrame(issue + lead * 60_000L, issue, lead, grid, "rv-$issue-$lead.png")

    private fun archive(vararg entries: Tar.Entry): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in entries) {
            val header = ByteArray(512)
            entry.name.toByteArray().copyInto(header)
            entry.bytes.size.toString(8).padStart(11, '0').toByteArray().copyInto(header, 124)
            out.write(header)
            out.write(entry.bytes)
            out.write(ByteArray((512 - entry.bytes.size % 512) % 512))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    @Test
    fun damagedLatestCache_doesNotSendValidatorsAndStillReusesValidFrames() {
        val saved =
            RadarSnapshot(
                RadarSequence(listOf(frame()), issue, false),
                100L,
                "etag",
                "modified",
                latestComplete = false,
            )
        val decoded = mutableListOf<Int>()
        val result =
            RadarRefresh(
                    latest = { cached, _ ->
                        assertNull(cached)
                        HttpReply.Ok(archive(entry(), entry(lead = 5)), "repaired", null)
                    },
                    observed = { error("history unavailable") },
                    decode = { value ->
                        val member = RadarMember.parse(value.name)
                        decoded += member.lead
                        frame(member.issue, member.lead)
                    },
                    now = { 200L },
                )
                .load(saved)
        assertEquals(listOf(5), decoded)
        assertTrue(result.latestComplete)
        assertEquals(200L, result.downloadedAt)
    }

    @Test
    fun currentImageIsPublishedBeforeForecastDecodeAndHistoryRequests() {
        val events = mutableListOf<String>()
        val statuses = mutableListOf<RadarProgress>()
        val result =
            RadarRefresh(
                    latest = { _, progress ->
                        progress(512, 2048)
                        HttpReply.Ok(archive(entry(lead = 5), entry()), "v1", "modified")
                    },
                    observed = { time ->
                        events += "history"
                        entry(time)
                    },
                    decode = { value ->
                        val member = RadarMember.parse(value.name)
                        events += "decode:${member.lead}"
                        frame(member.issue, member.lead)
                    },
                    now = { 1234L },
                    progress = statuses::add,
                    publish = { events += "publish:${it.frames.size}" },
                )
                .load(null)

        assertEquals(
            listOf("decode:0", "publish:1", "decode:5", "publish:2", "history"),
            events.take(5),
        )
        assertEquals(14, result.sequence.frames.size)
        assertTrue(result.sequence.historyComplete)
        assertEquals(1234L, result.downloadedAt)
        assertEquals("v1", result.etag)
        assertTrue(
            statuses.any {
                it.phase == RadarPhase.DOWNLOAD && it.bytes == 512L && it.totalBytes == 2048L
            }
        )
        assertEquals(RadarPhase.COMPLETE, statuses.last().phase)
        assertEquals(12, statuses.last().history)
    }

    @Test
    fun unchangedIssueReusesFramesWithoutDownloadingHistoryOrDecoding() {
        val saved =
            RadarSnapshot(
                RadarSequence(
                    (-12..0).map { frame(issue + it * 300_000) } + frame(lead = 5),
                    issue,
                    true,
                ),
                100L,
                "etag",
                "modified",
            )
        val result =
            RadarRefresh(
                    latest = { cached, _ ->
                        assertEquals(saved, cached)
                        HttpReply.NotModified("etag", "modified")
                    },
                    observed = { error("Cached history must not be downloaded") },
                    decode = { error("Cached images must not be decoded") },
                    now = { error("A 304 must not advance the successful-download timestamp") },
                )
                .load(saved)

        assertEquals(saved, result)
    }

    @Test
    fun newIssueReusesThePreviousObservationButNeverAnOldForecastForTheSameTime() {
        val oldIssue = issue - 300_000
        val savedFrames = (-12..0).map { frame(oldIssue + it * 300_000) } + frame(oldIssue, 5)
        val saved = RadarSnapshot(RadarSequence(savedFrames, oldIssue, true), 100)
        val decoded = mutableListOf<RadarMember>()
        val result =
            RadarRefresh(
                    latest = { _, _ ->
                        HttpReply.Ok(archive(entry(), entry(lead = 5)), null, null)
                    },
                    observed = { error("All observed history is already cached") },
                    decode = { value ->
                        RadarMember.parse(value.name).let {
                            decoded += it
                            frame(it.issue, it.lead)
                        }
                    },
                    now = { 200L },
                )
                .load(saved)

        assertEquals(listOf(RadarMember(issue, 0), RadarMember(issue, 5)), decoded)
        assertEquals(14, result.sequence.frames.size)
        assertTrue(result.sequence.frames.any { it.issue == oldIssue && it.lead == 0 })
    }

    @Test
    fun sameArchiveReusesExistingPngsBeforeRasterDecoding() {
        val frames = (-12..0).map { frame(issue + it * 300_000) } + frame(lead = 5)
        val saved = RadarSnapshot(RadarSequence(frames, issue, true), 100)
        val published = mutableListOf<RadarSequence>()
        val result =
            RadarRefresh(
                    latest = { _, _ ->
                        HttpReply.Ok(archive(entry(), entry(lead = 5)), "v2", null)
                    },
                    observed = { error("Unnecessary history request") },
                    decode = { error("Unnecessary raster decode") },
                    now = { 200L },
                    publish = published::add,
                )
                .load(saved)

        assertEquals(saved.sequence, result.sequence)
        assertEquals(200L, result.downloadedAt)
        assertTrue(
            "Already available frames must not disappear during a refresh",
            published.all { it.frames.size == saved.sequence.frames.size },
        )
    }

    @Test
    fun historyFailuresLeaveGapsAndOtherObservationsStillArrive() {
        val result =
            RadarRefresh(
                    latest = { _, _ ->
                        HttpReply.Ok(archive(entry(), entry(lead = 5)), null, null)
                    },
                    observed = { time ->
                        if (time == issue - 300_000) error("Missing archive") else entry(time)
                    },
                    decode = { RadarMember.parse(it.name).let { frame(it.issue, it.lead) } },
                    now = { 200L },
                )
                .load(null)

        assertFalse(result.sequence.historyComplete)
        assertEquals(13, result.sequence.frames.size)
        assertFalse(result.sequence.frames.any { it.time == issue - 300_000 })
    }

    @Test
    fun unchangedIssueStillRetriesMissingHistoryAndPreservesDownloadTime() {
        val saved =
            RadarSnapshot(RadarSequence(listOf(frame(), frame(lead = 5)), issue, false), 100)
        var requests = 0
        val result =
            RadarRefresh(
                    latest = { _, _ -> HttpReply.NotModified(null, null) },
                    observed = {
                        requests++
                        entry(it)
                    },
                    decode = { RadarMember.parse(it.name).let { frame(it.issue, it.lead) } },
                    now = { error("Unchanged latest issue must retain its download time") },
                )
                .load(saved)

        assertEquals(12, requests)
        assertTrue(result.sequence.historyComplete)
        assertEquals(100L, result.downloadedAt)
    }

    @Test
    fun cancellationStopsPublicationAndHistoryWork() {
        var running = true
        val seen = mutableListOf<RadarSequence>()
        val refresh =
            RadarRefresh(
                latest = { _, _ -> HttpReply.Ok(archive(entry(), entry(lead = 5)), null, null) },
                observed = { error("Cancelled work must not download history") },
                decode = { RadarMember.parse(it.name).let { frame(it.issue, it.lead) } },
                now = { 200L },
                running = { running },
                publish = {
                    seen += it
                    running = false
                },
            )

        assertThrows(CancellationException::class.java) { refresh.load(null) }
        assertEquals(1, seen.size)
    }

    @Test
    fun decodeFailureDoesNotPublishAnUnusableFrameOrChangeSavedReceipt() {
        val saved =
            RadarSnapshot(
                RadarSequence(listOf(frame(issue - 300_000)), issue - 300_000, false),
                100,
            )
        val seen = mutableListOf<RadarSequence>()
        val refresh =
            RadarRefresh(
                latest = { _, _ -> HttpReply.Ok(archive(entry(), entry(lead = 5)), null, null) },
                observed = { error("History must not start") },
                decode = { error("Bad HDF5") },
                now = { error("Failed processing is not a successful download") },
                publish = seen::add,
            )

        assertThrows(IllegalStateException::class.java) { refresh.load(saved) }
        assertTrue(seen.isEmpty())
        assertEquals(100L, saved.downloadedAt)
    }
}
