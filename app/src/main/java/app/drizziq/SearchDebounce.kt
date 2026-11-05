package app.drizziq

/**
 * One delayed submission; lifecycle/edit cancellation also invalidates callbacks already queued.
 */
internal class SearchDebounce(
    private val schedule: (Long, () -> Unit) -> Cancel,
    private val cancelActive: () -> Unit,
    private val submit: (String, Boolean) -> Unit,
) {
    private var pending: Cancel? = null
    private var generation = 0

    fun edited(text: String) {
        cancel()
        cancelActive()
        val query =
            try {
                Geocoding.query(text)
            } catch (_: IllegalArgumentException) {
                return
            }
        val request = generation
        pending =
            schedule(400) {
                if (request == generation) {
                    pending = null
                    submit(query, false)
                }
            }
    }

    fun immediate(text: String) {
        cancel()
        cancelActive()
        submit(text, true)
    }

    fun cancel() {
        generation++
        pending?.cancel()
        pending = null
    }
}
