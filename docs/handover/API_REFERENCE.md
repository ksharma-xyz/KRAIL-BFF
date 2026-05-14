# KRAIL-BFF · API reference for the KRAIL app

> Companion to `MIGRATION_GUIDE.md`. Self-contained
> request/response specs for every BFF endpoint the KRAIL app
> consumes. The migration playbook tells you *what* to change; this
> tells you *what to send and what to expect back*.
>
> All examples were captured live from a running BFF (2026-05-09)
> against the real NSW upstream. Field-by-field listings prefer
> "what the parser will actually see" over "what NSW documents" —
> these are the same in practice but you should rely on this file's
> samples, not the NSW Open Data swagger which has gaps.

---

## 1 · Common conventions

### Base URL

`http://10.0.2.2:8080` (Android emulator) /
`http://localhost:8080` (iOS sim) /
`http://<HOST_LAN_IP>:8080` (physical device).

In production the base URL becomes `https://bff.krail.app` (or whatever
the deployed host turns out to be) but this document only covers local
debug.

### Auth

**No client-side auth.** The BFF holds the NSW API key server-side
and adds `Authorization: apikey <key>` before forwarding upstream. Do
not send `Authorization` from the app — it's ignored on every endpoint
listed here.

### Headers

| Header | Required? | Notes |
|---|---|---|
| `X-Krail-Version` | Recommended | Format `MAJOR.MINOR.PATCH`. Local dev floor is `0.0.0` (gate disabled), so omitting it works for debug. Once the BFF tightens the floor in production, missing/older versions get `403 version_too_old`. |
| `X-Correlation-Id` | Optional | If you set it, the BFF echoes it in logs and in error envelopes. If you omit it, the BFF generates one and returns it in `X-Correlation-Id` response header. Always log it. |
| `Content-Type` | Not applicable | All requests are GET; no body. |

### Response envelope conventions

For successful responses (`200 OK`) the BFF returns the upstream NSW
JSON or GTFS-RT protobuf bytes verbatim — schemas in 3 onwards.

For error responses (any 4xx / 5xx) **originating in the BFF**, the
shape is:

```json
{
  "error": {
    "code": "version_too_old",
    "message": "X-Krail-Version 1.2.0 is below the minimum 2.0.0",
    "details": null
  },
  "correlationId": "942cf2c5-ef3d-42e0-93c9-be120ecd1410"
}
```

`code` is a stable string identifier (use it in app logic, not
`message`). `details` may be a string, an object, or `null`.

For errors **propagated from NSW** (e.g. NSW returned 502, BFF
forwards it), some endpoints return NSW's body verbatim while others
wrap it in the envelope above. Per-endpoint behavior is called out in
3 onwards. Treat any non-2xx response as a failure regardless of
shape, and surface `correlationId` if present.

### Status codes you may see

| Status | Meaning | Source |
|---|---|---|
| 200 | Success | NSW or BFF |
| 302 | Redirect (manifest endpoints only) | BFF |
| 400 `invalid_*` | Bad request — failed BFF input regex | BFF |
| 403 `version_too_old` | `X-Krail-Version` below floor | BFF |
| 429 `rate_limited` | Per-IP rate limit (5 RPS / 10 burst) | BFF |
| 502 | NSW upstream returned an error | NSW or BFF wrapper |
| 503 `daily_budget_exceeded` | NSW upstream daily quota exhausted | BFF |
| 503 `upstream_error` | NSW upstream unreachable / timeout | BFF |
| 504 | NSW upstream timed out | BFF |

---

## 2 · Quick endpoint index

| Endpoint | Method | Encoding | Used by |
|---|---|---|---|
| `/v1/tp/trip` | GET | JSON pass-through | Trip results — fallback |
| `/api/v1/trip/plan-proto` | GET | binary protobuf | Trip results — preferred |
| `/v1/stops/{stopId}/departures` | GET | JSON pass-through | Departures — fallback |
| `/api/v1/stops/{stopId}/departures-proto` | GET | binary protobuf | Departures — preferred |
| `/v1/parking/facilities` | GET | JSON pass-through | Facility list (rare) |
| `/v1/parking/facilities/{id}/availability` | GET | JSON pass-through | Park & Ride single (rare) |
| `/v1/parking/availability?stopIds=` | GET | JSON | Park & Ride home batch — fallback |
| `/api/v1/parking/availability-proto?stopIds=` | GET | binary protobuf | Park & Ride home batch — preferred |
| `/v1/gtfs/realtime/{feed}` | GET (binary) | upstream protobuf | Live tracking |
| `/v2/gtfs/realtime/{feed}` | GET (binary) | upstream protobuf | Live tracking (v2 feeds) |
| `/v2/gtfs/vehiclepos/{feed}` | GET (binary) | upstream protobuf | Map markers |
| `/v1/data/stops/manifest` | GET (302) | JSON manifest → .pb asset | Stops dataset (future) |
| `/v1/data/routes/manifest` | GET (302) | JSON manifest → .pb asset | Routes dataset (future) |
| `/health` · `/ready` | GET | JSON | Operational probes |

