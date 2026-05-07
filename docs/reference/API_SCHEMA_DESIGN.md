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
  string stop_id = 1;             // raw stop ID, e.g. "200060". City is implied by request context (see §6 Q1, Q7).
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
  string scheduled_utc = 2;            // ISO-8601 UTC (the baseline). Client formats per response timezone.
  optional string estimated_utc = 3;   // ISO-8601 UTC, only if real-time differs
  optional int32 delay_seconds = 4;    // signed; negative = early
  bool is_real_time = 5;               // true iff estimated_utc present
}
```

**Principle: time formatting and clock-relative computation are *both* client-side. The server only ships UTC.**

The server never localises timestamps — no `display_time`, no AEST conversion, no `date_label`. It ships ISO-8601 UTC
plus a top-level `timezone` field on each response (e.g. `"Australia/Sydney"`); the client formats per device locale and
that timezone. This locks nothing into one region (multi-city ready) and means clock-tick-driven values ("in 5 mins",
"3 mins ago", the 1 Hz countdown) compute naturally from the same UTC source of truth — no conflict between a
server-snapshotted relative string and the live device clock.

Practical consequences:
- `StopTime` has no `display_time` / `scheduled_display_time` field.
- No response has a `time_to_departure` / `relative_time` / `date_label` field.
- Each response that contains timestamps carries `string timezone = 1` so the client knows which zone to format in.

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
  string timezone = 1;                 // IANA zone, e.g. "Australia/Sydney"; client formats all UTC times in this zone
  repeated JourneyCard journeys = 2;
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
  string timezone = 1;                 // IANA zone for client-side formatting
  StopRef stop = 2;                    // for header
  repeated DepartureRow departures = 3;
  string fetched_at = 4;               // ISO-8601 UTC; client uses for "data as of"
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

No BFF cache — see §3 "Caching policy at indie scale". Client controls polling cadence (today: 30s while a stop card is
expanded) via Firebase RC; server protects NSW with per-IP rate limit + daily budget.

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
  string timezone = 1;                 // IANA zone for client-side formatting of fetched_at
  string facility_id = 2;
  int32 spots_total = 3;
  int32 spots_available = 4;
  int32 percentage_full = 5;           // 0–100
  string fetched_at = 6;               // ISO-8601 UTC
}
```

No BFF cache — see §3 "Caching policy at indie scale". The KRAIL app already cools down per Firebase RC (
`NSW_PARK_RIDE_PEAK_TIME_COOLDOWN=120s`, `_NON_PEAK_TIME_COOLDOWN=600s`); BFF caching on top would diverge from the RC
value and create stale-data confusion. Server protects NSW with per-IP rate limit + daily budget.

### 2.5 Journey detail + live overlay (the future-merged Track + Map screen)

> ⚠ **Provisional design.** Live tracking in KRAIL is not yet public; the feature isn't fully tested and may have
> bugs. The shape below is what the current KRAIL implementation suggests is right; it may change once we test. Treat
> this section as "current best plan" rather than a final contract — keep an open mind on response shape, polling
> cadences, and the static/live split.

The user has confirmed TrackTripScreen and JourneyMapScreen will merge into one screen. The right shape (today's best
guess) is **two endpoints** polled independently:

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

### 2.6 Reference datasets — stops + bus routes

KRAIL search runs against **two** local datasets, not one:

1. **Stops dataset** — for searching by stop name (e.g. "Wynyard"). Schema below.
2. **Bus routes dataset** — for searching by route number (e.g. "333" → all directions of bus 333 with their stop sequences).
   Already in KRAIL today as the bundled `NSW_BUSES_ROUTES.pb` (loaded into sandook tables `NswBusRouteGroups`,
   `NswBusRouteVariants`, `NswBusTripOptions`, `NswBusTripStops` on app launch by `NswBusRoutesManager`). The BFF takes
   over distribution so the app no longer has to ship a multi-MB blob in the binary.

Both are distributed via the same manifest-redirect pattern (`GET /v1/data/{dataset}/manifest` → 302 to GitHub
Releases). Two independent versions; the app can update either independently.

#### Stops dataset
```proto
message StopsDataset {
  string version = 1;                  // YYYYMMDD or semver tag
  string generated_at = 2;             // ISO-8601 UTC
  string attribution = 3;              // "Data © Transport for NSW (CC BY 4.0)"
  repeated DatasetStop stops = 4;
}

message DatasetStop {
  string stop_id = 1;                  // raw NSW stop ID, e.g. "200060". City implied by request context.
  string name = 2;                     // disassembledName
  optional LatLng position = 3;
  repeated TransportMode modes = 4;    // for filter chips
}
```

Endpoint: `GET /v1/data/stops/manifest` → 302 → GitHub Releases manifest JSON.

