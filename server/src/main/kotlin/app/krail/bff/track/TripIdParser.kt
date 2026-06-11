package app.krail.bff.track

/**
 * Parses Sydney Trains / NSW Trains dotted GTFS trip ids:
 *
 *   <trip_name>.<timetable_id>.<timetable_version>.<dop_ref>.<set_type>.<cars>.<instance>
 *   e.g. "612P.1287.134.32.T.8.89913444" → Tangara, 8 cars
 *
 * Format verified against live feeds 2026-06-12 (GtfsRealtimeDecodeTest).
 * Applies to product class 1 (trains) only — bus/ferry/metro trip ids use
 * different schemes and yield null here.
 */
object TripIdParser {

    data class ParsedTrip(val setCode: String, val displayName: String, val carCount: Int?)

    private val SET_TYPE_NAMES = mapOf(
        "A" to "Waratah",
        "B" to "Waratah 2",
        "C" to "C Set",
        "D" to "Mariyung",
        "H" to "OSCAR",
        "J" to "Hunter",
        "K" to "K Set",
        "M" to "Millennium",
        "N" to "Endeavour",
        "P" to "Xplorer",
        "S" to "S Set",
        "T" to "Tangara",
        "V" to "V Set",
        "X" to "XPT",
        "Z" to "Heritage",
    )

    /** Freight / track machines / non-passenger codes — never shown to users. */
    private val NON_PASSENGER_CODES = setOf("G", "I", "L", "O", "Q", "U", "W", "Y")

    fun parse(tripId: String): ParsedTrip? {
        val parts = tripId.split(".")
        if (parts.size != 7) return null
        val setCode = parts[4].uppercase()
        if (setCode in NON_PASSENGER_CODES) return null
        val name = SET_TYPE_NAMES[setCode] ?: return null
        val cars = parts[5].toIntOrNull()?.takeIf { it in 1..16 }
        return ParsedTrip(setCode = setCode, displayName = name, carCount = cars)
    }

    /** Maps a live set-type letter (TfnswVehicleDescriptor.vehicle_model on trains). */
    fun displayNameForSetCode(code: String): String? =
        SET_TYPE_NAMES[code.uppercase()].takeIf { code.uppercase() !in NON_PASSENGER_CODES }
}
