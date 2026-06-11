package app.krail.bff.track

/**
 * Maps a Trip Planner product class to the GTFS-Realtime feeds that can
 * contain its vehicles. Feed names + API versions verified against the live
 * NSW API 2026-06-12:
 *
 *  - v2: sydneytrains, metro, lightrail/<line>, ferries/sydneyferries
 *  - v1 only: buses (single consolidated feed), nswtrains
 *
 * A product class can map to several feeds (trains run in both the suburban
 * and intercity feeds); the matcher searches them in order.
 */
object FeedRegistry {

    data class FeedRef(val feed: String, val vehicleposVersion: Int, val tripUpdatesVersion: Int)

    private val TRAINS = listOf(
        FeedRef("sydneytrains", vehicleposVersion = 2, tripUpdatesVersion = 2),
        FeedRef("nswtrains", vehicleposVersion = 1, tripUpdatesVersion = 1),
    )
    private val METRO = listOf(FeedRef("metro", 2, 2))
    private val LIGHT_RAIL = listOf(
        FeedRef("lightrail/innerwest", 2, 1),
        FeedRef("lightrail/cbdandsoutheast", 2, 1),
        FeedRef("lightrail/parramatta", 2, 1),
        FeedRef("lightrail/newcastle", 2, 1),
    )
    private val BUSES = listOf(FeedRef("buses", 1, 1))
    private val FERRIES = listOf(FeedRef("ferries/sydneyferries", 2, 1))

    /** Product classes per the Trip Planner API / trip.proto TransportMode. */
    fun feedsFor(productClass: Int): List<FeedRef> = when (productClass) {
        1 -> TRAINS
        2 -> METRO
        4 -> LIGHT_RAIL
        5, 11 -> BUSES
        7 -> emptyList() // coaches have no public realtime feed
        9 -> FERRIES
        else -> emptyList()
    }

    fun isKnownProductClass(productClass: Int): Boolean =
        productClass in setOf(1, 2, 4, 5, 7, 9, 11)
}
