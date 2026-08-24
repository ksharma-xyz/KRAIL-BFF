package app.krail.bff.router

/**
 * Hand-built mini GTFS network exercised through the real parser + builder.
 * Self-contained calendar around fixed test dates in March 2026 — never
 * depends on the wall clock.
 *
 * Stops are ~11km apart (no accidental proximity transfers) except D/D2,
 * ~100m apart, which get a footpath. STN is a station with children D + D2.
 *
 * Services: WK Mon-Fri, SAT Saturday, ALL every day; WK removed on
 * 2026-03-09; XMAS exists only via a calendar_dates addition on 2026-03-15.
 *
 * Routes (type): R1(2) A-B-C-D slow direct, WK — trip r1-1 has blank times
 * at B (tests interpolation); RX(2) A-C express, ALL; RD(3) C-D, ALL;
 * RS(3) C-D fast, SAT; R2(0) C-E, WK; RW(3) D2-F, WK; RN(3) A-B at 24:30
 * after-midnight, WK; RH(3) A-B, XMAS.
 *
 * transfers.txt: explicit D->D2 at 30s (must win over the generated footpath).
 */
object MiniNetworkFixture {

    const val NAMESPACE = "mini"

    fun source(): GtfsSource = StringGtfsSource(
        NAMESPACE,
        mapOf(
            "stops.txt" to STOPS,
            "routes.txt" to ROUTES,
            "trips.txt" to TRIPS,
            "stop_times.txt" to STOP_TIMES,
            "calendar.txt" to CALENDAR,
            "calendar_dates.txt" to CALENDAR_DATES,
            "transfers.txt" to TRANSFERS,
        ),
    )

    fun buildModel(): RouterModel =
        RouterModelBuilder.build(listOf(GtfsParser.parseFeed(source())), sourceLabel = "mini-fixture")

    fun sec(h: Int, m: Int, s: Int = 0): Int = h * 3600 + m * 60 + s

    // BOM on stops.txt: the VIC feeds carry one on every file.
    private val STOPS = "﻿" + """
        stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station
        A,"Stop A ""Central"", Platform 1",-37.80,145.00,0,
        B,Stop B,-37.80,145.10,0,
        C,Stop C,-37.80,145.20,0,
        STN,Station DX,-37.80,145.30,1,
        D,Stop D,-37.80,145.30,0,STN
        D2,Stop D2,-37.8009,145.30,0,STN
        E,Stop E,-37.90,145.20,0,
        F,Stop F,-37.90,145.30,0,
        X_NODE,Generic node,-37.80,145.00,3,
    """.trimIndent()

    private val ROUTES = """
        route_id,route_short_name,route_long_name,route_type
        R1,r1,Slow Direct,2
        RX,rx,Express,2
        RD,rd,Connector,3
        RS,rs,Sat Connector,3
        R2,r2,Tramish,0
        RW,rw,Walk Feeder,3
        RN,rn,Night Owl,3
        RH,rh,Holiday Special,3
    """.trimIndent().replace("\n", "\r\n") // exercise CRLF handling

    private val TRIPS = """
        route_id,service_id,trip_id,trip_headsign
        R1,WK,r1-1,Towards D
        R1,WK,r1-2,
        RX,ALL,rx-1,
        RD,ALL,rd-1,
        RS,SAT,rs-1,
        R2,WK,r2-0,
        R2,WK,r2-1,
        RW,WK,rw-1,
        RN,WK,rn-1,
        RH,XMAS,rh-1,
    """.trimIndent()

    private val STOP_TIMES = """
        trip_id,arrival_time,departure_time,stop_id,stop_sequence
        r1-1,08:00:00,08:00:00,A,1
        r1-1,,,B,2
        r1-1,08:20:00,08:21:00,C,3
        r1-1,08:40:00,08:40:00,D,4
        r1-2,09:00:00,09:00:00,A,1
        r1-2,09:10:00,09:11:00,B,2
        r1-2,09:20:00,09:21:00,C,3
        r1-2,09:40:00,09:40:00,D,4
        rx-1,08:05:00,08:05:00,A,1
        rx-1,08:15:00,08:15:00,C,2
        rd-1,08:22:00,08:22:00,C,1
        rd-1,08:30:00,08:30:00,D,2
        rs-1,08:22:00,08:22:00,C,1
        rs-1,08:26:00,08:26:00,D,2
        r2-0,08:18:00,08:18:00,C,1
        r2-0,08:28:00,08:28:00,E,2
        r2-1,08:25:00,08:25:00,C,1
        r2-1,08:35:00,08:35:00,E,2
        rw-1,08:50:00,08:50:00,D2,1
        rw-1,09:00:00,09:00:00,F,2
        rn-1,24:30:00,24:30:00,A,1
        rn-1,24:50:00,24:50:00,B,2
        rh-1,10:00:00,10:00:00,A,1
        rh-1,10:20:00,10:20:00,B,2
    """.trimIndent()

    private val CALENDAR = """
        service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
        WK,1,1,1,1,1,0,0,20260301,20260331
        SAT,0,0,0,0,0,1,0,20260301,20260331
        ALL,1,1,1,1,1,1,1,20260301,20260331
    """.trimIndent()

    private val CALENDAR_DATES = """
        service_id,date,exception_type
        WK,20260309,2
        XMAS,20260315,1
    """.trimIndent()

    private val TRANSFERS = """
        from_stop_id,to_stop_id,transfer_type,min_transfer_time
        D,D2,2,30
    """.trimIndent()
}
