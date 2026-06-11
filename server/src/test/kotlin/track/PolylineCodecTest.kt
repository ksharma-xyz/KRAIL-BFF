package track

import app.krail.bff.track.PolylineCodec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolylineCodecTest {

    @Test
    fun `matches the canonical Google reference vector`() {
        // From the polyline algorithm spec.
        val points = listOf(
            PolylineCodec.Point(38.5, -120.2),
            PolylineCodec.Point(40.7, -120.95),
            PolylineCodec.Point(43.252, -126.453),
        )
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", PolylineCodec.encode(points))
    }

    @Test
    fun `round-trips Sydney coordinates within precision 5`() {
        val points = listOf(
            PolylineCodec.Point(-33.8688, 151.2093),  // Sydney CBD
            PolylineCodec.Point(-33.8675, 151.2070),
            PolylineCodec.Point(-33.9200, 151.0000),
        )
        val decoded = PolylineCodec.decode(PolylineCodec.encode(points))
        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (a, b) ->
            assertTrue(abs(a.lat - b.lat) < 1e-5 && abs(a.lon - b.lon) < 1e-5, "$a vs $b")
        }
    }

    @Test
    fun `empty list encodes to empty string`() {
        assertEquals("", PolylineCodec.encode(emptyList()))
        assertEquals(0, PolylineCodec.decode("").size)
    }
}
