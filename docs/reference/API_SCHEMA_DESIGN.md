# KRAIL BFF API Schema Design

> Design proposal for screen-shaped BFF responses, reusable shared types, and KMP-shared proto contracts between
> KRAIL-BFF and the KRAIL app. Pairs with [SCREEN_DATA_INVENTORY.md](SCREEN_DATA_INVENTORY.md). Lives
> under [MODERNIZATION_PLAN.md](MODERNIZATION_PLAN.md) Phase 1.

This is a **review doc**. Nothing here is implemented yet. Sign off on the shape first; the build follows.

---

## Goals

1. **Screen-shaped responses.** What the BFF returns matches what the screen renders. The 12 mappers in KRAIL today (
   `TripResponseMapper`, `DepartureMonitorMapper`, `ParkRideMapper`, `JourneyMapMapper`, `GtfsRealtimeMatcher`, …)
   collapse to "decode proto → bind to UI."
2. **Reusable shared types.** Stop, TransitLine, StopTime, Deviation, ServiceAlert, LatLng — defined once, used in every
   endpoint that needs them. No copy-paste fields per endpoint.
3. **KMP-shared schema.** One set of `.proto` files; both KRAIL-BFF (server, JVM) and KRAIL (KMP app, all platforms)
   generate Kotlin classes from the same source. Single source of truth.
4. **Pre-compute on the server.** Anything that doesn't depend on the device's clock or viewport gets pre-computed: line
   colors, formatted strings, deviation labels, platform extraction, display-text resolution.
5. **Multi-city seam, not multi-city build.** Field names should not bake "NSW" into the contract. Don't build a city
   registry until city #2 is real.

---

## §1. Shared core types

These appear in every screen-level message. Define once, reuse.

### `LatLng`

```proto
message LatLng {
  double lat = 1;
  double lon = 2;
}
```

### `TransitLine`

The mode + line + color trio. BFF computes `color_hex` server-side using the existing `NswTransportLine` table (~46
line-specific overrides) with mode-color fallback; the client just renders.

```proto
message TransitLine {
  TransportMode mode = 1;
  string line_name = 2;        // "T1", "L1", "F1", "333"
  string color_hex = 3;        // "#F99D1C" — pre-resolved (line override > mode default)
  string icon_name = 4;        // "train-icon" — design-system reference (mode-only, no per-line variants)
}

enum TransportMode {
  TRANSPORT_MODE_UNSPECIFIED = 0;
  TRAIN = 1;
  METRO = 2;
  LIGHT_RAIL = 4;
  BUS = 5;
  COACH = 7;
  FERRY = 9;
  SCHOOL_BUS = 11;
  WALKING = 99;
}
```

Numeric values match NSW productClass (1, 2, 4, 5, 7, 9, 11) so cross-referencing GTFS / NSW data stays trivial;
WALKING = 99 is a KRAIL convention for non-transit legs.

### `StopRef`

A stop, with everything any screen ever shows about a stop.

```proto
message StopRef {
  string stop_id = 1;             // city-namespaced from day one: "NSW:200060"
  string name = 2;                // disassembledName
  optional LatLng position = 3;   // omit when not needed (saves bytes for lists)
  optional string platform = 4;   // pre-extracted, mode-aware: "Platform 1" / "Stand A" / "Wharf 3"
  bool wheelchair_accessible = 5;
}
```

### `StopTime`

A stop with a time. The shape needed by departure board, journey card stop list, track screen.

```proto
message StopTime {
  StopRef stop = 1;
  string scheduled_utc = 2;            // ISO-8601 UTC (the baseline)
  optional string estimated_utc = 3;   // ISO-8601 UTC, only if real-time differs
  string display_time = 4;             // pre-formatted "11:30 am" (estimated if present, else scheduled)
  optional string scheduled_display_time = 5;  // "11:25 am", only if estimated differs (for strikethrough)
  optional int32 delay_seconds = 6;    // signed; negative = early
  bool is_real_time = 8;               // true iff estimated_utc present
}
```

**Principle: clock-relative values are client-only; absolute display strings can come from the server.**

`display_time` ("11:30 am") is the absolute time the vehicle leaves — it doesn't change as the clock advances, so the
server pre-formatting it (and handling AEST conversion) is a free win. `scheduled_utc` / `estimated_utc` come along so the
client has the source of truth for any computation that *does* need to update on the clock — "in 5 mins", "departing
now", "Today" vs "Tomorrow", the 1 Hz countdown on the track screen. The server never tries to ship those, because they
go stale within seconds.