#### Bus routes dataset
The shape mirrors KRAIL's existing `NswBusRoute.proto` so the migration is mechanical (the build job emits the same
proto; the app keeps its existing decoder + insert-into-sandook flow, just sourced from the BFF manifest instead of
bundled assets):

```proto
message RoutesDataset {
  string version = 1;
  string generated_at = 2;
  string attribution = 3;
  repeated RouteGroup routes = 4;
}

message RouteGroup {
  string route_short_name = 1;         // "333", "T1", "L1"
  repeated RouteVariant variants = 2;
}

message RouteVariant {
  string route_id = 1;                 // e.g. "2504_702"
  string route_name = 2;               // "Blacktown to Seven Hills"
  repeated TripOption trips = 3;
}

message TripOption {
  string trip_id = 1;                  // representative trip for this direction
  string headsign = 2;                 // "Blacktown to Seven Hills"
  repeated string stop_ids = 3;        // ordered, references DatasetStop.stop_id
}
```

Endpoint: `GET /v1/data/routes/manifest` → 302 → GitHub Releases manifest JSON.

The build job (`server/src/main/kotlin/.../tools/BuildStopsDataset.kt` today; companion `BuildRoutesDataset.kt` to be
added) reads GTFS, emits both datasets, publishes a single GitHub Release with `stops.pb`, `routes.pb`,
`stops-manifest.json`, `routes-manifest.json`. App fetches whichever has changed.

**Why two datasets, not one:** the route hierarchy (Group → Variant → Trip → Stops sequence) doesn't compress neatly
into the per-stop record. Embedding `route_short_names` per stop loses the stop sequence per direction, which is what
the search-by-route UX needs. Keep them separate; they're already separate in KRAIL's local DB and proto.

---

## §3. What BFF pre-computes (the work that moves off the app)

Everything in this list is in an app mapper today and should leave the app:

| Computation                           | Today (KRAIL app)                                       | Tomorrow (KRAIL-BFF)                                              |
|---------------------------------------|---------------------------------------------------------|-------------------------------------------------------------------|
| Line color resolution                 | `NswTransportLine` lookup (~46 entries) → mode fallback | Server holds the table; ships hex per `TransitLine`               |
| Mode → icon name                      | `TransportMode.iconName` mapping                        | Server ships icon name                                            |
| Platform / Stand / Wharf extraction   | mode-specific regex in `DepartureMonitorMapper`         | Server runs regex once; ships clean string in `StopRef.platform`  |
| Display text ("Burwood to Liverpool") | `resolveServiceDisplayText`                             | Server resolves once; ships as `display_text`                     |
| Deviation label ("3 mins late")       | computed in mapper                                      | Server pre-computes (English-only — see §6 Q8)                    |
| Service alert dedup                   | mapper iterates legs                                    | Server dedupes once                                               |
| Park & ride availability math         | `totalSpots − sum(occupancy)`                           | Server computes                                                   |
| GTFS-RT vehicle ↔ leg matching        | `GtfsRealtimeMatcher` 4-tier match                      | Server matches; ships only the matched vehicle per leg            |
| Feed selection (iconId → feed name)   | `feedNamesForTransportation`                            | Server-side routing                                               |

**Stays client-side (correctly):**

- "Approaching" countdown (1 Hz tick from device clock)
- Relative-to-clock strings: "in 5 mins", "3 mins ago", "Today" / "Tomorrow" — derived from `*_utc` fields against the
  device clock at render time (the server never tries to ship these because they go stale within seconds)
- **Absolute time formatting** ("11:30 am") — derived from `*_utc` against the response's `timezone` field. Server never
  pre-formats times; client owns locale + timezone (per §6 Q5)
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
4. **Trip results** — implements `/v1/screens/trip-results`. The existing `/v1/tp/trip` and `/api/v1/trip/plan` are
   local-dev passthrough scaffolding (never publicly deployed) and get retired in the same PR.
5. **Journey + journey/live** — last; we'll have the rhythm by then for the most complex one.

---

## §6. Decisions and open questions

The first six are settled (decisions captured below the original recommendation). The last five (Q7–Q11) surfaced during
review and still need answers before the schema is fully locked.

### Q1. `stop_id` namespacing — DECIDED: keep raw IDs

**Recommendation given**: prefix `"NSW:200060"` from day one to avoid future migration.

**Decision**: keep raw IDs (`"200060"`). KRAIL's `sandook` already stores them this way; multi-city support will use
**separate per-city tables in `sandook`**, with the city implied by the table. The BFF wire format mirrors that — city is
implied by request context, not a field prefix.

**Implications**:
- `StopRef.stop_id` and `DatasetStop.stop_id` carry raw IDs; comments updated.
- Multi-city dispatch becomes its own question — see Q7 below.
- The `tools/BuildStopsDataset.kt` "NSW:" prefix line needs to be removed when the routes/stops dataset workflow runs.

