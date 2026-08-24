package app.krail.bff.router

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceCalendarTest {

    private val weekdays = ServiceCalendar(
        weekdayMask = 0b0011111, // Mon-Fri
        startDate = 20260301,
        endDate = 20260331,
        addedDates = intArrayOf(20260404),
        removedDates = intArrayOf(20260309),
    )

    @Test
    fun `active on weekday inside range`() {
        assertTrue(weekdays.activeOn(LocalDate.of(2026, 3, 2))) // Monday
        assertTrue(weekdays.activeOn(LocalDate.of(2026, 3, 6))) // Friday
    }

    @Test
    fun `inactive on weekend`() {
        assertFalse(weekdays.activeOn(LocalDate.of(2026, 3, 7))) // Saturday
        assertFalse(weekdays.activeOn(LocalDate.of(2026, 3, 8))) // Sunday
    }

    @Test
    fun `inactive outside date range`() {
        assertFalse(weekdays.activeOn(LocalDate.of(2026, 2, 27))) // Friday, before start
        assertFalse(weekdays.activeOn(LocalDate.of(2026, 4, 1))) // Wednesday, after end
    }

    @Test
    fun `calendar_dates removal wins over weekday mask`() {
        assertFalse(weekdays.activeOn(LocalDate.of(2026, 3, 9))) // removed Monday
    }

    @Test
    fun `calendar_dates addition wins outside range`() {
        assertTrue(weekdays.activeOn(LocalDate.of(2026, 4, 4))) // added Saturday, past endDate
    }

    @Test
    fun `calendar_dates-only service has empty base calendar`() {
        val svc = ServiceCalendar(0, Int.MAX_VALUE, Int.MIN_VALUE, intArrayOf(20260315), IntArray(0))
        assertTrue(svc.activeOn(LocalDate.of(2026, 3, 15)))
        assertFalse(svc.activeOn(LocalDate.of(2026, 3, 16)))
    }
}