A practical consequence: there is no `time_to_departure` / `relative_time` / `date_label` field in any response shape.
The client renders all of those from the UTC fields against the device clock.

### `Deviation`

Late / Early / OnTime, with the human label pre-formatted.

```proto
message Deviation {
  enum Type { ON_TIME = 0; LATE = 1; EARLY = 2; }
  Type type = 1;
  optional int32 minutes = 2;          // absolute value; only set when LATE/EARLY
  optional string label = 3;           // "3 mins late"
}
```

### `ServiceAlert`

```proto
message ServiceAlert {
  string id = 1;                       // for cross-leg dedup
  string heading = 2;                  // shown collapsed
  string body = 3;                     // shown expanded
  AlertPriority priority = 4;          // for sort
}

enum AlertPriority {
  ALERT_PRIORITY_UNSPECIFIED = 0;
  LOW = 1;
  NORMAL = 2;
  HIGH = 3;
}
```

### `WalkSegment`

```proto
message WalkSegment {
  int32 duration_seconds = 1;
  string display_duration = 2;         // pre-formatted "5 mins"
  enum Position { POSITION_UNSPECIFIED = 0; BEFORE = 1; AFTER = 2; IDEST = 3; }
  Position position = 3;
}
```

---

## §2. Endpoint messages

Each endpoint returns one root message, composed from the shared types above plus light per-screen wrappers.

### 2.1 `GET /v1/screens/trip-results`

Powers TimeTableScreen.

```proto
message TripResultsResponse {
  repeated JourneyCard journeys = 1;
}

message JourneyCard {
  string journey_id = 1;               // stable key for "track this journey"
  StopTime origin = 2;                 // first leg origin — client computes "in N mins" from origin.estimated_utc
  StopTime destination = 3;            // last leg destination
  string travel_duration = 4;          // "25 min" — duration, not clock-relative
  optional string total_walk_duration = 5;  // "3 mins" if any walking — duration, not clock-relative
  repeated TransitLine lines = 6;      // badge per transit leg
  optional Deviation deviation = 7;
  int32 service_alert_count = 8;
  repeated Leg legs = 9;
}

message Leg {
  oneof kind {
    TransitLeg transit = 1;
    WalkLeg walk = 2;
  }
}

message TransitLeg {
  string leg_id = 1;
  TransitLine line = 2;
  string display_text = 3;             // "Burwood to Liverpool" — resolveServiceDisplayText already applied
  repeated StopTime stops = 4;
  string duration = 5;                 // "12 min"
  optional WalkSegment interchange = 6;
  repeated ServiceAlert alerts = 7;
  optional string realtime_trip_id = 8;  // included only when journey is trackable
  optional string transportation_id = 9; // tracking deep-link key
}

message WalkLeg {
  string leg_id = 1;
  int32 duration_seconds = 2;
  string display_duration = 3;         // "5 mins"
}
```

**Sizing estimate** (6-journey response): ~10–15 KB on the wire vs ~80–150 KB raw NSW JSON, ~14–25 KB current
pass-through protobuf. Screen-shaping cuts another ~30–40% on top of protobuf.

### 2.2 `GET /v1/stops/{stop_id}/departures`

Powers the departure board.

```proto
message DepartureBoardResponse {
  StopRef stop = 1;                    // for header
  repeated DepartureRow departures = 2;
  string fetched_at = 3;               // ISO-8601 UTC; client uses for "data as of"
}

message DepartureRow {
  TransitLine line = 1;
  string destination = 2;              // "Central via Strathfield" — pre-resolved
  StopTime departure = 3;              // contains time, real-time flag, delay
  optional string platform = 4;        // overrides StopRef.platform if different per departure
  // Client renders "Today" / "Tomorrow" / "Mon 25 Sep" from departure.scheduled_utc against local clock.
  // Not shipped — the boundary changes at midnight, so a server-rendered label goes stale.
}
```

No BFF cache — see §3 "Caching policy at indie scale". Client controls polling cadence (today: 30s while a stop card is expanded) via Firebase RC; server protects NSW with per-IP rate limit + daily budget.

### 2.3 `GET /v1/parking/facilities`

Replaces Firebase Remote Config `NSW_PARK_RIDE_FACILITIES`. List rarely changes — 24h client cache.

