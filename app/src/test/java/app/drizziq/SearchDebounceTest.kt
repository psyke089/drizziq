package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class SearchDebounceTest {
    private class Scheduler {
        data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)

        var now = 0L
        val tasks = mutableListOf<Task>()

        fun schedule(delay: Long, action: () -> Unit): Cancel {
            val task = Task(now + delay, action)
            tasks += task
            return Cancel { task.cancelled = true }
        }

        fun advance(milliseconds: Long) {
            now += milliseconds
            val due = tasks.filter { it.at <= now }.toList()
            tasks.removeAll(due)
            due.filterNot { it.cancelled }.forEach { it.action() }
        }
    }

    @Test
    fun typingUsesCacheButKeyboardExplicitlyForcesRefresh() {
        val scheduler = Scheduler()
        val intents = mutableListOf<Pair<String, Boolean>>()
        val debounce =
            SearchDebounce(scheduler::schedule, {}) { query, force ->
                intents += query to force
            }
        debounce.edited("Berlin")
        scheduler.advance(400)
        debounce.immediate("Berlin")
        assertEquals(listOf("Berlin" to false, "Berlin" to true), intents)
    }

    @Test
    fun editing_waits400MillisecondsAndCancelsActiveWorkImmediately() {
        val scheduler = Scheduler()
        val submitted = mutableListOf<String>()
        var cancelled = 0
        val debounce =
            SearchDebounce(
                scheduler::schedule,
                { cancelled++ },
                { query, _ ->
                    submitted.add(query)
                    Unit
                },
            )
        debounce.edited("Munich")
        assertEquals(1, cancelled)
        scheduler.advance(399)
        assertEquals(emptyList<String>(), submitted)
        scheduler.advance(1)
        assertEquals(listOf("Munich"), submitted)
        scheduler.advance(4000)
        assertEquals(listOf("Munich"), submitted)
    }

    @Test
    fun rapidEditsInvalidQueriesAndDismissal_neverPublishObsoleteSubmission() {
        val scheduler = Scheduler()
        val submitted = mutableListOf<String>()
        val debounce =
            SearchDebounce(
                scheduler::schedule,
                {},
                { query, _ ->
                    submitted.add(query)
                    Unit
                },
            )
        debounce.edited("Munich")
        val obsolete = scheduler.tasks.single().action
        scheduler.advance(399)
        debounce.edited("Berlin")
        obsolete()
        scheduler.advance(399)
        assertEquals(emptyList<String>(), submitted)
        scheduler.advance(1)
        assertEquals(listOf("Berlin"), submitted)
        for (query in listOf("", "M", "x".repeat(121))) {
            debounce.edited(query)
            scheduler.advance(400)
        }
        assertEquals(listOf("Berlin"), submitted)
        debounce.edited("Hamburg")
        debounce.cancel()
        scheduler.tasks.forEach { it.action() }
        assertEquals(listOf("Berlin"), submitted)
    }

    @Test
    fun imeSearch_submitsImmediatelyWithoutDuplicateAndAllowsExplicitRetry() {
        val scheduler = Scheduler()
        val submitted = mutableListOf<String>()
        val debounce =
            SearchDebounce(
                scheduler::schedule,
                {},
                { query, _ ->
                    submitted.add(query)
                    Unit
                },
            )
        debounce.edited("Munich")
        debounce.immediate("Munich")
        scheduler.advance(400)
        assertEquals(listOf("Munich"), submitted)
        debounce.immediate("Munich")
        assertEquals(listOf("Munich", "Munich"), submitted)
    }
}