Two BFF design choices to call out:

- **Park & Ride is `?stopIds=` only.** The earlier `?ids=` mode was
  removed. KRAIL exclusively uses stop-id resolution.
- **GTFS-RT is byte-for-byte upstream pass-through.** No BFF-shaped
  variant. KRAIL's existing `FeedMessage.ADAPTER.decode()` consumes
  the standard GTFS-RT spec directly.
- **Parking facility list stays in Firebase Remote Config**
  (`NSW_PARK_RIDE_FACILITIES`). The earlier "embed in stops dataset"
  plan was dropped — the BFF doesn't serve that mapping.

---

## 3 · `GET /v1/tp/trip` — trip planner (JSON)

**KRAIL caller:** `RealTripPlanningService.trip(...)` →
`feature/trip-planner/network/.../api/service/RealTripPlanningService.kt`

**Behavior:** pure pass-through of NSW `/v1/tp/trip`. Same path, same
query params, same JSON response — drop-in URL swap.

### Request

| Param | In | Type | Required | Format | Example | Notes |
|---|---|---|---|---|---|---|
| `name_origin` | query | string | ✅ | NSW stop ID | `10101101` | Origin stop. Town Hall = `10101101`, Central = `200060`. |
| `name_destination` | query | string | ✅ | NSW stop ID | `200060` | Destination stop. |
| `depArrMacro` | query | string | ✅ | enum | `dep` / `arr` | "dep" = depart at, "arr" = arrive by. |
| `type_origin` | query | string | ✅ | const | `any` | KRAIL always sends `any`. |
| `type_destination` | query | string | ✅ | const | `any` | Same. |
| `calcNumberOfTrips` | query | int | ✅ | 1–10 | `6` | KRAIL sends 6. |
| `TfNSWTR` | query | bool | ✅ | const | `true` | NSW-mandated marker. |
| `version` | query | string | ✅ | NSW API version | `10.2.1.42` | KRAIL hardcodes this. |
| `coordOutputFormat` | query | string | ✅ | const | `EPSG:4326` | Always this value. |
| `outputFormat` | query | string | ✅ | const | `rapidJSON` | The BFF will only return JSON for this endpoint. |
| `itOptionsActive` | query | bool | ✅ | `1` | `1` | |
| `computeMonomodalTripBicycle` | query | bool | ✅ | `false` | `false` | |
| `cycleSpeed` | query | int | ✅ | const | `16` | |
| `useElevationData` | query | bool | ✅ | `1` | `1` | |
| `itdDate` | query | string | – | YYYYMMDD | `20260419` | Optional. Omit for "now". |
| `itdTime` | query | string | – | HHmm (24h) | `1430` | Optional. Omit for "now". |
| `excludedMeans` | query | string | – | const | `checkbox` | Send when excluding any modes. |
| `exclMOT1` | query | int | – | `1` | `1` | Exclude trains. |
| `exclMOT2` | query | int | – | `2` | `2` | Exclude metro. |
| `exclMOT4` | query | int | – | `4` | `4` | Exclude light rail. |
| `exclMOT5` | query | int | – | `5` | `5` | Exclude buses. |
| `exclMOT7` | query | int | – | `7` | `7` | Exclude coach. |
| `exclMOT9` | query | int | – | `9` | `9` | Exclude ferry. |
| `exclMOT11` | query | int | – | `11` | `11` | Exclude school bus. |

### Sample request

```bash
curl 'http://localhost:8080/v1/tp/trip?\
name_origin=10101101&name_destination=200060\
&depArrMacro=dep&type_origin=any&type_destination=any\
&calcNumberOfTrips=6&TfNSWTR=true&version=10.2.1.42\
&coordOutputFormat=EPSG:4326&outputFormat=rapidJSON\
&itOptionsActive=1&computeMonomodalTripBicycle=false\
&cycleSpeed=16&useElevationData=1' \
  -H 'X-Krail-Version: 1.0.0'
```

### Response — 200 OK

`Content-Type: application/json`. Top-level keys:

```json
{
  "version": "10.6.21.17",
  "systemMessages": [],
  "error": null,
  "journeys": [
    {
      "legs": [
        {
          "distance": 1843,
          "duration": 540,
          "isRealtimeControlled": true,
          "origin":      { "id": "...", "name": "...", "departureTimePlanned": "...", "departureTimeEstimated": "..." },
          "destination": { "id": "...", "name": "...", "arrivalTimePlanned": "...",   "arrivalTimeEstimated": "..." },
          "transportation": { /* see "transportation object" below */ },
          "stopSequence": [ /* array of stops on this leg */ ],
          "footPathInfo": [],
          "infos": [],
          "interchange": null,
          "hints": []
        }
      ]
    }
  ]
}
```