```proto
message ParkingFacilitiesResponse {
  repeated ParkingFacility facilities = 1;
}

message ParkingFacility {
  string facility_id = 1;
  string display_name = 2;             // "Park&Ride - " prefix already stripped
  StopRef nearest_stop = 3;
  string suburb = 4;
  string address = 5;
  optional LatLng position = 6;
}
```

### 2.4 `GET /v1/parking/facilities/{facility_id}/availability`

Live-ish occupancy.

```proto
message ParkingAvailabilityResponse {
  string facility_id = 1;
  int32 spots_total = 2;
  int32 spots_available = 3;
  int32 percentage_full = 4;           // 0–100
  string fetched_at = 5;               // ISO-8601 UTC
  string display_fetched_at = 6;       // "11:30 AM"
}
```

No BFF cache — see §3 "Caching policy at indie scale". The KRAIL app already cools down per Firebase RC (`NSW_PARK_RIDE_PEAK_TIME_COOLDOWN=120s`, `_NON_PEAK_TIME_COOLDOWN=600s`); BFF caching on top would diverge from the RC value and create stale-data confusion. Server protects NSW with per-IP rate limit + daily budget.

### 2.5 Journey detail + live overlay (the future-merged Track + Map screen)

The user has confirmed TrackTripScreen and JourneyMapScreen will merge into one screen. The right shape is **two
endpoints** polled independently:

- Static journey detail: refreshed every ~60s while screen visible.
- Live overlay (vehicle positions + delays): refreshed every ~30s while map subscribed.

#### Tracking deep link — shrink from ~300–530 chars to ~150–200

Today the KRAIL app encodes the entire journey state into the deep link
(`feature/track/state/.../TripDeepLinkEncoder.kt`):

```
https://ksharma-xyz.github.io/trip?d=<base64url(JSON of TripDeepLink)>
```

`TripDeepLink` carries: from/to stop IDs + names, departure UTC, every leg's `transportationId` and product class,
excluded product classes. JSON-encoded, base64url-encoded, embedded in `?d=`. Real-world payload is **300–530 chars**
depending on leg count and station name length.

The reason it's that big today: the app must recover the journey *without server help* — names for first-paint UI before
the API call returns, leg IDs for strict matching against NSW's response (so a re-search returns the same journey, not a
sibling departure), excluded modes so a re-search filters identically.

With the BFF in the loop, all the recipient screen actually needs to find the journey is **(origin, destination,
departure_utc)** — that's enough for the existing fallback matcher in `TripResponseMapper.findMatchingJourney`
(±60s tolerance + same destination). Leg IDs are a *strict-matching hint*; names are a *first-paint nicety*.

**Recommended new format** (used going forward):

```
https://ksharma-xyz.github.io/trip?o=10101100&d=10102099&dep=2025-04-19T22:26:00Z&fn=Seven%20Hills&tn=Wynyard
```

- `o`, `d` — origin / destination stop IDs (5–7 chars each)
- `dep` — departure UTC (24 chars, fixed)
- `fn`, `tn` — short station names for first-paint, *optional*; can be dropped for absolute minimum
- `legs` — *optional*, comma-separated `transportationId` list; only included for confidence on noisy days

Total ~150–200 chars typical, ~110 with names dropped. **50–65% smaller** than today.

