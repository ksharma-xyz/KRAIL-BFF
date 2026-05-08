package app.krail.bff.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripRequestValidateTest {
    @Test
    fun `valid request passes`() {
        val req = TripRequest(origin = "200060", destination = "10101100")
        assertNull(req.validate())
    }

    @Test
    fun `valid with optional date and time`() {
        val req = TripRequest(
            origin = "200060",
            destination = "10101100",
            date = "20260508",
            time = "1830",
        )
        assertNull(req.validate())
    }

    @Test
    fun `invalid stop id with special chars rejected`() {
        val req = TripRequest(origin = "200/060", destination = "10101100")
        assertEquals(TripRequestError.InvalidStopId, req.validate())
    }

    @Test
    fun `empty stop id rejected`() {
        assertEquals(
            TripRequestError.InvalidStopId,
            TripRequest(origin = "", destination = "10101100").validate(),
        )
    }

    @Test
    fun `over-long stop id rejected`() {
        val tooLong = "a".repeat(33)
        assertEquals(
            TripRequestError.InvalidStopId,
            TripRequest(origin = tooLong, destination = "10101100").validate(),
        )
    }

    @Test
    fun `invalid date format rejected`() {
        assertEquals(
            TripRequestError.InvalidDate,
            TripRequest(origin = "200060", destination = "10101100", date = "2026-05-08").validate(),
        )
    }

    @Test
    fun `invalid time format rejected`() {
        assertEquals(
            TripRequestError.InvalidTime,
            TripRequest(origin = "200060", destination = "10101100", time = "18:30").validate(),
        )
    }

    @Test
    fun `invalid depArr rejected`() {
        assertEquals(
            TripRequestError.InvalidDepArr,
            TripRequest(origin = "200060", destination = "10101100", depArr = "leave").validate(),
        )
    }

    @Test
    fun `excluded mode outside whitelist rejected`() {
        assertEquals(
            TripRequestError.InvalidMode,
            TripRequest(
                origin = "200060",
                destination = "10101100",
                excludedModes = setOf(1, 999),
            ).validate(),
        )
    }

    @Test
    fun `whitelist of allowed modes accepted`() {
        val req = TripRequest(
            origin = "200060",
            destination = "10101100",
            excludedModes = setOf(1, 2, 4, 5, 7, 9, 11),
        )
        assertNull(req.validate())
    }
}
