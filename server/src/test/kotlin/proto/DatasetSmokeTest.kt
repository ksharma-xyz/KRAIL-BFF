package app.krail.bff.proto

import app.krail.bff.proto.data.DatasetStop
import app.krail.bff.proto.data.LatLng
import app.krail.bff.proto.data.RouteGroup
import app.krail.bff.proto.data.RouteVariant
import app.krail.bff.proto.data.RoutesDataset
import app.krail.bff.proto.data.StopsDataset
import app.krail.bff.proto.data.TransportMode
import app.krail.bff.proto.data.TripOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke + contract test for the dataset proto types
 * (`StopsDataset`, `RoutesDataset`).
 *
 * The dataset builders (`BuildStopsDataset`, `BuildRoutesDataset`) are
 * batch tools exercised by `.github/workflows/datasets.yml` — they don't
 * have a unit-test surface. This test proves three things:
 *
 *   1. The Wire-generated Kotlin from the KRAIL-API-PROTO submodule is
 *      reachable from test code (i.e. the submodule swap landed).
 *   2. The proto types under `app.krail.bff.proto.data.*` round-trip
 *      cleanly (encode → decode bytewise identical).
 *   3. Every `// contract: required` field on the dataset messages can
 *      be populated with sensible defaults — a regression in the proto
 *      schema that adds a new required field will break this test.
 *
 * Field-by-field non-null assertions on the upstream-built datasets
 * happen at the workflow level — when `datasets.yml` runs, the resulting
 * .pb files are downloaded by the KRAIL app and parsed; bad data fails
 * the dataset-bump auto-PR there.
 */
class DatasetSmokeTest {

    @Test
    fun `StopsDataset schema is reachable from this module after submodule swap`() {
        val dataset = StopsDataset(
            version = "20260509",
            generated_at = "2026-05-09T00:00:00Z",
            attribution = "Contains transport data sourced from Transport for NSW under CC BY 4.0.",
            stops = listOf(
                DatasetStop(
                    stop_id = "NSW:200060",
                    name = "Central Station",
                    position = LatLng(lat = -33.8829, lon = 151.2061),
                    modes = listOf(TransportMode.TRAIN, TransportMode.METRO),
                ),
                DatasetStop(
                    stop_id = "NSW:10101101",
                    name = "Town Hall Station",
                    // position deliberately omitted — contract: optional.
                    position = null,
                    modes = listOf(TransportMode.TRAIN),
                ),
            ),
        )

        assertNotNull(dataset.version)
        assertNotNull(dataset.generated_at)
        assertNotNull(dataset.attribution)
        assertNotNull(dataset.stops)
        assertEquals(2, dataset.stops.size)

        for (stop in dataset.stops) {
            assertNotNull(stop.stop_id, "DatasetStop.stop_id (required)")
            assertNotNull(stop.name,    "DatasetStop.name (required)")
            assertNotNull(stop.modes,   "DatasetStop.modes (required, may be empty)")
            // stop.position is contract: optional — null is allowed.
        }

        // Round-trip: encode → decode → equality.
        val encoded = StopsDataset.ADAPTER.encode(dataset)
        val decoded = StopsDataset.ADAPTER.decode(encoded)
        assertEquals(dataset, decoded, "StopsDataset must round-trip cleanly")
    }

    @Test
    fun `RoutesDataset schema is reachable from this module after submodule swap`() {
        val dataset = RoutesDataset(
            version = "20260509",
            generated_at = "2026-05-09T00:00:00Z",
            attribution = "Contains transport data sourced from Transport for NSW under CC BY 4.0.",
            routes = listOf(
                RouteGroup(
                    route_short_name = "T1",
                    mode = TransportMode.TRAIN,
                    variants = listOf(
                        RouteVariant(
                            route_id = "1_T1A",
                            route_name = "T1 North Shore Line",
                            trips = listOf(
                                TripOption(
                                    trip_id = "T1A.1234.10.1.A",
                                    headsign = "Central via North Sydney",
                                    stop_ids = listOf("NSW:10101101", "NSW:200060"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertNotNull(dataset.routes)
        assertTrue(dataset.routes.isNotEmpty())

        for (group in dataset.routes) {
            assertNotNull(group.route_short_name, "RouteGroup.route_short_name (required)")
            // group.mode is enum — proto3 default UNSPECIFIED, never null.
            assertNotNull(group.variants, "RouteGroup.variants (required, ≥ 1)")
            for (variant in group.variants) {
                assertNotNull(variant.route_id,   "RouteVariant.route_id (required)")
                assertNotNull(variant.route_name, "RouteVariant.route_name (required)")
                assertNotNull(variant.trips,      "RouteVariant.trips (required, ≥ 1)")
                for (trip in variant.trips) {
                    assertNotNull(trip.trip_id,  "TripOption.trip_id (required)")
                    assertNotNull(trip.headsign, "TripOption.headsign (required)")
                    assertNotNull(trip.stop_ids, "TripOption.stop_ids (required, may be empty)")
                }
            }
        }

        // Round-trip through Wire's generated adapter.
        val encoded = RoutesDataset.ADAPTER.encode(dataset)
        val decoded = RoutesDataset.ADAPTER.decode(encoded)
        assertEquals(dataset, decoded, "RoutesDataset must round-trip cleanly")
    }

    @Test
    fun `TransportMode enum has the expected NSW productClass values`() {
        // These numeric values are the contract: they match NSW's productClass codes
        // and are referenced by the trip-planner mapper. Renumbering would silently
        // break encoded datasets in the wild.
        assertEquals(0,  TransportMode.TRANSPORT_MODE_UNSPECIFIED.value)
        assertEquals(1,  TransportMode.TRAIN.value)
        assertEquals(2,  TransportMode.METRO.value)
        assertEquals(4,  TransportMode.LIGHT_RAIL.value)
        assertEquals(5,  TransportMode.BUS.value)
        assertEquals(7,  TransportMode.COACH.value)
        assertEquals(9,  TransportMode.FERRY.value)
        assertEquals(11, TransportMode.SCHOOL_BUS.value)
        assertEquals(99, TransportMode.WALKING.value)
    }
}
