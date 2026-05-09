# KRAIL-BFF · API reference for the KRAIL app

> Companion to `KRAIL_APP_INTEGRATION_HANDOVER.md`. Self-contained
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

## §1 · Common conventions

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
JSON or GTFS-RT protobuf bytes verbatim — schemas in §3 onwards.

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
§3 onwards. Treat any non-2xx response as a failure regardless of
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

## §2 · Quick endpoint index

| Endpoint | Method | Used by KRAIL screen | Migration in handover? |
|---|---|---|---|
| `/v1/tp/trip` | GET | Trip results (TimeTableScreen) | ✅ §6.3 |
| `/api/v1/trip/plan` | GET | (BFF-shaped JSON; not used) | ❌ |
| `/api/v1/trip/plan-proto` | GET | (BFF protobuf; not used) | ❌ |
| `/v1/stops/{stopId}/departures` | GET | Departures (Saved Trips) | ✅ §6.4 |
| `/v1/parking/facilities` | GET | Park & Ride list | ✅ §6.5 |
| `/v1/parking/facilities/{id}/availability` | GET | Park & Ride detail | ✅ §6.5 |
| `/v1/gtfs/realtime/{feed}` | GET (binary) | Live tracking | ✅ §6.6 |
| `/v2/gtfs/realtime/{feed}` | GET (binary) | Live tracking (v2 feeds) | ✅ §6.6 |
| `/v2/gtfs/vehiclepos/{feed}` | GET (binary) | Map markers | ✅ §6.6 |
| `/v1/data/stops/manifest` | GET (302) | Stops dataset (future) | ❌ |
| `/v1/data/routes/manifest` | GET (302) | Routes dataset (future) | ❌ |
| `/health` | GET | None (smoke test) | n/a |
| `/ready` | GET | None (smoke test) | n/a |

Sections §3–§9 cover each endpoint in turn.

---

## §3 · `GET /v1/tp/trip` — trip planner (JSON)

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

## §4 · `GET /v1/stops/{stopId}/departures` — departure board

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
      "transportation": { /* see §3 — same shape */ },
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

## §5 · `GET /v1/parking/facilities` — Park & Ride list

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

## §6 · `GET /v1/parking/facilities/{facilityId}/availability` — single facility

**KRAIL caller:** `RealParkRideService.fetchCarParkFacilities(facilityId)` →
same file as §5.

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

## §7 · GTFS-Realtime endpoints (binary protobuf)

**KRAIL caller:** `RealGtfsRealtimeService.fetchFeed(feedName, feedType, sinceLastModified)` →
`feature/track/network/.../RealGtfsRealtimeService.kt`

**Behavior:** byte-for-byte pass-through. The BFF does not decode
protobuf; it forwards the bytes from NSW. KRAIL's existing
`FeedMessage.ADAPTER.decode(bytes)` (Wire) parses identically.

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

## §8 · `/api/v1/trip/plan` and `/api/v1/trip/plan-proto` — future-shaped trip planner

**Migration in this handover?** No. Listed for completeness.

These exist on the BFF today but the KRAIL app doesn't use them yet.
They are the eventual replacements for `/v1/tp/trip` once the app
adopts the BFF-shaped contract.

| Endpoint | Content-Type | Schema | Wire-size vs JSON |
|---|---|---|---|
| `/api/v1/trip/plan` | `application/json` | `JourneyList` JSON-encoded | ~same as NSW JSON |
| `/api/v1/trip/plan-proto` | `application/protobuf` | `JourneyList` proto-encoded | ~83% smaller |

The proto schema lives in the BFF repo at
`server/src/main/proto/trip.proto`. Top-level message:

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

## §9 · `/v1/data/stops/manifest` and `/v1/data/routes/manifest` — dataset distribution

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
schemas in `server/src/main/proto/stops_dataset.proto` and
`routes_dataset.proto`.

### Response — 404

`{"error":{"code":"manifest_not_configured","message":"Manifest URL not configured (STOPS_MANIFEST_URL)"}}`

Means the BFF deployment hasn't set the `STOPS_MANIFEST_URL` env var.
Locally you'll see this unless you set it. Not a bug, just unimplemented
in your env.

---

## §10 · `/health` and `/ready` — operational probes

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

## §11 · How KRAIL's existing parsers map to BFF responses

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

## §12 · Verifying contracts manually

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

Or curl directly — the URLs in §3–§7 are all you need.
