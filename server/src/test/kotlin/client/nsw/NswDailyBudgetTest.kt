package app.krail.bff.client.nsw

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NswDailyBudgetTest {
    @Test
    fun `tryAcquire allows up to limit and rejects over`() {
        val clock = FixedClock(Instant.parse("2026-05-08T03:00:00Z"))
        val budget = NswDailyBudget(limit = 3, zone = ZoneOffset.UTC, clock = clock)
        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())
        assertEquals(4, budget.currentCount(), "counter still increments on rejection (caller decides what to do)")
    }

    @Test
    fun `limit zero disables the budget`() {
        val budget = NswDailyBudget(limit = 0)
        repeat(100) { assertTrue(budget.tryAcquire(), "limit=0 must always allow") }
    }

    @Test
    fun `negative limit also disables`() {
        val budget = NswDailyBudget(limit = -5)
        assertTrue(budget.tryAcquire())
    }

    @Test
    fun `counter resets at midnight in the configured zone`() {
        // 23:30 Sydney on 2026-05-08 is 13:30 UTC.
        val clock = MutableClock(Instant.parse("2026-05-08T13:30:00Z"))
        val budget = NswDailyBudget(
            limit = 2,
            zone = ZoneId.of("Australia/Sydney"),
            clock = clock,
        )
        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())

        // Advance 1h → 14:30 UTC → 00:30 next day in Sydney → reset.
        clock.now = Instant.parse("2026-05-08T14:30:00Z")
        assertTrue(budget.tryAcquire(), "post-midnight Sydney call must succeed after reset")
        assertEquals(1L, budget.currentCount())
    }

    private class FixedClock(private val instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
    }

    private class MutableClock(@Volatile var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}
