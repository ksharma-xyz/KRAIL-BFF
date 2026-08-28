package app.krail.bff.router

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * frequencies.txt expansion (VBB is the first ingested feed that uses it).
 * Template trip F1 A->B->C takes 10 min per hop; the base trip's own
 * stop_times (08:00 anchor) are travel-time offsets only, never a
 * schedulable trip.
 *
 * Rows: 06:00-07:00 every 20 min (exact_times absent = headway-based);
 * 09:00-09:30 every 10 min with exact_times=1 (schedule replication).
 * Two garbage rows (headway 0, end <= start) must be dropped.
 */
class FrequenciesTest {

    private companion object {
        const val TRAVEL = 10 * 60

        val source = StringGtfsSource(
            "freq",
            mapOf(
                "stops.txt" to """
                    stop_id,stop_name,stop_lat,stop_lon
                    A,Stop A,-37.80,145.00
                    B,Stop B,-37.80,145.10
                    C,Stop C,-37.80,145.20
                """.trimIndent(),
                "routes.txt" to """
                    route_id,route_short_name,route_long_name,route_type
                    RF,rf,Frequent Line,3
                """.trimIndent(),
                "trips.txt" to """
                    route_id,service_id,trip_id,trip_headsign
                    RF,ALL,F1,Loopwards
                """.trimIndent(),
                "stop_times.txt" to """
                    trip_id,arrival_time,departure_time,stop_id,stop_sequence
                    F1,08:00:00,08:00:00,A,1
                    F1,08:10:00,08:10:00,B,2
                    F1,08:20:00,08:20:00,C,3
                """.trimIndent(),
                "calendar.txt" to """
                    service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                    ALL,1,1,1,1,1,1,1,20260301,20260331
                """.trimIndent(),
                "frequencies.txt" to """
                    trip_id,start_time,end_time,headway_secs,exact_times
                    F1,06:00:00,07:00:00,1200,
                    F1,09:00:00,09:30:00,600,1
                    F1,10:00:00,11:00:00,0,
                    F1,12:00:00,12:00:00,600,1
                    NOPE,06:00:00,07:00:00,600,
                """.trimIndent(),
            ),
        )

        val model: RouterModel =
            RouterModelBuilder.build(listOf(GtfsParser.parseFeed(source)), sourceLabel = "freq-fixture")
        val router = RaptorRouter(model)
        val monday: LocalDate = LocalDate.of(2026, 3, 2)
    }

    private fun sec(h: Int, m: Int) = h * 3600 + m * 60

    private fun transit(j: Journey) = j.legs.filterIsInstance<JourneyLeg.Transit>()

    @Test
    fun `expands to concrete trips and drops the template trip`() {
        // 06:00/06:20/06:40 (three departures < 07:00) + 09:00/09:10/09:20.
        assertEquals(6, model.tripCount)
        assertTrue(model.tripIds.none { it == "F1" }, "template trip must not be schedulable")
        assertTrue(model.tripIds.all { it.startsWith("F1#") })
    }

    @Test
    fun `headway-based trips are boardable at each step with template travel times`() {
        val j = router.plan("A", "C", monday, sec(5, 50)).first()
        val leg = transit(j).single()
        assertEquals("F1#06:00:00", leg.tripId)
        assertEquals(sec(6, 0), leg.boardDepSec)
        assertEquals(sec(6, 0) + 2 * TRAVEL, j.arrSec)

        val next = router.plan("A", "C", monday, sec(6, 5)).first()
        assertEquals("F1#06:20:00", transit(next).single().tripId)
        assertEquals(sec(6, 20) + 2 * TRAVEL, next.arrSec)
    }

    @Test
    fun `no departures between frequency windows`() {
        // The template's own 08:00 stop_times must not be boardable: the next
        // service after the 06:xx window is the 09:00 exact_times block.
        val j = router.plan("A", "C", monday, sec(7, 30)).first()
        val leg = transit(j).single()
        assertEquals("F1#09:00:00", leg.tripId)
        assertEquals(sec(9, 0), leg.boardDepSec)
    }

    @Test
    fun `exact_times block ends strictly before end_time`() {
        // 09:00-09:30 headway 600 -> 09:00, 09:10, 09:20; no 09:30 trip.
        val j = router.plan("A", "C", monday, sec(9, 15)).first()
        assertEquals("F1#09:20:00", transit(j).single().tripId)
        assertTrue(router.plan("A", "C", monday, sec(9, 21)).let { plans ->
            // Next day's 06:00 run is the only thing left (arr > 24h frame).
            plans.isEmpty() || plans.all { it.arrSec > 86_400 }
        })
    }

    @Test
    fun `intermediate boarding keeps the shifted times`() {
        val j = router.plan("B", "C", monday, sec(6, 0)).first()
        val leg = transit(j).single()
        assertEquals(sec(6, 10), leg.boardDepSec)
        assertEquals(sec(6, 20), j.arrSec)
    }
}
