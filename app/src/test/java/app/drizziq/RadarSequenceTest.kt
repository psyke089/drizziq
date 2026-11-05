package app.drizziq

import org.junit.Assert.assertEquals
import org.junit.Test

class RadarSequenceTest {
    @Test
    fun position_keepsTheSameTimeScaleAsFramesArrive() {
        val issue = 1_700_000_000_000L
        val grid = RadarGrid(1, 1, 0.0, 0.0, 1.0, 1.0)
        val current = RadarFrame(issue, issue, 0, grid, "current.png")
        val first = RadarSequence(listOf(current), issue, false)
        val growing = first.copy(frames = listOf(current, current.copy(time = issue + 300_000)))

        assertEquals(1f / 3, first.position(issue), .00001f)
        assertEquals(first.position(issue), growing.position(issue), .00001f)
        assertEquals(0f, growing.position(issue - 3_600_000), .00001f)
        assertEquals(1f, growing.position(issue + 7_200_000), .00001f)
    }

    @Test
    fun nextRadarFrame_advancesAndWrapsThePlaybackLoop() {
        assertEquals(1, nextRadarFrame(0, 3))
        assertEquals(2, nextRadarFrame(1, 3))
        assertEquals(0, nextRadarFrame(2, 3))
    }

    @Test
    fun nextRadarFrame_singleOrEmptyTimelineStaysAtTheFirstFrame() {
        assertEquals(0, nextRadarFrame(0, 1))
        assertEquals(0, nextRadarFrame(0, 0))
    }

    @Test
    fun nextRadarFrame_outOfRangePositionRestartsAtTheFirstFrame() {
        assertEquals(0, nextRadarFrame(-1, 4))
        assertEquals(0, nextRadarFrame(9, 4))
    }
}
