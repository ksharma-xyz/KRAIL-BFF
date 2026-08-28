package app.krail.bff.region

import app.krail.bff.router.GtfsParser
import app.krail.bff.router.RouterModelBuilder
import app.krail.bff.router.StringGtfsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Diacritic-insensitive stop search: Auckland publishes macrons (Ōtāhuhu,
 * Waitematā), Berlin umlauts, Prague háčky — the lab's autocomplete must
 * match them from plain-ASCII queries and from accented ones alike.
 */
class RegionTripServiceTest {

    private companion object {
        val model = RouterModelBuilder.build(
            listOf(
                GtfsParser.parseFeed(
                    StringGtfsSource(
                        "diacritics",
                        mapOf(
                            "stops.txt" to """
                                stop_id,stop_name,stop_lat,stop_lon
                                S1,Ōtāhuhu Station,-36.94,174.84
                                S2,Waitematā (Britomart),-36.84,174.77
                                S3,Möckernbrücke,52.49,13.38
                                S4,Anděl,50.07,14.40
                                S5,Plain Street,50.00,14.00
                            """.trimIndent(),
                            "routes.txt" to """
                                route_id,route_short_name,route_long_name,route_type
                                R,r,Line,3
                            """.trimIndent(),
                            "trips.txt" to """
                                route_id,service_id,trip_id
                                R,ALL,t1
                            """.trimIndent(),
                            "stop_times.txt" to """
                                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                                t1,08:00:00,08:00:00,S1,1
                                t1,08:10:00,08:10:00,S5,2
                            """.trimIndent(),
                            "calendar.txt" to """
                                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                                ALL,1,1,1,1,1,1,1,20260301,20260331
                            """.trimIndent(),
                        ),
                    )
                )
            ),
            sourceLabel = "diacritics-fixture",
        )

        val service = RegionTripService(model, RegionRegistry.akl)
    }

    @Test
    fun `ascii query matches macron names`() {
        val hits = service.searchStops("otahuhu")
        assertEquals(listOf("AKL:S1"), hits.map { it.id })
        assertEquals("Ōtāhuhu Station", hits.single().name)
    }

    @Test
    fun `accented query matches too`() {
        assertEquals(listOf("AKL:S2"), service.searchStops("Waitematā").map { it.id })
        assertEquals(listOf("AKL:S2"), service.searchStops("waitemata").map { it.id })
        assertEquals(listOf("AKL:S3"), service.searchStops("mockernbrucke").map { it.id })
        assertEquals(listOf("AKL:S4"), service.searchStops("andel").map { it.id })
        assertEquals(listOf("AKL:S4"), service.searchStops("Anděl").map { it.id })
    }

    @Test
    fun `plain names still match and ids carry the region prefix`() {
        val hits = service.searchStops("plain")
        assertEquals(listOf("AKL:S5"), hits.map { it.id })
        assertTrue(service.knowsStop("S1"))
    }

    @Test
    fun `folding is nfd strip plus lowercase`() {
        assertEquals("otahuhu", RegionTripService.foldForSearch("Ōtāhuhu"))
        assertEquals("mockernbrucke", RegionTripService.foldForSearch("MÖCKERNBRÜCKE"))
        assertEquals("andel", RegionTripService.foldForSearch("Anděl"))
    }
}
