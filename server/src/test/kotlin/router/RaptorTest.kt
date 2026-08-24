package app.krail.bff.router

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaptorTest {

    private val model = MiniNetworkFixture.buildModel()
    private val router = RaptorRouter(model)
    private val monday = LocalDate.of(2026, 3, 2)
    private val saturday = LocalDate.of(2026, 3, 7)

    private fun sec(h: Int, m: Int, s: Int = 0) = MiniNetworkFixture.sec(h, m, s)

    private fun transitLegs(j: Journey) = j.legs.filterIsInstance<JourneyLeg.Transit>()

    @Test
    fun `direct trip plus faster transfer form the pareto set`() {
        val journeys = router.plan("A", "D", monday, sec(7, 55))
        assertEquals(2, journeys.size)
        // Round 1: slow direct, 0 transfers.
        assertEquals(sec(8, 40), journeys[0].arrSec)
        assertEquals(0, journeys[0].transfers)
        assertEquals(1, transitLegs(journeys[0]).size)
        // Round 2: express + connector arrives earlier at the cost of a transfer.
        assertEquals(sec(8, 30), journeys[1].arrSec)
        assertEquals(1, journeys[1].transfers)
        assertEquals(listOf("rx-1", "rd-1"), transitLegs(journeys[1]).map { it.tripId })
    }

    @Test
    fun `same-stop transfer catches tight connection`() {
        val journeys = router.plan("A", "E", monday, sec(7, 55))
        assertEquals(1, journeys.size)
        val j = journeys.single()
        assertEquals(sec(8, 28), j.arrSec)
        assertEquals(1, j.transfers)
        // Express arrives C 08:15; the 08:18 tram is catchable, 08:25 is not optimal.
        assertEquals(listOf("rx-1", "r2-0"), transitLegs(j).map { it.tripId })
    }

    @Test
    fun `blank intermediate times are interpolated`() {
        val journeys = router.plan("A", "B", monday, sec(7, 55))
        val direct = journeys.first()
        // B's blank 08:00->08:20 anchor times interpolate to 08:10.
        assertEquals(sec(8, 10), direct.arrSec)
        val leg = transitLegs(direct).single()
        assertEquals(sec(8, 10), leg.stops.last().arrSec)
    }

    @Test
    fun `boards earliest catchable trip after departure time`() {
        val journeys = router.plan("A", "D", monday, sec(8, 30))
        val j = journeys.first()
        assertEquals(sec(9, 0), transitLegs(j).first().boardDepSec)
        assertEquals(sec(9, 40), j.arrSec)
    }

    @Test
    fun `footpath transfer produces walk leg and explicit transfer time wins`() {
        val journeys = router.plan("A", "F", monday, sec(7, 55))
        assertEquals(1, journeys.size)
        val j = journeys.single()
        assertEquals(sec(9, 0), j.arrSec)
        assertEquals(3, j.legs.size)
        val walk = j.legs[1] as JourneyLeg.Walk
        // transfers.txt says 30s; the generated ~100m footpath would be ~135s.
        assertEquals(30, walk.durationSec)
        assertEquals("D", model.stopIds[walk.fromStop])
        assertEquals("D2", model.stopIds[walk.toStop])
    }

    @Test
    fun `station id resolves to child platforms`() {
        val journeys = router.plan("A", "STN", monday, sec(7, 55))
        assertEquals(2, journeys.size)
        assertEquals(sec(8, 40), journeys[0].arrSec)
        assertEquals(sec(8, 30), journeys[1].arrSec)
    }

    @Test
    fun `after-midnight trip on previous service day is caught`() {
        val tuesday = LocalDate.of(2026, 3, 3)
        val journeys = router.plan("A", "B", tuesday, sec(0, 15))
        assertTrue(journeys.isNotEmpty())
        val j = journeys.first()
        // Monday's 24:30 trip departs 00:30 Tuesday, arrives 00:50.
        assertEquals(sec(0, 30), transitLegs(j).first().boardDepSec)
        assertEquals(sec(0, 50), j.arrSec)
    }

    @Test
    fun `saturday-only service used on saturday`() {
        val journeys = router.plan("A", "D", saturday, sec(7, 55))
        assertEquals(1, journeys.size)
        val j = journeys.single()
        assertEquals(sec(8, 26), j.arrSec)
        assertEquals(listOf("rx-1", "rs-1"), transitLegs(j).map { it.tripId })
    }

    @Test
    fun `saturday-only service not used on monday`() {
        val journeys = router.plan("A", "D", monday, sec(7, 55))
        val tripIds = journeys.flatMap { transitLegs(it) }.map { it.tripId }
        assertTrue("rs-1" !in tripIds)
    }

    @Test
    fun `calendar_dates removal pushes journey to next service day`() {
        val removedMonday = LocalDate.of(2026, 3, 9)
        val journeys = router.plan("A", "B", removedMonday, sec(7, 55))
        assertTrue(journeys.isNotEmpty())
        // All weekday service is removed on 2026-03-09; the best A->B is
        // Tuesday morning's direct, arriving next day (>24h in query frame).
        assertEquals(sec(8, 10) + 86_400, journeys.first().arrSec)
    }

    @Test
    fun `calendar_dates-only service is active on its added date`() {
        val addedSunday = LocalDate.of(2026, 3, 15)
        val journeys = router.plan("A", "B", addedSunday, sec(9, 0))
        assertTrue(journeys.isNotEmpty())
        assertEquals(sec(10, 20), journeys.first().arrSec)
    }

    @Test
    fun `no journey when nothing runs`() {
        // Sunday 03-08: nothing reaches B today/yesterday, and next-day
        // weekday service is removed by calendar_dates on 03-09.
        val journeys = router.plan("A", "B", LocalDate.of(2026, 3, 8), sec(9, 0))
        assertTrue(journeys.isEmpty())
    }

    @Test
    fun `unknown stop ids return empty`() {
        assertTrue(router.plan("NOPE", "D", monday, sec(8, 0)).isEmpty())
        assertTrue(router.plan("A", "NOPE", monday, sec(8, 0)).isEmpty())
    }

    @Test
    fun `csv quirks survive into the model`() {
        // Quoted name with comma + escaped quotes (stops.txt also has a BOM).
        val a = model.stopsForId("A").single()
        assertEquals("Stop A \"Central\", Platform 1", model.stopNames[a])
        // Generic nodes (location_type 3) are not routable.
        assertTrue(model.stopsForId("X_NODE").isEmpty())
    }
}