### Q2. Proto repo home — DECIDED: separate `krail-api-proto`

**Recommendation given**: separate repo + git submodule in both consumers.

**Decision**: separate `krail-api-proto` repo. Whenever the proto changes, an automated PR is exported to KRAIL-BFF
and KRAIL.

**Implications & TODO**:
- Stand up `krail-api-proto` as a public repo with `proto/core/*.proto` + `proto/api/*.proto`.
- Set up the export-PR automation: a workflow on `krail-api-proto` that on tag/release runs against KRAIL-BFF and KRAIL,
  bumps the submodule pointer, opens a PR to each. Keep the changelog.
- Versioning: SemVer at the package level (see Q6).
- Document the contributor flow in the new repo's README ("change a `.proto` here → PR auto-opens in consumers → reviewers
  there decide if they pin to the new version").

### Q3. Pre-formatted display strings on the wire — DECIDED: client formats from UTC always

**Recommendation given**: keep both `display_time` and `scheduled_utc` (cheap bytes, flexible client).

**Decision**: server **never** localises timestamps. Client formats from UTC plus the response's `timezone` field, every
time. Field-level comments call this out.

**Implications**:
- `StopTime.display_time` and `StopTime.scheduled_display_time` are removed (already done).
- `ParkingAvailabilityResponse.display_fetched_at` removed.
- `WalkSegment.display_duration` and `Deviation.label` are *durations* / *deltas*, not localised timestamps; they stay
  for v1 but see Q8 about future localisation.
- §3 pre-compute table loses the "HH:MM AEST formatting" row.
- `*_utc` fields gain explicit comment that the client formats per response timezone.

### Q4. `MapBundle` inside `JourneyResponse` — DECIDED: bundle, with payload monitoring

**Recommendation given**: bundle the polyline + stops with the static journey response.

**Decision**: bundle. Opening the map is a frequent action; bundling means the static-detail call already has everything
needed for the map view, no second round-trip on first open.

**Implications & TODO**:
- Add server-side observability for payload size on `/v1/journey/{key}` — histogram metric `bff.journey.response_bytes`.
- Add an alert rule (when monitoring lands): trigger if p95 response size exceeds a threshold (e.g. 50 KB) so we know if
  the bundle is getting too big for typical journeys (long routes with many polyline points blow this up — see Q9).
- Plan a path to split if needed: `MapBundle` could become its own endpoint (`/v1/journey/{key}/map`) without breaking
  clients — they'd just call it on map-open instead of reading from the bundled response. Keep the field `optional` in
  the proto to allow this future evolution without a wire break.

### Q5. Timezone field on responses — DECIDED: every response carries `timezone`

**Recommendation given**: AEST baked into pre-formatted strings; add the field when city #2 is real.

**Decision**: include `timezone` from day one. App always formats locally; server never localises.

**Implications**:
- Every response shape that carries timestamps has `string timezone = 1;` (IANA zone name like `"Australia/Sydney"`).
- The KRAIL app's existing `DateTimeHelper.utcToLocalDateTimeAEST` becomes parameterised on the response timezone.
- No measurable wire cost (~20 bytes per response).

### Q6. Versioning — DECIDED: package-level SemVer

**Recommendation given**: package-level SemVer; revisit if consumers diverge.

**Decision**: package-level SemVer. The user wasn't sure of the trade-off; documenting it here:

**The trade-off, made explicit**:

| Option | Pros | Cons |
|---|---|---|
| **Package SemVer** (chosen) | One number to think about; matches how libraries are published; works with submodule pinning. | A breaking change in *one* message bumps the whole package's major version, even though most consumers don't use that message. |
| **Per-message field versioning** (alternative) | Each message evolves independently; consumers only feel breakage where they actually use the changed message. | Much more book-keeping; no clean way to publish a "single version" of the contract; common in big ecosystems (Google, Stripe) where the proto registry has hundreds of services. |

At indie scale with one server + one app consumer, package SemVer is right. Revisit only if a third consumer joins
*and* the divergence becomes painful.

**Implications**:
- `krail-api-proto/version.txt` follows SemVer.
- Adding a new field to an existing message → minor bump.
- Adding a new message → minor bump.
- Removing or renaming a field → major bump (avoid; if needed, deprecate first).

---

### Q7. Multi-city request dispatch — OPEN

Q1 chose raw stop IDs with city implied by context. So how does the BFF know the city of a request?

**Options**:
- **Default to NSW only** (do nothing for now). Fine until city #2 is real.
- **Path prefix**: `/v1/nsw/stops/{id}/departures`, `/v1/mel/stops/{id}/departures`. Clean URLs; routing logic obvious;
  natural fit for OpenAPI docs.
- **Query param**: `?city=nsw`. Easy to add; less obvious in URLs.
- **Header**: `X-Krail-City: nsw`. Hidden from URL bar; harder to debug.

