package app.drizziq

import org.junit.Assert.assertEquals
import org.junit.Test

class RadarSequenceTest {
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
