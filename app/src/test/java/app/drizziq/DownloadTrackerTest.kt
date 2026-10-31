package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class DownloadTrackerTest {
    @Test
    fun overlappingDownloads_completeOutOfOrder_stayActiveUntilLastCompletion() {
        val tracker = DownloadTracker()
        val states = mutableListOf<Boolean>()
        val listener = tracker.observe { states += it }
        assertEquals(listOf(false), states)
        val a = tracker.begin()
        val b = tracker.begin()
        assertTrue(tracker.active)
        a.cancel()
        a.cancel()
        assertTrue(tracker.active)
        b.cancel()
        assertFalse(tracker.active)
        assertEquals(listOf(false, true, false), states)
        listener.cancel()
        tracker.begin().cancel()
        assertEquals(listOf(false, true, false), states)
    }

    @Test
    fun failedOrCancelledTransfer_cleansUp_andObserverFailureDoesNotBreakDownloads() {
        val tracker = DownloadTracker()
        tracker.observe { throw IllegalStateException() }
        assertThrows(java.io.IOException::class.java) {
            tracker.track {
                assertTrue(tracker.active)
                throw java.io.IOException()
            }
        }
        assertFalse(tracker.active)
        assertEquals(42, tracker.track { 42 })
        assertFalse(tracker.active)
    }

    @Test
    fun newObserverDuringTransfer_seesActiveAndCanUnsubscribeIndependently() {
        val tracker = DownloadTracker()
        val lease = tracker.begin()
        val a = mutableListOf<Boolean>()
        val b = mutableListOf<Boolean>()
        val first = tracker.observe { a += it }
        tracker.observe { b += it }
        first.cancel()
        lease.cancel()
        assertEquals(listOf(true), a)
        assertEquals(listOf(true, false), b)
    }
}
