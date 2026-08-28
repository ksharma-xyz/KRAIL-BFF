package app.krail.bff.router

import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * pickup_type / drop_off_type enforcement (SEQ sets these mid-trip on ~8k
 * calls; MBTA/VBB/PID use them too). Only type 1 (not available) restricts;
 * 2/3 (phone / arrange with driver) stay usable.
 *
 * R1 A-B-C: t1 calls at B with pickup_type=1 AND drop_off_type=1 (express-style
 * restriction); t2 is unrestricted at B. Both trips share one pattern, so the
 * FIFO binary-search path must skip t1 when boarding at B.
 *
 * R2 P-Q-R is a non-FIFO (overtaking) pattern where the later-departing trip
 * at Q is also pickup-restricted — the linear-scan path must skip it too.
 *
 * A also carries drop_off_type=1 and C pickup_type=1 on t1 (trip-endpoint
 * restrictions, the common real-feed case) — riding A->C must be unaffected.
 * B on t2 has pickup_type=3 (arrange with driver): still boardable.
 */
class PickupDropOffTest {

    private companion object {
        val source = StringGtfsSource(
            "pudo",
            mapOf(
                "stops.txt" to """
                    stop_id,stop_name,stop_lat,stop_lon
                    A,Stop A,-37.80,145.00
                    B,Stop B,-37.80,145.10
                    C,Stop C,-37.80,145.20
                    P,Stop P,-37.60,145.00
                    Q,Stop Q,-37.60,145.10
                    R,Stop R,-37.60,145.20
                """.trimIndent(),
                "routes.txt" to """
                    route_id,route_short_name,route_long_name,route_type
                    R1,r1,Main Line,3
                    R2,r2,Overtaker,3
                """.trimIndent(),
                "trips.txt" to """
                    route_id,service_id,trip_id,trip_headsign
                    R1,ALL,t1,
                    R1,ALL,t2,
                    R2,ALL,u1,
                    R2,ALL,u2,
                """.trimIndent(),
                "stop_times.txt" to """
                    trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type
                    t1,08:00:00,08:00:00,A,1,0,1
                    t1,08:10:00,08:10:00,B,2,1,1
                    t1,08:25:00,08:25:00,C,3,1,0
                    t2,08:20:00,08:20:00,A,1,0,0
                    t2,08:30:00,08:30:00,B,2,3,0
                    t2,08:45:00,08:45:00,C,3,0,0
                    u1,09:00:00,09:00:00,P,1,0,0
                    u1,11:00:00,11:00:00,Q,2,1,0
                    u1,11:30:00,11:30:00,R,3,0,0
                    u2,09:05:00,09:05:00,P,1,0,0
                    u2,09:30:00,09:30:00,Q,2,0,0
                    u2,10:00:00,10:00:00,R,3,0,0
                """.trimIndent(),
                "calendar.txt" to """
                    service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                    ALL,1,1,1,1,1,1,1,20260301,20260331
                """.trimIndent(),
            ),
        )

        val model: RouterModel =
            RouterModelBuilder.build(listOf(GtfsParser.parseFeed(source)), sourceLabel = "pudo-fixture")
        val router = RaptorRouter(model)
        val monday: LocalDate = LocalDate.of(2026, 3, 2)
    }

    private fun sec(h: Int, m: Int) = h * 3600 + m * 60

    private fun transit(j: Journey) = j.legs.filterIsInstance<JourneyLeg.Transit>()

    @Test
    fun `no boarding at a pickup-restricted call (fifo binary-search path)`() {
        val journeys = router.plan("B", "C", monday, sec(8, 5))
        assertTrue(journeys.isNotEmpty(), "B -> C should be reachable via t2")
        val leg = transit(journeys.first()).single()
        assertEquals("t2", leg.tripId)
        assertEquals(sec(8, 30), leg.boardDepSec)
    }

    @Test
    fun `no alighting at a drop-off-restricted call`() {
        val journeys = router.plan("A", "B", monday, sec(7, 55))
        assertTrue(journeys.isNotEmpty(), "A -> B should be reachable via t2")
        val j = journeys.first()
        // t1 passes B at 08:10 but may not set down; t2 arrives 08:30.
        assertEquals("t2", transit(j).single().tripId)
        assertEquals(sec(8, 30), j.arrSec)
    }

    @Test
    fun `riding through a restricted call is unaffected`() {
        // t1's B call restricts board/alight, not riding through; endpoint
        // restrictions (A no-drop-off, C no-pickup) don't limit A->C either.
        val j = router.plan("A", "C", monday, sec(7, 55)).first()
        assertEquals("t1", transit(j).single().tripId)
        assertEquals(sec(8, 25), j.arrSec)
    }

    @Test
    fun `pickup_type 3 stays boardable`() {
        val j = router.plan("B", "C", monday, sec(8, 5)).first()
        assertEquals("t2", transit(j).single().tripId)
    }

    @Test
    fun `no boarding at a pickup-restricted call (non-fifo linear path)`() {
        // Overtaking pattern: Q's departures across first-stop-sorted trips
        // are 11:00 (u1) then 09:30 (u2) — non-FIFO, so the linear scan runs.
        assertTrue(model.patternFifo.count { !it } >= 1, "expected a non-FIFO position at Q/R")
        val early = router.plan("Q", "R", monday, sec(9, 0)).first()
        assertEquals("u2", transit(early).single().tripId)

        // After u2 has left, only u1 remains and its Q call is no-pickup:
        // today must yield nothing (tomorrow's u2 shows up in the +1 frame).
        val late = router.plan("Q", "R", monday, sec(10, 30))
        assertTrue(late.all { it.arrSec > 86_400 }, "must not board u1 at its no-pickup call")
    }

    @Test
    fun `restrictions survive a snapshot round-trip`() {
        val path = Files.createTempFile("router-snapshot-pudo", ".bin")
        try {
            RouterSnapshot.write(model, path)
            val reloaded = RouterSnapshot.read(path)
            kotlin.test.assertContentEquals(
                model.stopTimeFlags.toTypedArray(), reloaded.stopTimeFlags.toTypedArray()
            )
            val j = RaptorRouter(reloaded).plan("A", "B", monday, sec(7, 55)).first()
            assertEquals("t2", transit(j).single().tripId)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
