package app.krail.bff.client.nsw

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-day call budget for NSW upstream traffic.
 *
 * Counter resets at midnight in [zone]. Used as the BFF's own safety net so a
 * misbehaving cohort can't burn the whole NSW quota in one day.
 *
 * Returns false from [tryAcquire] once the limit is exceeded; callers should
 * convert that into a 503.
 */
class NswDailyBudget(
    val limit: Long,
    private val zone: ZoneId = ZoneId.of("Australia/Sydney"),
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class State(val day: LocalDate, val count: Long)

    private val state = AtomicReference(State(today(), 0L))

    fun tryAcquire(): Boolean {
        if (limit <= 0) return true // disabled
        while (true) {
            val current = state.get()
            val today = today()
            val newCount = if (today == current.day) current.count + 1L else 1L
            val next = State(today, newCount)
            if (state.compareAndSet(current, next)) {
                return newCount <= limit
            }
        }
    }

    fun currentCount(): Long = state.get().count
    fun currentDay(): LocalDate = state.get().day

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), zone)
}
