package tools

import app.krail.bff.tools.buildShapesDataset
import app.krail.bff.tools.parseTrackStops
import app.krail.bff.track.PolylineCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildTrackDatasetTest {

    @Test
    fun `directory keeps platforms AND stations with raw ids and parent links`() {
        val stops = parseTrackStops(
            """
            stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station
            200060,Central Station,-33.8832,151.2065,1,
            2000336,"Central Station Platform 16",-33.8842,151.2062,0,200060
            X1,Station Entrance,-33.88,151.20,2,200060
            """.trimIndent(),
        )
        assertEquals(listOf("200060", "2000336"), stops.map { it.stop_id }, "entrances excluded")
        val platform = stops.single { it.stop_id == "2000336" }
        assertEquals("Central Station Platform 16", platform.name)
        assertEquals("200060", platform.parent_id)
        assertTrue(platform.lat < -33.0 && platform.lon > 151.0)
    }

    @Test
    fun `shapes dataset dedups trips onto shared shapes and prunes orphans`() {
        val trips = """
            route_id,service_id,trip_id,shape_id
            T1,wd,trip-a,shape-1
            T1,wd,trip-b,shape-1
            T1,wd,trip-c,shape-orphan
        """.trimIndent()
        val shapes = """
            shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence
            shape-1,-33.8688,151.2093,2
            shape-1,-33.8675,151.2070,1
            shape-unused,-30.0,150.0,1
        """.trimIndent()

        val ds = buildShapesDataset("sydneytrains", "20260611", trips, shapes)

        assertEquals(setOf("trip-a", "trip-b"), ds.trip_index.keys, "trip with missing shape dropped")
        assertEquals(setOf("shape-1"), ds.shapes.keys, "unused shape dropped")
        // Points ordered by shape_pt_sequence, not file order.
        val decoded = PolylineCodec.decode(ds.shapes["shape-1"]!!)
        assertEquals(-33.8675, decoded.first().lat, 1e-5)
        assertEquals(-33.8688, decoded.last().lat, 1e-5)
        assertNull(ds.trip_index["trip-c"])
        assertEquals("sydneytrains", ds.feed)
    }

    @Test
    fun `bundle without shape columns yields an empty dataset, not a crash`() {
        val ds = buildShapesDataset(
            "metro", "20260611",
            "route_id,service_id,trip_id\nM1,wd,trip-x",
            "shape_id,shape_pt_lat\nshape-1,-33.0",
        )
        assertTrue(ds.shapes.isEmpty() && ds.trip_index.isEmpty())
    }
}
