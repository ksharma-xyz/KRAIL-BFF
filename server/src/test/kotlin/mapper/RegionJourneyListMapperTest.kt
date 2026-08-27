package app.krail.bff.mapper

import app.krail.bff.router.MiniNetworkFixture
import app.krail.bff.router.RaptorRouter
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The region parameters (timezone + stop-id prefix) are the only thing that
 * varies between BFF-routed regions — these tests pin exactly that seam.
 * Endpoint-level behaviour is covered by VicTripRoutesTest/QldTripRoutesTest.
 */
class RegionJourneyListMapperTest {

    private val model = MiniNetworkFixture.buildModel()
    private val router = RaptorRouter(model)
    private val date = LocalDate.of(2026, 3, 2) // Monday inside the fixture calendar

    private val melbourneMapper = RegionJourneyListMapper(ZoneId.of("Australia/Melbourne"), "VIC")
    private val brisbaneMapper = RegionJourneyListMapper(ZoneId.of("Australia/Brisbane"), "QLD")

    // 07:30 UTC — the "now" input only feeds the relative time_text.
    private val now = date.atTime(7, 30).atZone(ZoneId.of("UTC")).toInstant()

    private fun directJourney() = router.plan("A", "D", date, MiniNetworkFixture.sec(7, 55))
        .single { j -> j.legs.size == 1 } // R1 direct, dep 08:00, arr 08:40

    @Test
    fun `stop ids carry the region prefix`() {
        val journey = directJourney()
        val vic = melbourneMapper.toProto(model, listOf(journey), date, now).journeys.single()
        val qld = brisbaneMapper.toProto(model, listOf(journey), date, now).journeys.single()
        assertEquals("VIC:A", vic.legs[0].transport_leg!!.stops.first().stop_id)
        assertEquals("QLD:A", qld.legs[0].transport_leg!!.stops.first().stop_id)
    }

    @Test
    fun `instants derive from the region's local midnight`() {
        val journey = directJourney()
        val vic = melbourneMapper.toProto(model, listOf(journey), date, now).journeys.single()
        val qld = brisbaneMapper.toProto(model, listOf(journey), date, now).journeys.single()
        // 2026-03-02 08:00 local: Melbourne is UTC+11 (AEDT), Brisbane a fixed
        // UTC+10 (no DST in Queensland) — same wall time, different instants.
        assertEquals("2026-03-01T21:00:00Z", vic.origin_utc_date_time)
        assertEquals("2026-03-01T22:00:00Z", qld.origin_utc_date_time)
        // Local wall-clock rendering is identical in both regions.
        assertEquals("8:00am", vic.origin_time)
        assertEquals("8:00am", qld.origin_time)
        assertEquals("8:40am", vic.destination_time)
        assertEquals("8:40am", qld.destination_time)
    }

    @Test
    fun `walk-only journeys are dropped and walk legs render between transits`() {
        // A -> F forces express/direct + walk (D -> D2) + feeder.
        val journeys = router.plan("A", "F", date, MiniNetworkFixture.sec(7, 55))
        val proto = brisbaneMapper.toProto(model, journeys, date, now)
        val journey = proto.journeys.single()
        assertEquals(3, journey.legs.size)
        assertTrue(journey.legs[1].walking_leg != null)
        assertTrue(journey.total_walk_time != null)
    }

    @Test
    fun `product class mapping covers seq and vic route types`() {
        // SEQ basic types.
        assertEquals(4, RegionJourneyListMapper.productClass(0)) // G:link tram -> light rail
        assertEquals(1, RegionJourneyListMapper.productClass(2)) // Citytrain -> train
        assertEquals(5, RegionJourneyListMapper.productClass(3)) // bus
        assertEquals(9, RegionJourneyListMapper.productClass(4)) // CityCat/ferry
        // VIC extended types keep their existing mapping.
        assertEquals(1, RegionJourneyListMapper.productClass(400)) // metro rail
        assertEquals(5, RegionJourneyListMapper.productClass(701)) // bus
        assertEquals(1, RegionJourneyListMapper.productClass(102)) // V/Line rail
        assertEquals(7, RegionJourneyListMapper.productClass(200)) // coach
    }
}