**Dual-format support** (the user's instinct here is right):

The KRAIL app should *parse* both formats indefinitely — old deep links shared on social or saved by users keep working
even after the BFF flag flips:

```kotlin
fun decode(url: Url): TripDeepLink? = when {
    url.parameters["d"] != null -> decodeLegacy(url.parameters["d"]!!)   // base64 JSON
    url.parameters["o"] != null -> decodeCompact(url.parameters)          // new
    else -> null
}
```

The app should *generate* only the new format (after the BFF flag is on for tracking). The `BFF_USE_FOR_TRIP` flag (or a
new `BFF_USE_FOR_TRACKING` if you want decoupled rollouts) gates which encoder runs.

**What if the BFF is down when an old or new deep link is opened?** Either format degrades the same way: the app falls
back to its current "call NSW directly with these IDs and recover via `findMatchingJourney`" path. The new format
carries the same minimum identifiers (origin, destination, departure_utc) the fallback needs — server presence is not
required to *interpret* the link, only to optimise the lookup.

App-side guidance for this migration is captured in [KRAIL_INTEGRATION.md](KRAIL_INTEGRATION.md) §5.5 and §11.

This matches the existing `TrackingConfig` cadence and lets the client poll each at its natural rhythm without coupling.

#### 2.5a `GET /v1/journey/{journey_key}` — static detail

```proto
message JourneyResponse {
  string journey_id = 1;
  StopRef from = 2;
  StopRef to = 3;
  StopTime origin = 4;
  StopTime destination = 5;
  string travel_duration = 6;
  Deviation deviation = 7;
  bool is_arrived = 8;                 // computed server-side from estimated arrival vs now
  repeated Leg legs = 9;               // same Leg type as TripResultsResponse (reuse)
  optional MapBundle map = 10;         // route polylines + stop coordinates
}

message MapBundle {
  repeated MapLeg legs = 1;
  repeated MapStop stops = 2;
}

message MapLeg {
  string leg_id = 1;
  TransitLine line = 2;                // unset/UNSPECIFIED mode = walking leg
  repeated LatLng path = 3;            // route polyline (from NSW coords[])
}

message MapStop {
  StopRef stop = 1;
  enum Kind { KIND_UNSPECIFIED = 0; ORIGIN = 1; DESTINATION = 2; INTERCHANGE = 3; REGULAR = 4; }
  Kind kind = 2;
}
```

Reusing `Leg` here means the journey-detail screen and the trip-results card share the exact same leg shape — write the
rendering once.

#### 2.5b `GET /v1/journey/{journey_key}/live` — overlay

```proto
message JourneyLiveResponse {
  string journey_id = 1;
  string fetched_at = 2;                       // ISO-8601 UTC
  optional string upstream_last_modified = 3;  // for client-side If-None-Match-style skip
  repeated VehicleSnapshot vehicles = 4;       // one per active transit leg
  repeated StopDelay stop_delays = 5;
}

message VehicleSnapshot {
  string leg_id = 1;
  LatLng position = 2;
  optional float bearing = 3;
  enum Status { STATUS_UNSPECIFIED = 0; INCOMING_AT = 1; STOPPED_AT = 2; IN_TRANSIT_TO = 3; }
  Status status = 4;
  int64 last_updated_epoch_sec = 5;
}

message StopDelay {
  string stop_id = 1;
  int32 delay_seconds = 2;             // signed; negative = early
}
```

When live tracking moves to BFF (deferred per Modernization Plan §Phase 2), this endpoint reads from a server-side cache
populated by a single GTFS-RT poller per feed — one BFF poll feeds N clients. The 4-tier vehicle-matching logic and
feed-name lookup-by-iconId currently in `GtfsRealtimeMatcher` move server-side.

### 2.6 `GET /v1/data/stops/manifest` and `/v1/data/stops/{version}.pb`

Already specified in [MODERNIZATION_PLAN.md](MODERNIZATION_PLAN.md) §1.1. The `.pb` proto:

```proto
message StopsDataset {
  string version = 1;                  // semver or YYYYMMDD
  string generated_at = 2;             // ISO-8601 UTC
  string attribution = 3;              // "Data © Transport for NSW (CC BY 4.0)"
  repeated DatasetStop stops = 4;
}

message DatasetStop {
  string stop_id = 1;                  // "NSW:200060"
  string name = 2;                     // disassembledName
  optional LatLng position = 3;
  repeated TransportMode modes = 4;    // for filter chips
}
```

---

## §3. What BFF pre-computes (the work that moves off the app)

Everything in this list is in an app mapper today and should leave the app:

| Computation                           | Today (KRAIL app)                                       | Tomorrow (KRAIL-BFF)                                                      |
|---------------------------------------|---------------------------------------------------------|---------------------------------------------------------------------------|
| Line color resolution                 | `NswTransportLine` lookup (~46 entries) → mode fallback | Server holds the table; ships hex per `TransitLine`                       |
| Mode → icon name                      | `TransportMode.iconName` mapping                        | Server ships icon name                                                    |
| Platform / Stand / Wharf extraction   | mode-specific regex in `DepartureMonitorMapper`         | Server runs regex once; ships clean string in `StopRef.platform`          |
| Display text ("Burwood to Liverpool") | `resolveServiceDisplayText`                             | Server resolves once; ships as `display_text`                             |
| HH:MM AEST formatting                 | `utcToLocalDateTimeAEST().toHHMM()`                     | Server formats; client still gets UTC for clock-driven re-renders         |
| Deviation label ("3 mins late")       | computed in mapper                                      | Server pre-computes                                                       |
| Service alert dedup                   | mapper iterates legs                                    | Server dedupes once                                                       |
| Park & ride availability math         | `totalSpots − sum(occupancy)`                           | Server computes                                                           |
| GTFS-RT vehicle ↔ leg matching        | `GtfsRealtimeMatcher` 4-tier match                      | Server matches; ships only the matched vehicle per leg                    |
| Feed selection (iconId → feed name)   | `feedNamesForTransportation`                            | Server-side routing                                                       |

**Stays client-side (correctly):**

- "Approaching" countdown (1 Hz tick from device clock)
- Relative-to-clock strings: "in 5 mins", "3 mins ago", "Today" / "Tomorrow" — derived from `*_utc` fields against the
  device clock at render time (the server never tries to ship these because they go stale within seconds)
- `currentStopIndex` / progress bar (depends on local time)
- Map camera bounds and zoom (depends on viewport)
- "Past stop" greying (depends on local time)
- Theme / dark-mode adaption of color hex (client owns the theme — the hex is the input)

### Caching policy at indie scale

**The BFF doesn't cache upstream responses.** Two protections are sufficient at this user count:

1. **App-side polling cadence** — the KRAIL app already throttles its own polling via Firebase Remote Config. Today
   that's `NSW_PARK_RIDE_PEAK_TIME_COOLDOWN=120s` / `NON_PEAK_TIME_COOLDOWN=600s` for parking; departures poll every
   30s while a card is expanded. These flags are tunable without an app release.
2. **BFF defences** — per-IP rate limit (5 RPS / 10 burst) catches a single misbehaving client; the daily NSW call
   budget (10 000/day, Sydney midnight reset) catches aggregate misbehaviour.

Why no cache: at ~tens of paid users, concurrent overlap on the same stop / facility is rare. The win from caching
(saving NSW calls when two clients hit the same key in the cache window) is small, while the cost (extra state, TTL
tuning, staleness debugging, divergence from the RC-controlled cooldown) is real.

Threshold to revisit: **~500+ paid users with concurrent activity**. At that point a 15s departures cache pays off
because popular stops (Central, Town Hall, Wynyard) get hit by many clients simultaneously. Parking probably never
needs a cache — the cooldowns are already long enough.

When BFF caching does land, the client's cooldown stays the source of truth — server cache TTL is set *shorter* than
client cooldown so the server never returns data the client expects to be fresher.

---

## §4. KMP sharing strategy — one schema, two consumers

The proto files are the contract between KRAIL and KRAIL-BFF. They must live in exactly one place; both repos consume
the same source.

### Proposed layout

```
krail-api-proto/                # new, separate, public repo
├── README.md
├── version.txt                 # SemVer, e.g. "0.1.0"
└── proto/
    ├── core/
    │   ├── lat_lng.proto
    │   ├── transit_line.proto
    │   ├── stop.proto
    │   ├── stop_time.proto
    │   ├── deviation.proto
    │   ├── service_alert.proto
    │   └── walk_segment.proto
    └── api/
        ├── trip_results.proto
        ├── departure_board.proto
        ├── parking.proto
        ├── journey.proto
        └── stops_dataset.proto
```

### How both repos consume it

**KRAIL-BFF** (already uses Wire):

```kotlin
// settings.gradle.kts — vendor via git submodule at krail-api-proto/
wire {
    kotlin { sourcePath { srcDir("$rootDir/krail-api-proto/proto") } }
}
```

**KRAIL** (KMP):

```kotlin
// commonMain depends on the same submodule; Wire emits multiplatform Kotlin
wire {
    kotlin {
        sourcePath { srcDir("$rootDir/krail-api-proto/proto") }
        targets { commonMain }
    }
}
```

Change a proto → both sides regenerate → both compile against the new shape. No drift.

### Three options, ranked

1. **Separate public repo + git submodule in both consumers** (recommended). Cleanest ownership; no Maven publishing
   infra; submodule update is one command; the contract being public is a feature (anyone can read it, including
   auditors of the KRAIL app's network behaviour).
2. **Separate public repo published to GitHub Packages / JitPack as a Maven artifact**. Cleaner for cross-team work;
   overkill for a one-person project.
3. **Embed the proto files inside KRAIL-BFF**, KRAIL points at a path. Simplest now, biggest pain later when the
   contract has independent users — don't.

### Versioning

SemVer at the package level. Adding fields = minor bump. Removing or renaming = major bump (and the BFF must serve the
prior shape for one app version's deprecation window). Each release of `krail-api-proto` tagged in git; both consumers
point at a tag, not a moving branch.

### Public-repo implications

The proto files are public anyway — anyone can decompile the APK and recover them. Keeping `krail-api-proto` public
costs nothing and signals contract stability. Apply the same secret hygiene as the other repos: no hostnames, no real
values, no example data with PII.

---

## §5. Migration: the existing 12 mappers

Each app-side mapper today does two things: (1) strip noise from NSW responses, (2) shape the result for the screen.
With BFF doing both, the app keeps only one tiny mapper per screen: **proto → UI state**, which is mostly passthrough.

### Mapper-by-mapper fate

| Today (KRAIL)                        | Status post-migration                                                              |
|--------------------------------------|------------------------------------------------------------------------------------|
| `TripResponseMapper` (TimeTable)     | Deleted; replaced by trivial `TripResultsResponse → JourneyCardInfo` (passthrough) |
| `TripResponseMapper` (Track variant) | Deleted; replaced by trivial `JourneyResponse → TrackedJourneyDisplay`             |
| `DepartureMonitorMapper`             | Deleted; `DepartureBoardResponse` is already shaped                                |
| `ParkRideMapper`                     | Deleted; availability math moves to BFF                                            |
| `StopResultMapper`                   | Stays (operates on the local stops dataset)                                        |
| `JourneyMapMapper`                   | Reduced to `MapBundle → GeoJSON` (rendering glue)                                  |
| `TrackedJourneyMapMapper`            | Folded into the rendering glue above                                               |
| `JourneyMapFeatureMapper`            | Stays (GeoJSON is a MapLibre concern, not a wire concern)                          |
| `JourneyStopUiMapper`                | Stays (label/value pairs are presentation)                                         |
| `StopResultsMapMapper`               | Stays (rendering glue)                                                             |
| `GtfsRealtimeMatcher`                | Deleted from app; logic lives in BFF when live tracking moves server-side          |
| `DiscoverAnalyticsMapper`            | Stays (analytics, not network)                                                     |

Rough net: 12 mappers → 4 (rendering glue + local search). The NSW response models in the app (`TripResponse`,
`DepartureMonitorResponse`, `CarParkFacilityDetailResponse`, etc.) become deletable once Phase 1 is fully rolled out.

### Migration order (matches Modernization Plan §Phase 1)

1. **Stops dataset** — first; it's the manifest pattern, no auth, removes a build-time data dep.
2. **Departure board** — small payload, easy cohort comparison.
3. **Park & ride availability + facilities list** — small endpoints, tiny risk.
4. **Trip results** — implements `/v1/screens/trip-results`. The existing `/v1/tp/trip` and `/api/v1/trip/plan` are local-dev passthrough scaffolding (never publicly deployed) and get retired in the same PR.
5. **Journey + journey/live** — last; we'll have the rhythm by then for the most complex one.

---

## §6. Open questions for you

Decisions worth making before I start writing proto files:

1. **`stop_id` namespacing** — start with `"NSW:200060"` from day one, or keep raw `"200060"` and namespace later?
   Cheaper to namespace now if `sandook` already stores stop IDs (avoids a future migration).
2. **Proto repo home** — separate `krail-api-proto` (recommended), git submodule inside KRAIL-BFF, or vendored copy?
   Separate repo is the cleanest long-term choice; the only friction is "one more repo to manage."
3. **Pre-formatted strings on the wire** — keep both `display_time` and `scheduled_utc`, or trust the client to format
   from UTC always? I'd keep both for now (cheap bytes, flexible client).
4. **`MapBundle` inside `JourneyResponse`** — bundle the polyline+stops with the journey, or fetch separately on first
   map open? Bundle for now; split later if payload size becomes a concern.
5. **Timezone field** — bake "AEST" into pre-formatted strings (current plan), or include a `timezone` field per
   response so the proto is multi-city-ready? AEST-only for now; add the field in a minor bump when city #2 is real.
6. **Versioning** — package-level SemVer, or per-message field versioning? Package SemVer at indie scale; revisit if
   multiple consumers diverge.

---

## §7. What changes in MODERNIZATION_PLAN.md

Nothing structural. This document defines the schema layer that lives under MODERNIZATION_PLAN.md §1.1–1.3 (Phase 1
endpoints). Once the schema is signed off:

- §1.1 (stops dataset) ships with `StopsDataset`.
- §1.2 (departure board) implements `DepartureBoardResponse`.
- §1.3 (trip results) implements `TripResultsResponse`.
- The future-merged Track + JourneyMap screen ships with `JourneyResponse` + `JourneyLiveResponse`.

A short pointer to this doc gets added to the plan once approved.