Authoritative parser: `feature/trip-planner/network/.../model/TripResponse.kt` and friends. The
classes `TripResponse`, `Journey`, `Leg`, `StopSequence`, `Transportation`,
`Origin`, `Destination` already match this shape — that's why the
migration is a URL swap with no model changes.

#### `transportation` object (used here AND in departures)

```json
{
  "id": "nsw:74430: :R:sj2",
  "name": "Sydney Buses Network 430",
  "disassembledName": "430",
  "number": "430",
  "description": "Sydenham to Central Railway Square (Loop Service)",
  "product": {
    "id": 5,
    "class": 5,
    "name": "Sydney Buses Network",
    "iconId": 5
  },
  "operator": { "id": "2459", "name": "Transit Systems" },
  "destination": { "id": "10101326", "name": "Sydenham via Central", "type": "stop" },
  "properties": { /* free-form NSW metadata */ }
}
```

`product.class` is the **mode code** the app already maps:
1=Train, 2=Metro, 4=LightRail, 5=Bus, 7=Coach, 9=Ferry, 11=SchoolBus.

### Errors

| Status | Body | When |
|---|---|---|
| 400 | `{"error":{"code":"missing_origin","message":"..."}}` | `name_origin` empty/absent. |
| 400 | `{"error":{"code":"missing_destination","message":"..."}}` | `name_destination` empty/absent. |
| 400 | `{"error":{"code":"invalid_param","message":"..."}}` | Bad value (e.g. malformed date). |
| 502 / 503 | NSW error or BFF envelope | NSW upstream failed. |

KRAIL's existing `TripResponse` parser also looks at the `error` field
inside a 200 body — NSW sometimes returns 200 with `"error": {...}` for
"no journeys found" cases. Don't change that handling.

---

## 4 · `GET /v1/stops/{stopId}/departures` — departure board

**KRAIL caller:** `RealDeparturesService.departures(stopId, date, time)` →
`feature/departures/network/.../api/service/RealDeparturesService.kt`

**Behavior:** path-reshape of NSW `/v1/tp/departure_mon`. The BFF
fixes most NSW query params server-side (`outputFormat=rapidJSON`,
`type_dm=stop`, `mode=direct`, `departureMonitorMacro=true`, `TfNSWDM=true`,
`coordOutputFormat=EPSG:4326`). The KRAIL app no longer sends those.

### Request

| Param | In | Type | Required | Format | Example | Notes |
|---|---|---|---|---|---|---|
| `stopId` | path | string | ✅ | `^[A-Za-z0-9:]{1,40}$` | `200060` or `NSW:200060` | The BFF strips a `NSW:` namespace prefix automatically before calling NSW. |
| `date` | query | string | – | YYYYMMDD | `20260419` | Reference date. Omit for "now". |
| `time` | query | string | – | HHmm | `1430` | Reference time. Omit for "now". |

### Sample request

```bash
curl 'http://localhost:8080/v1/stops/200060/departures' \
  -H 'X-Krail-Version: 1.0.0'
```

### Response — 200 OK

`Content-Type: application/json`. Verbatim NSW `departure_mon`
response shape. Top-level keys:

```json
{
  "version": "10.6.21.17",
  "systemMessages": [],
  "locations": [ /* the resolved stop(s) */ ],
  "stopEvents": [
    {
      "isRealtimeControlled": true,
      "realtimeStatus": ["MONITORED"],
      "location": { "id": "...", "name": "...", "type": "platform", "parent": { /* station */ } },
      "departureTimePlanned":       "2026-05-09T14:30:00Z",
      "departureTimeBaseTimetable": "2026-05-09T14:30:00Z",
      "departureTimeEstimated":     "2026-05-09T14:32:15Z",
      "transportation": { /* see 3 — same shape */ },
      "properties":     { /* NSW metadata */ }
    }
  ]
}
```

KRAIL's existing parser: `feature/departures/network/.../model/DepartureMonitorResponse.kt`. No model changes needed.

### Errors

| Status | Body | When |
|---|---|---|
| 400 | `{"error":{"code":"invalid_stop_id","message":"stopId must be alphanumeric (with optional namespace prefix), 1-40 chars"}}` | Failed regex. |
| 502 / 503 | BFF envelope | NSW upstream failure. |

---

## 4b · `GET /api/v1/stops/{stopId}/departures-proto` — departure board (proto)

**KRAIL caller:** the proto-flagged path in `RealDeparturesService`
when the BFF proto flag is on.

**Behavior:** same input as `/v1/stops/{id}/departures`; same NSW
upstream call. Response is a screen-shaped binary `DepartureBoardResponse`
(KRAIL-API-PROTO `v0.3.0+`) instead of NSW's full JSON. Carries only the
fields the screen renders — line + destination + planned/realtime times +
platform + trip_id + is_realtime — so the wire payload is ~98% smaller
raw, ~93% smaller gzipped vs the JSON pass-through.

