package app.drizziq

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
 * Downloads and publishes ready frames, preserving their source issue rather than just validity.
 */
internal class RadarRefresh(
    private val latest: (RadarSnapshot?, (Long, Long?) -> Unit) -> HttpReply,
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
        val reply =
            latest(cached) { bytes, total ->
                checkRunning()
                report(status.copy(bytes = bytes, totalBytes = total))
            }
        checkRunning()
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
        val issue: Long
        val downloadedAt: Long
        val etag: String?
        val modified: String?
        when (reply) {
            is HttpReply.Ok -> {
                val entries = mutableListOf<Tar.Entry>()
                Tar.read(reply.bytes.inputStream()) { if (it.name.endsWith("-hd5")) entries += it }
                val members = entries.map { RadarMember.parse(it.name) }
                require(members.any { it.lead == 0 } && members.any { it.lead > 0 }) {
                    "DWD radar archive has no complete timeline"
                }
                issue = members.first().issue
                require(
                    members.all { it.issue == issue } && members.distinct().size == members.size
                ) {
                    "Mixed or duplicate radar issue times"
                }
                var ready = members.count { reusable[it]?.time == it.time }
                report(
                    status.copy(
                        phase = RadarPhase.PROCESSING,
                        bytes = reply.bytes.size.toLong(),
                        totalBytes = reply.bytes.size.toLong(),
                        totalFrames = entries.size,
                        processed = ready,
                    )
                )
                for (entry in entries.sortedBy { RadarMember.parse(it.name).lead }) {
                    val member = RadarMember.parse(entry.name)
                    frames += frame(entry)
                    if (reusable[member]?.time != member.time) ready++
                    publishReady(issue)
                    report(status.copy(processed = ready))
                }
                downloadedAt = now()
                etag = reply.etag
                modified = reply.lastModified
            }
            is HttpReply.NotModified -> {
                val saved = requireNotNull(cached) { "DWD returned 304 without cached radar" }
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