**Recommendation**: stay default-NSW for v1. When city #2 becomes real, add path prefix (`/v1/{city}/...`) and version
the new shape as `/v2/`. This matches the existing v1/v2 NSW versioning we already pass through for GTFS-RT.

### Q8. Localisation of non-time English strings — OPEN

Q5 settled timestamps (client formats). What about other server-formatted English strings?

- `Deviation.label` ("3 mins late") — generated by the BFF.
- `WalkSegment.display_duration` ("5 mins") — generated by the BFF.
- `TransitLeg.display_text` ("Burwood to Liverpool") — comes from NSW (`disassembledName` / `description`); not invented
  by us, but English.

**Options**:
- **Pre-format on server (current)**: simple; works for English-only KRAIL.
- **Ship structured + client formats**: drop `Deviation.label`, ship `Deviation.minutes` only; drop
  `WalkSegment.display_duration`, ship `duration_seconds` only. Client formats per locale.

**Recommendation**: keep pre-formatted strings for v1 (English-only KRAIL today), but mark this as the second concern to
revisit alongside Q5 if/when localisation lands. Both `Deviation.label` and `display_duration` already have a structured
sibling (`minutes`, `duration_seconds`) so the wire change to drop them later is non-breaking.

### Q9. Map polyline simplification — OPEN

NSW returns full lat/lon polylines for routes (`legs[].coords`). A long suburban-rail trip can be 1000+ points, ~30 KB
just for one leg's polyline. Bundled in `JourneyResponse.MapBundle` (per Q4), this can dominate payload.

**Options**:
- **Pass through as-is**: simplest; large payload risk.
- **Server-side simplify (Douglas-Peucker)**: ~80–95% point reduction with imperceptible visual loss at typical zoom
  levels. Cheap to compute server-side once per journey response.
- **Encode as polyline string** (Google's `polyline5` format): same number of points but ~6× tighter encoding than
  repeated `LatLng` messages. Standard in mapping APIs.

**Recommendation**: combine both. Apply Douglas-Peucker with a tolerance like 5 m, and encode the result as a polyline5
string in the proto:
```proto
message MapLeg {
  string leg_id = 1;
  TransitLine line = 2;
  string encoded_path = 3;             // polyline5; tolerate-5m simplified
}
```
Defer until payload monitoring (Q4) shows actual sizes — if a typical bundle stays under 20 KB, simplify is premature.

### Q10. Service alert content sanitisation — OPEN

NSW returns alert `subtitle` and `content` as strings. Spot checks suggest they sometimes contain HTML
(`<a href="...">`, `<br>`, etc).

**Options**:
- **Pass through**: client must render carefully or the markup leaks. XSS not a concern for native apps but rendering
  may be ugly.
- **Strip HTML**: convert to plain text; loses links.
- **Sanitise**: keep a safe subset (e.g. `<a>`, `<br>`, `<b>`, `<i>`); use jsoup-like Whitelist.

**Recommendation**: sanitise on the BFF before shipping. Servers shouldn't ship rich text the client can't trust to
render uniformly across platforms. Keep simple: text + extracted URLs as a separate `repeated string urls = N;` field.
Or for v1 just strip everything and ship plain text. Decide.

### Q11. Routes dataset shape — OPEN (provisional Yes)

§2.6 introduces a `RoutesDataset` proto modeled on KRAIL's existing `NswBusRoute.proto`. Two questions:

1. **Reuse the NSW shape** as-is (`NswBusRouteList` → `RoutesDataset`, `NswBusRouteGroup` → `RouteGroup`, etc.) or
   redesign for the multi-city plan? The existing schema works and the migration is mechanical; redesigning costs
   nothing functional but lets us bake in `transport_mode` per group up front (today's proto only carries
   `transportMode = "Buses"` at the top level).
2. **Routes dataset versioning vs stops dataset versioning** — separate manifests so they can update independently?
   Yes per the design above. Confirm.

**Recommendation**: redesign mildly to add `TransportMode mode = 2` per `RouteGroup` so trains / metro / ferries fit the
same schema (KRAIL today only ships bus routes; if we generalise, this matters). Keep separate manifests.

---

---

## §7. What changes in MODERNIZATION_PLAN.md

Nothing structural. This document defines the schema layer that lives under MODERNIZATION_PLAN.md §1.1–1.3 (Phase 1
endpoints). Once the schema is signed off:

- §1.1 (stops dataset) ships with `StopsDataset`.
- §1.2 (departure board) implements `DepartureBoardResponse`.
- §1.3 (trip results) implements `TripResultsResponse`.
- The future-merged Track + JourneyMap screen ships with `JourneyResponse` + `JourneyLiveResponse`.

A short pointer to this doc gets added to the plan once approved.
