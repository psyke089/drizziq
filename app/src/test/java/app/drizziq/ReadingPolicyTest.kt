package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class ReadingPolicyTest {
    @Test
    fun automaticRefresh_usesReceiptIntervalAndRetriesMissingOrFailedData() {
        val live = Reading("forecast", Freshness.LIVE, 100L)
        assertFalse(live.needsRefresh(199, 100))
        assertTrue(live.needsRefresh(200, 100))
        assertTrue(live.needsRefresh(99, 100))
        assertTrue(Reading<String>().needsRefresh(100, 100))
        assertTrue(live.failed("failed").needsRefresh(101, 100))
        assertFalse(live.copy(refreshing = true).needsRefresh(500, 100))
    }

    @Test
    fun offlineMessage_distinguishesEmptyFromSavedPayloadWithoutChangingReceipt() {
        val empty = Reading<String>().offline("forecast")
        assertEquals(Freshness.ERROR, empty.state)
        assertEquals("Offline · no saved forecast", empty.message)
        val saved = Reading("frames", Freshness.LIVE, 42L).offline("radar")
        assertEquals("Offline · showing saved radar", saved.message)
        assertEquals(42L, saved.fetched)
        assertEquals(Freshness.STALE, saved.state)
    }

    @Test
    fun issueFreshness_atExpiryBoundary_remainsLiveUntilNextMillisecond() {
        val issue = 100_000L
        assertEquals(Freshness.LIVE, issueFreshness(issue, issue + 1_000, 1_000))
        assertEquals(Freshness.STALE, issueFreshness(issue, issue + 1_001, 1_000))
    }

    @Test
    fun issueFreshness_offlineOrIncompleteHistory_isStaleEvenWhenRecent() {
        assertEquals(Freshness.STALE, issueFreshness(100, 101, 1_000, online = false))
        assertEquals(Freshness.STALE, issueFreshness(100, 101, 1_000, complete = false))
    }

    @Test
    fun failed_withSuccessfulData_retainsPayloadAndReceivedTime() {
        val payload = listOf("cached forecast")
        val previous = Reading(payload, Freshness.LIVE, 123L, refreshing = true)
        val failed = previous.failed("Refresh failed")
        assertSame(payload, failed.data)
        assertEquals(123L, failed.fetched)
        assertEquals(Freshness.STALE, failed.state)
        assertFalse(failed.refreshing)
        assertEquals("Refresh failed", failed.message)
    }

    @Test
    fun failed_withoutSuccessfulData_isError() {
        val failed = Reading<String>(refreshing = true).failed("Offline")
        assertNull(failed.data)
        assertNull(failed.fetched)
        assertEquals(Freshness.ERROR, failed.state)
        assertFalse(failed.refreshing)
    }

    @Test
    fun aged_oldIssue_marksCacheStaleWithoutDiscardingData() {
        val previous = Reading("forecast", Freshness.CACHED, 90L, refreshing = true)
        val aged = previous.aged(100, 1_101, 1_000, "Old issue")
        assertEquals(Freshness.STALE, aged.state)
        assertEquals("forecast", aged.data)
        assertEquals(90L, aged.fetched)
        assertTrue(aged.refreshing)
        assertEquals("Old issue", aged.message)
    }

    @Test
    fun aged_recentOrMissingIssue_preservesErrorAndRefreshState() {
        val previous = Reading<String>(state = Freshness.ERROR, message = "Network failed")
        assertSame(previous, previous.aged(null, 1_101, 1_000, "Old issue"))
        assertSame(previous, previous.aged(100, 1_100, 1_000, "Old issue"))
    }

    @Test
    fun requestGeneration_switchingPlaces_rejectsObsoleteResponse() {
        val requests = RequestGeneration()
        val munich = requests.next()
        assertTrue(requests.isCurrent(munich))
        val berlin = requests.next()
        assertFalse(requests.isCurrent(munich))
        assertTrue(requests.isCurrent(berlin))
    }
}