### Request

Same as section 4 (`stopId` in path, optional `?date=&time=`).

### Sample request

```bash
curl 'http://localhost:8080/api/v1/stops/200060/departures-proto' \
  -H 'X-Krail-Version: 1.0.0' \
  -o /tmp/dep.pb
```

A captured fixture for snapshot tests on the KRAIL side lives at
`docs/handover/fixtures/departures-200060.pb` in this repo (1.9 KB,
real captured response).

### Response — 200 OK

`Content-Type: application/protobuf`. Top-level message:

```proto
message DepartureBoardResponse {
  StopRef stop = 1;             // required — id + name + optional coord
  repeated DepartureRow departures = 2;  // required — empty list when no upcoming
}

message DepartureRow {
  TransitLine line = 1;     // required — display_name + transport_mode_type + optional color_hex
  string destination = 2;   // required — headsign
  StopTime time = 3;        // required — planned_utc + optional estimated_utc
  optional string platform_text = 4;
  optional string date_label = 5;     // "today" / "tomorrow" / null
  optional string trip_id = 6;        // GTFS-RT trackable when set
  bool is_realtime = 7;
}
```

Decoded sample (Bondi Junction stop, abridged):

```
stop {
  id: "200060"
  name: "Central Station"
  coord { lat: -33.8829 lon: 151.2061 }
}
departures {
  line { display_name: "T1" transport_mode_type: 1 }
  destination: "Bondi Junction"
  time { planned_utc: "2026-05-10T11:42:00Z" estimated_utc: "2026-05-10T11:43:15Z" }
  platform_text: "Platform 16"
  trip_id: "Sydney_Trains_T1A.1234.10.1"
  is_realtime: true
}
departures { ... 5–10 more rows ... }
```

### Errors

Same as section 4 — invalid stopId is 400 JSON envelope. NSW upstream
errors propagate as 502/503 with a JSON envelope (not proto), matching
the trip-proto endpoint's pattern.

---

## 5 · `GET /v1/parking/facilities` — Park & Ride list

**KRAIL caller:** `RealParkRideService.fetchCarParkFacilities()` (no-arg overload) →
`feature/park-ride/network/.../service/RealParkRideService.kt`

**Behavior:** pure pass-through of NSW `/v1/carpark` (no `facility=`
param). Returns a flat object map of facility ID → facility name.

### Request

No params.

### Sample request

```bash
curl 'http://localhost:8080/v1/parking/facilities'
```

### Response — 200 OK

`Content-Type: application/json`. **44 entries** (as of 2026-05-09).
Shape is `Map<String, String>`:

```json
{
  "486": "Park&Ride - Ashfield",
  "487": "Park&Ride - Kogarah",
  "488": "Park&Ride - Seven Hills",
  "489": "Park&Ride - Manly Vale",
  "490": "Park&Ride - Penrith (At-Grade)",
  "491": "Park&Ride - Warriewood",
  "492": "Park&Ride - Brookvale",
  "493": "Park&Ride - Mona Vale",
  "...": "(40 more entries)"
}
```

KRAIL deserializes this directly into `Map<String, String>`. No model
class needed.

### Errors

| Status | Body | When |
|---|---|---|
| 502 / 503 | BFF envelope | NSW upstream failure. |

---

## 6 · `GET /v1/parking/facilities/{facilityId}/availability` — single facility

**KRAIL caller:** `RealParkRideService.fetchCarParkFacilities(facilityId)` →
same file as 5.

**Behavior:** pass-through of NSW `/v1/carpark?facility={id}`.

### Request

| Param | In | Type | Required | Format | Example |
|---|---|---|---|---|---|
| `facilityId` | path | string | ✅ | `^[A-Za-z0-9_-]{1,40}$` | `486` |

### Sample request

```bash
curl 'http://localhost:8080/v1/parking/facilities/486/availability'
```

### Response — 200 OK

`Content-Type: application/json`. Real captured response (Ashfield):

```json
{
  "tsn": "213110",
  "time": "831668277",
  "spots": "216",
  "zones": [
    {
      "spots": "216",
      "zone_id": "1",
      "occupancy": {
        "loop": "36",
        "total": null,
        "monthlies": "0",
        "open_gate": null,
        "transients": "22"
      },
      "zone_name": "MainArea",
      "parent_zone_id": "0"
    }
  ],
  "ParkID": 1,
  "location": {
    "suburb": "Ashfield",
    "address": "Brown Street",
    "latitude": "-33.888104",
    "longitude": "151.126577"
  },
  "occupancy": {
    "loop": null,
    "total": "36",
    "monthlies": "0",
    "open_gate": "0",
    "transients": "22"
  },
  "MessageDate": "2026-05-09T...",
  "facility_id": "486",
  "facility_name": "Park&Ride - Ashfield",
  "tfnsw_facility_id": "..."
}
```

