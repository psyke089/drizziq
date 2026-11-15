package app.drizziq

import java.io.InputStream
import java.util.concurrent.CancellationException

internal enum class RadarPhase {
    IDLE,
    DOWNLOAD,
    PROCESSING,
    HISTORY,
    COMPLETE,
    FAILED,
    CLEARING,
}

internal data class RadarProgress(
    val phase: RadarPhase = RadarPhase.IDLE,
    val bytes: Long = 0,
    val totalBytes: Long? = null,
    val processed: Int = 0,
    val totalFrames: Int = 0,
    val history: Int = 0,
)

internal data class RadarSnapshot(
    val sequence: RadarSequence,
    val downloadedAt: Long,
    val etag: String? = null,
    val lastModified: String? = null,
    val latestComplete: Boolean = true,
)

internal data class RadarMember(val issue: Long, val lead: Int) {
    val time: Long
        get() = issue + lead * 60_000L

    companion object {
        fun parse(name: String): RadarMember {
            val match =
                Regex("composite_rv_(\\d{8})_(\\d{4})_(\\d{3})-hd5").matchEntire(name)
                    ?: error("Unexpected DWD archive entry")
            val date = match.groupValues[1]
            val hour = match.groupValues[2]
            val issue =
                Times.parse(
                    "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6)}T${hour.substring(0, 2)}:${hour.substring(2)}:00Z"
                )
            val lead = match.groupValues[3].toInt()
            require(lead in 0..120 && lead % 5 == 0) { "Unexpected DWD lead time" }
            return RadarMember(issue, lead)
        }
    }
}

/**
 * Streams the latest archive, publishing each decoded frame as soon as it arrives, then fills the
 * observed history. Frame issue times are preserved rather than just validity.
 */
internal class RadarRefresh(
    private val latest:
        (RadarSnapshot?, (Long, Long?) -> Unit, (InputStream) -> Unit) -> StreamReply,
    private val observed: (Long) -> Tar.Entry,
    private val decode: (Tar.Entry) -> RadarFrame,
    private val now: () -> Long,
    private val running: () -> Boolean = { true },
    private val progress: (RadarProgress) -> Unit = {},
    private val publish: (RadarSequence) -> Unit = {},
) {
    fun load(cached: RadarSnapshot?): RadarSnapshot {
        var status = RadarProgress(phase = RadarPhase.DOWNLOAD)
        fun report(value: RadarProgress) {
            status = value
            progress(value)
        }
        fun checkRunning() {
            if (!running()) throw CancellationException("Radar refresh cancelled")
        }
        report(status)
        val reusable =
            cached?.sequence?.frames.orEmpty().associateBy { RadarMember(it.issue, it.lead) }
        fun frame(entry: Tar.Entry): RadarFrame {
            checkRunning()
            val member = RadarMember.parse(entry.name)
            return reusable[member]?.takeIf { it.time == member.time }
                ?: decode(entry).also {
                    require(
                        it.issue == member.issue && it.lead == member.lead && it.time == member.time
                    ) {
                        "DWD frame time mismatch"
                    }
                }
        }
        val frames = mutableListOf<RadarFrame>()
        fun publishReady(issue: Long) {
            val alreadyReady =
                reusable.values.filter {
                    (it.issue == issue &&
                        it.time == issue + it.lead * 60_000L &&
                        it.lead in 0..120 &&
                        it.lead % 5 == 0) ||
                        (it.lead == 0 &&
                            it.time == it.issue &&
                            it.time in (issue - 3_600_000L) until issue &&
                            (issue - it.time) % 300_000L == 0L)
                }
            publish(
                RadarSequence(
                    (frames + alreadyReady).distinctBy { it.time }.sortedBy { it.time },
                    issue,
                    false,
                )
            )
        }
        var issue = -1L
        var downloadedAt = 0L
        var etag: String? = null
        var modified: String? = null
        val seen = mutableSetOf<RadarMember>()
        val deferred = mutableListOf<Tar.Entry>()
        var sawObservation = false
        var processed = 0
        val reply =
            latest(
                cached?.takeIf { it.latestComplete },
                { bytes, total ->
                    checkRunning()
                    report(status.copy(bytes = bytes, totalBytes = total))
                },
                { input ->
                    Tar.read(input) { entry ->
                        checkRunning()
                        val member = RadarMember.parse(entry.name)
                        if (issue < 0) issue = member.issue
                        require(member.issue == issue) { "Mixed radar issue times" }
                        require(seen.add(member)) { "Duplicate radar members" }
                        if (member.lead == 0) {
                            sawObservation = true
                            frames += frame(entry)
                            processed++
                            publishReady(issue)
                            for (waiting in deferred) {
                                frames += frame(waiting)
                                processed++
                            }
                            if (deferred.isNotEmpty()) publishReady(issue)
                            deferred.clear()
                        } else if (!sawObservation) {
                            // Keep the observation first even if the archive lists forecasts first.
                            deferred += entry
                        } else {
                            frames += frame(entry)
                            processed++
                            publishReady(issue)
                        }
                        report(status.copy(phase = RadarPhase.PROCESSING, processed = processed))
                    }
                },
            )
        checkRunning()
        when (reply) {
            is StreamReply.Ok -> {
                require(sawObservation && deferred.isEmpty()) {
                    "DWD radar archive has no observation"
                }
                require(frames.any { it.lead > 0 }) { "DWD radar archive has no forecast" }
                downloadedAt = now()
                etag = reply.etag
                modified = reply.lastModified
                report(
                    status.copy(
                        phase = RadarPhase.PROCESSING,
                        processed = processed,
                        totalFrames = processed,
                    )
                )
            }
            is StreamReply.NotModified -> {
                val saved = requireNotNull(cached) { "DWD returned 304 without cached radar" }
                require(saved.latestComplete) { "DWD returned 304 for incomplete cached radar" }
                issue = saved.sequence.issue
                frames +=
                    saved.sequence.frames.filter {
                        it.issue == issue && it.time == issue + it.lead * 60_000L
                    }
                require(frames.any { it.lead == 0 } && frames.any { it.lead > 0 }) {
                    "Incomplete cached radar"
                }
                publishReady(issue)
                report(status.copy(processed = frames.size, totalFrames = frames.size))
                downloadedAt = saved.downloadedAt
                etag = reply.etag ?: saved.etag
                modified = reply.lastModified ?: saved.lastModified
            }
        }
        var complete = true
        report(status.copy(phase = RadarPhase.HISTORY))
        for (back in 12 downTo 1) {
            checkRunning()
            val time = issue - back * 300_000L
            try {
                val saved = reusable[RadarMember(time, 0)]?.takeIf { it.time == time }
                val observedFrame = saved ?: frame(observed(time))
                require(
                    observedFrame.lead == 0 &&
                        observedFrame.issue == time &&
                        observedFrame.time == time
                )
                frames += observedFrame
                publishReady(issue)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                complete = false
            }
            report(status.copy(history = 13 - back))
        }
        checkRunning()
        val sequence = RadarSequence(frames.sortedBy { it.time }, issue, complete)
        publish(sequence)
        report(status.copy(phase = RadarPhase.COMPLETE))
        return RadarSnapshot(sequence, downloadedAt, etag, modified)
    }
}