Notes on quirks (these are NSW's, the BFF preserves them):
- **Numbers come as strings.** `spots`, `total`, `transients` etc. are JSON strings, not numbers.
- `total` and `loop` may be `null` depending on facility instrumentation.
- `time` is a unix-ish epoch string (NSW-specific, not standard).

KRAIL's existing parser: `feature/park-ride/network/.../model/CarParkFacilityDetailResponse.kt`. Already handles these quirks.

### Errors

| Status | Body | When |
|---|---|---|
| 400 | `{"error":{"code":"invalid_facility_id","message":"facilityId must be alphanumeric, 1-40 chars"}}` | Failed regex. |
| 502 / 503 | BFF envelope (e.g. `{"success":false,"error":{"code":"upstream_error",...}}`) | NSW upstream failure. |

The error envelope from this endpoint includes a `success: false` field —
it's a NSW-quirk wrapper the BFF preserves. Don't expect it on success
responses.

---

## 6b · `GET /v1/parking/availability?stopIds=...` — JSON batch

**KRAIL caller:** `RealParkRideService.fetchAvailabilityForStops(...)`
when the BFF flag is on (JSON branch).

**Behavior:** server-side resolves stop IDs to facility IDs via
`ParkRideStopFacilityMap`, fans out concurrent NSW carpark calls,
returns a per-stop block of `facilities` + `errors` plus a top-level
`unknownStops` list. **Stop-id mode only** — `?ids=` was deprecated.

Caps: ≤ 20 stop IDs per request. Empty `stopIds` → 400 `missing_stop_ids`.
Beyond cap → 400 `too_many_stop_ids`. NSW upstream failures appear as
per-facility entries in the per-stop `errors` map.

### Sample request

```bash
curl 'http://localhost:8080/v1/parking/availability?stopIds=275010,2155384'
```

### Response — 200 OK

```json
{
  "stops": {
    "275010": {
      "facilities": {
        "21": { /* full NSW carpark body for Penrith at-grade */ },
        "22": { /* full NSW carpark body for Penrith multi-level */ }
      },
      "errors": {}
    },
    "2155384": {
      "facilities": {
        "26": { /* Tallawong P1 NSW body */ },
        "27": { /* Tallawong P2 NSW body */ },
        "28": { /* Tallawong P3 NSW body */ }
      },
      "errors": {}
    }
  },
  "unknownStops": [],
  "correlationId": "942cf2c5-ef3d-42e0-93c9-be120ecd1410"
}
```

`NSW:` namespace prefix on stop IDs is stripped before lookup; the
response key preserves whatever the client sent.

---

## 6c · `GET /api/v1/parking/availability-proto?stopIds=...` — proto batch

**KRAIL caller:** the proto-flagged path in `RealParkRideService`
when both the BFF flag and the proto flag are on.

**Behavior:** identical input to section 6b; identical fan-out;
returns screen-shaped `ParkingAvailabilityResponse` (KRAIL-API-PROTO
`v0.3.0+`) instead of JSON. ~86% smaller raw / ~79% smaller gzipped
vs the JSON variant.

### Sample request

```bash
curl 'http://localhost:8080/api/v1/parking/availability-proto?stopIds=2155384' \
  -o /tmp/parking.pb
```

Captured fixture for KRAIL snapshot tests:
`docs/handover/fixtures/parking-tallawong.pb` (388 bytes, real
captured response).

### Response — 200 OK

`Content-Type: application/protobuf`. Top-level:

```proto
message ParkingAvailabilityResponse {
  // Top-level facilities/errors stay empty in stopIds-only mode
  // (reserved for a future ?ids= mode if it ever returns).
  map<string, FacilityAvailability> facilities = 1;
  map<string, ApiError> errors = 2;

  // Populated for stopIds mode.
  map<string, StopParkingBlock> stops = 3;
  repeated string unknown_stops = 4;
  string correlation_id = 5;
}

message StopParkingBlock {
  map<string, FacilityAvailability> facilities = 1;
  map<string, ApiError> errors = 2;
}

message FacilityAvailability {
  string facility_id = 1;
  string facility_name = 2;
  int32 total_spots = 3;
  int32 occupied_spots = 4;
  optional Coord location = 5;
  optional string suburb = 6;
  optional string address = 7;
  optional string updated_at = 8;
}
```

Decoded sample (Tallawong, abridged):

```
stops {
  key: "2155384"
  value {
    facilities {
      key: "26"
      value {
        facility_id: "26"
        facility_name: "Park&Ride - Tallawong P1"
        total_spots: 350
        occupied_spots: 142
        location { lat: -33.6926 lon: 150.9078 }
        suburb: "Tallawong"
        updated_at: "2026-05-10T11:35:00"
      }
    }
    facilities { key: "27" value { ... } }
    facilities { key: "28" value { ... } }
  }
}
correlation_id: "942cf2c5-ef3d-42e0-93c9-be120ecd1410"
```

### Errors

| Status | Body |
|---|---|
| 400 | JSON `{"error":{"code":"missing_stop_ids","message":"..."}}` (or `too_many_stop_ids`) |
| Per-stop / per-facility | Inside the `errors` map of the relevant `StopParkingBlock`. Codes match the JSON variant: `upstream_404`, `upstream_error`, `daily_budget_exceeded`. |

NSW per-facility upstream failures don't fail the whole response —
the surviving facilities + the failed ones in `errors` come back in
one 200 response.

---

## 7 · GTFS-Realtime endpoints (binary protobuf)

**KRAIL caller:** `RealGtfsRealtimeService.fetchFeed(feedName, feedType, sinceLastModified)` →
`feature/track/network/.../RealGtfsRealtimeService.kt`

**Behavior:** byte-for-byte pass-through. The BFF does not decode
protobuf; it forwards the bytes from NSW. KRAIL's existing
`FeedMessage.ADAPTER.decode(bytes)` (Wire) parses identically.

**Why no BFF-shaped variant:** the GTFS-RT spec is the wire contract,
maintained by gtfs.org. Reshaping to a BFF-defined `JourneyLiveResponse`
would mean defining a custom proto, mapping NSW's vehicle position
fields to it, doubling the schema surface, and locking the app into
the BFF's interpretation of standard fields. The pass-through is the
right design choice. Revisit only if a real screen need emerges that
the standard `FeedMessage` can't serve.

### Three endpoint variants

| BFF path | Use | Feed examples |
|---|---|---|
| `GET /v1/gtfs/realtime/{feed}` | Trip updates / alerts (v1 feeds) | `buses`, `lightrail/cbdandsoutheast`, `nswtrains`, `ferries/sydneyferries`, `regionbuses/...` |
| `GET /v2/gtfs/realtime/{feed}` | Trip updates / alerts (v2 feeds) | `sydneytrains`, `metro` |
| `GET /v2/gtfs/vehiclepos/{feed}` | Vehicle positions | `sydneytrains`, `metro`, `lightrail/innerwest` |

**Choosing v1 vs v2:** KRAIL already encodes this. `V2_FEEDS = setOf("sydneytrains", "metro")`
in `RealGtfsRealtimeService.kt`; everything else uses v1. Don't change that
logic.

**`{feed}` regex:** `^[A-Za-z0-9_/-]{1,64}$`. Slashes are allowed for
nested feeds (`lightrail/cbdandsoutheast`).

### Request

| Param | In | Type | Required | Notes |
|---|---|---|---|---|
| `feed` | path | string | ✅ | See regex above. May contain slashes. |

The KRAIL service also issues a `HEAD` against the same URL with
`If-Modified-Since: <previous Last-Modified>` to skip parsing when
unchanged — the BFF supports HEAD for these routes and forwards NSW's
`Last-Modified` header.

### Sample request

```bash
curl 'http://localhost:8080/v2/gtfs/vehiclepos/sydneytrains' \
  -H 'Accept: application/x-google-protobuf' \
  -o /tmp/feed.pb
file /tmp/feed.pb   # should report binary, ~hundreds of KB
```

### Response — 200 OK

`Content-Type: application/x-protobuf` (or `application/protobuf`).
Body is raw GTFS-Realtime `FeedMessage` bytes. Schema is the standard
GTFS-RT spec (https://gtfs.org/realtime/), abridged:

```
message FeedMessage {
  FeedHeader header = 1;
  repeated FeedEntity entity = 2;
}

message FeedEntity {
  string id = 1;
  oneof entity_payload {
    TripUpdate trip_update = 2;     // /gtfs/realtime/* feeds
    VehiclePosition vehicle = 4;    // /gtfs/vehiclepos/* feeds
    Alert alert = 5;                // either feed (rarely)
  }
}
```

KRAIL's `FeedMessage` import is the canonical Wire-generated class —
field offsets and types are guaranteed by the GTFS-RT spec, not by
NSW or the BFF. Migration does not touch the parser.

`Last-Modified` response header is preserved end-to-end for the
HEAD-optimisation flow.

### Errors

| Status | Body | When |
|---|---|---|
| 400 | `{"error":{"code":"invalid_feed","message":"feed must be alphanumeric (slashes / underscores / hyphens allowed), 1-64 chars"}}` | Failed regex. |
| 404 | (NSW returns 404 for unknown feeds; BFF forwards) | Unknown feed name. |
| 502 / 503 | BFF envelope | NSW upstream failure. |

---

## 8 · `/api/v1/trip/plan` and `/api/v1/trip/plan-proto` — future-shaped trip planner

**Migration in this handover?** No. Listed for completeness.

These exist on the BFF today but the KRAIL app doesn't use them yet.
They are the eventual replacements for `/v1/tp/trip` once the app
adopts the BFF-shaped contract.

| Endpoint | Content-Type | Schema | Wire-size vs JSON |
|---|---|---|---|
| `/api/v1/trip/plan` | `application/json` | `JourneyList` JSON-encoded | ~same as NSW JSON |
| `/api/v1/trip/plan-proto` | `application/protobuf` | `JourneyList` proto-encoded | ~83% smaller |

The proto schema lives in the BFF repo at
`krail-api-proto/proto/api/trip.proto` (in the submodule). Top-level message:

```
message JourneyList {
  repeated JourneyCardInfo journeys = 1;
}

message JourneyCardInfo {
  string time_text = 1;                // "in 5 mins"
  optional string platform_text = 2;
  optional string platform_number = 3;
  string origin_time = 4;
  string origin_utc_date_time = 5;
  string destination_time = 6;
  string destination_utc_date_time = 7;
  string travel_time = 8;
  optional string total_walk_time = 9;
  repeated TransportModeLine transport_mode_lines = 10;
  repeated Leg legs = 11;
  int32 total_unique_service_alerts = 12;
  optional DepartureDeviation departure_deviation = 13;
}
```

When KRAIL adopts these:
1. Set up a shared `krail-api-proto` repo (the `.proto` files extracted
   from the BFF repo).
2. Add Wire codegen to KRAIL's `feature/trip-planner/network/`.
3. Replace `RealTripPlanningService.trip()` to call the proto endpoint
   and decode `JourneyList`.
4. Replace the `TripResponse` → screen mapper with a `JourneyList` →
   screen mapper. The BFF has already done the screen-shaping work.

This is a separate, larger handover. Don't attempt it as part of this
one.

---

## 9 · `/v1/data/stops/manifest` and `/v1/data/routes/manifest` — dataset distribution

**Migration in this handover?** No. Listed for completeness.

The BFF distributes versioned static datasets (stops, routes) via 302
redirects to GitHub Releases assets. The app fetches the manifest,
compares versions, downloads the linked `.pb` only on change.

### Request

```bash
curl -i 'http://localhost:8080/v1/data/stops/manifest'
```

### Response — 302 Found

```
HTTP/1.1 302 Found
Location: https://github.com/.../stops-2026-05-09.json
Cache-Control: public, max-age=300
```

After following the redirect, the manifest body looks like:

```json
{
  "version": "20260509",
  "generated_at": "2026-05-09T03:14:00Z",
  "file_url": "https://github.com/.../stops-2026-05-09.pb",
  "sha256": "abc123...",
  "size_bytes": 4280193
}
```

The `.pb` payload is a `StopsDataset` (or `RoutesDataset`) protobuf —
schemas in `krail-api-proto/proto/data/stops_dataset.proto` and
`routes_dataset.proto` (in the submodule).

### Response — 404

`{"error":{"code":"manifest_not_configured","message":"Manifest URL not configured (STOPS_MANIFEST_URL)"}}`

Means the BFF deployment hasn't set the `STOPS_MANIFEST_URL` env var.
Locally you'll see this unless you set it. Not a bug, just unimplemented
in your env.

---

## 10 · `/health` and `/ready` — operational probes

### `GET /health`

Liveness probe. Used by DigitalOcean App Platform and the dashboard's
smoke test. **Do not call from the KRAIL app** — it's noise on the
metrics dashboards.

```bash
$ curl -i http://localhost:8080/health
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 2

{}
```

Returns `{}` (empty JSON object) on success. The BFF is up and
serving. Does not check NSW.

### `GET /ready`

Readiness probe. Same shape as `/health` (`{}` on success), but
internally it pings NSW once before responding. Slower (~50–500 ms).
Used to detect "BFF is up but can't reach NSW."

Don't call this from the app either — it pollutes the daily NSW budget.

---

## 11 · Wire-size benchmarks (live, 2026-05-10)

Captured against the local BFF on `:8080`, real Sydney query
parameters. Reproducible — see `TESTING_GUIDE.md` section 3 for the script.

### Trip results — Town Hall → Bondi Junction (6 journeys)

| Format | Raw bytes | gzipped (over the wire) |
|---|---|---|
| JSON (`/v1/tp/trip`) | 367 216 B (~358 KB) | 53 798 B (~52 KB) |
| Proto v0.2.0 (`/api/v1/trip/plan-proto`) | 103 632 B (~101 KB) | 30 600 B (~30 KB) |
| **Proto savings** | **−72 %** | **−44 %** |

The wire (gzipped) number is the one that matters for cellular cost.
**Proto saves ~23 KB per trip request** vs gzipped JSON. Raw savings
are wider because gzip is very effective at compressing repeated JSON
keys (`"departureTimeEstimated"` appears 50+ times in one response);
proto's binary tags don't have that redundancy to compress.

### Departures — stop 200060

| Format | Raw bytes | gzipped wire |
|---|---|---|
| JSON (`/v1/stops/200060/departures`) | 99 637 B (~97 KB) | 8 869 B (~9 KB) |
| Proto (`/api/v1/stops/200060/departures-proto`) | 1 884 B | 660 B |
| **Proto savings** | **−98 %** | **−93 %** |

Bigger relative win than trip planner. The screen-shaped
`DepartureBoardResponse` proto only carries
display-relevant fields (line + destination + time + platform +
trip_id) — the NSW JSON pass-through carries 60+ fields per
stopEvent, most of which the app ignores.

### Parking batch — Tallawong + Penrith (5 facilities)

| Format | Raw bytes | gzipped wire |
|---|---|---|
| JSON (`/v1/parking/availability?stopIds=...`) | 3 187 B | 727 B |
| Proto (`/api/v1/parking/availability-proto?stopIds=...`) | 435 B | 150 B |
| **Proto savings** | **−86 %** | **−79 %** |

Per-facility content is screen-shaped — id + name + total/occupied
spots + location/suburb/address + updated_at. JSON pass-through
carries NSW's full carpark body (~30 fields with `zones`, `tsn`,
`time`, `ParkID`, etc.).

### Other endpoints

| Endpoint | Raw bytes | gzipped wire |
|---|---|---|
| `/v2/gtfs/vehiclepos/sydneytrains` (binary protobuf) | 37 885 B (~37 KB) | 12 359 B (~12 KB) |
| `/v1/parking/facilities/{id}/availability` (single, JSON) | ~1 800 B | ~470 B |
| `/v1/parking/facilities` (list, JSON) | 1 950 B | 600 B |

### Why proto on trip is worth adopting (cohort-rollout justification)

- 44 % gzipped-wire reduction = ~23 KB per trip request. On a
  4G-cellular link in low-signal transit corridors, that's ~70 ms of
  transfer time saved.
- Decode is ~3–5× faster — Wire's binary decode beats
  `kotlinx.serialization` JSON decode for nested structures of this
  size and shape.
- Smaller heap allocations (no tokenizer state, fewer intermediate
  strings) — matters on lower-end devices.
- Stable schema. Wire identifies fields by number, not string. An
  upstream NSW field rename can't silently break a proto consumer
  that's already been generated against a fixed schema.

### Caveats

- gzip compresses repeated JSON keys very efficiently; for tiny
  responses (the parking batch above is 540 B gzipped) the proto win
  would be negligible. Not worth proto-ifying every endpoint —
  reserve it for the big-payload screens.
- The proto v0.2.0 shape isn't 1:1 with NSW JSON; some NSW fields
  (e.g. `daysOfService`, `coupledTripsInfo`, `fare.tickets`) aren't
  in the proto schema yet. JSON pass-through has them; proto path
  doesn't until those messages are added in a future schema bump.
  See PROTO_TRIP_POLYLINE_HANDOVER.md for what's in v0.2.0 and what
  isn't.
- Numbers will drift as NSW data changes. Re-measure when making
  cohort-rollout decisions.

---

## 12 · How KRAIL's existing parsers map to BFF responses

For every endpoint migrated in this handover, the response shape is
**identical** to what NSW returns. KRAIL's existing parsers work
unchanged. This table is for reference / debugging:

| BFF endpoint | KRAIL parser class | KRAIL file |
|---|---|---|
| `/v1/tp/trip` | `TripResponse` | `feature/trip-planner/network/src/commonMain/.../model/TripResponse.kt` |
| `/v1/stops/{id}/departures` | `DepartureMonitorResponse` | `feature/departures/network/src/commonMain/.../model/DepartureMonitorResponse.kt` |
| `/v1/parking/facilities` | `Map<String, String>` (no class) | (deserialized inline) |
| `/v1/parking/facilities/{id}/availability` | `CarParkFacilityDetailResponse` | `feature/park-ride/network/src/commonMain/.../model/CarParkFacilityDetailResponse.kt` |
| `/v1/gtfs/realtime/{feed}` (binary) | `com.google.transit.realtime.FeedMessage` | (Wire-generated from GTFS-RT spec) |
| `/v2/gtfs/realtime/{feed}` (binary) | `FeedMessage` | same |
| `/v2/gtfs/vehiclepos/{feed}` (binary) | `FeedMessage` | same |

If you find a parser that **does** break against the BFF response — the
BFF has a bug. Capture the response with `curl`, compare to NSW direct
(via the dashboard's "Compare with NSW" button), and report it. Don't
patch the parser.

---

## 13 · Verifying contracts manually

If you ever need to confirm "does the BFF actually return X for input Y",
the dashboard at <http://localhost:8000/api-tester.html> is the fastest
path:

1. Pick the endpoint in the left sidebar.
2. Fill params, click **Send**.
3. The Highlights panel at the top of the response surfaces the
   important fields — journey count, next departure, error code, etc.
4. The full response body is below, with a foldable JSON tree.
5. Click **Compare with NSW** to fire the same query at NSW direct
   in parallel and see the diff (size, latency, body).

Or curl directly — the URLs in 3–7 are all you need.
