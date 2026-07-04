# KRAIL Screen Data Inventory

> What each KRAIL screen actually displays today, mapped field-by-field to its NSW Open Data source. This is the
> reference for designing screen-shaped BFF responses (see [API_SCHEMA_DESIGN.md](API_SCHEMA_DESIGN.md)).

KRAIL today has 12 mappers (`TripResponseMapper`, `DepartureMonitorMapper`, `ParkRideMapper`, `StopResultMapper`,
`JourneyMapMapper`, `TrackedJourneyMapMapper`, `GtfsRealtimeMatcher`, `JourneyMapFeatureMapper`, `JourneyStopUiMapper`,
`StopResultsMapMapper`, `DiscoverAnalyticsMapper`, plus a track-variant `TripResponseMapper`) that strip NSW responses
down to what each screen actually shows. The BFF should do this work server-side so the app receives data already in
display shape.

---

## How to read this doc

For each screen:

- **File** — composable implementing the screen
- **Endpoints today** — which NSW APIs it calls
- **Polling** — when and how often
- **Mapper** — which app-side mapper transforms the response
- **Renders** — UI element → source field path → transformation
- **Drops** — fields NSW sends that the app never shows

Each "renders" row = a thing on screen the BFF response must carry.

---

## SavedTripsScreen (home)

**File:** `feature/trip-planner/ui/.../savedtrips/SavedTripsScreen.kt`

The home screen composes three blocks: saved trip cards (local DB), an embedded departure-board accordion, and an
embedded park & ride section.

### Block A — Saved trip card

**Source:** local `sandook` DB only; no API call per card.

| UI element             | Source                                 |
|------------------------|----------------------------------------|
| From / to stop name    | `Trip.fromStopName`, `Trip.toStopName` |
| Optional label / emoji | `StopLabel.label`, `StopLabel.emoji`   |

### Block B — Departure board (per-stop accordion)

**Endpoint today:** `GET /v1/tp/departure_mon` (NSW)
**Polling:** 30s while card expanded; relative-time text re-renders every 10s without an API call.
**Mapper:** `DepartureMonitorMapper`

| UI element                     | Source field path                                                  | Transformation                                                      |
|--------------------------------|--------------------------------------------------------------------|---------------------------------------------------------------------|
| Line number                    | `stopEvents[].transportation.disassembledName` ∨ `number` ∨ `name` | first non-null                                                      |
| Line color                     | (line name + product class)                                        | `NswTransportLine` lookup → `mode.colorCode` fallback → bus default |
| Mode name                      | `transportation.product.cls`                                       | `NswTransportMode.fromProductClass`                                 |
| Destination                    | `transportation.destination.name`, `description`, `product.cls`    | `resolveServiceDisplayText`                                         |
| Departure time                 | `departureTimeEstimated` ∨ `departureTimePlanned`                  | UTC → AEST → "11:30 am"                                             |
| Relative time                  | (above)                                                            | "in 5 mins"                                                         |
| Real-time flag                 | `departureTimeEstimated != null`                                   | bool                                                                |
| Scheduled time (strikethrough) | `departureTimePlanned`                                             | AEST HH:MM, null if no deviation                                    |
| Delay minutes                  | (estimated − planned)                                              | int (negative if early)                                             |
| Platform / Stand / Wharf       | `location.properties.platformName` ∨ `location.disassembledName`   | mode-specific regex (Train→"Platform N", Bus→"Stand X", Ferry→"F1") |
| Date label                     | (departure time)                                                   | "Today" / "Tomorrow" / "Mon 25 Sep"                                 |

**Drops:** `location.parent.*`, `location.name` (use disassembledName), `location.modes[]`, `transportation.id`,
`transportation.operator.*`, `transportation.product.iconId`, `Download[]`, version objects.

### Block C — Park & ride card

**Endpoint today:** `GET /v1/carpark?facility={id}` (NSW)
**Polling:** none on screen; refreshed on app launch + when card expands. Cooldowns from Firebase RC: 120s peak / 600s
off-peak.
**Mapper:** `ParkRideMapper`

| UI element           | Source                                    | Transformation              |
|----------------------|-------------------------------------------|-----------------------------|
| Facility name        | `facilityName`                            | strip "Park&Ride - " prefix |
| Spots available      | `spots − sum(zones[].occupancy.total)`    | int, ≥ 0                    |
| % full               | (occupied × 100) / total                  | int                         |
| Last updated         | `messageDate`                             | "11:30 AM"                  |
| Suburb / address     | `location.suburb`, `location.address`     | passthrough                 |
| Coords (when needed) | `location.latitude`, `location.longitude` | parse to double             |

**Drops:** zone breakdown, `tsn`, `tfnswFacilityId`, `parentZoneId`, `occupancy.loop`, granular `transients` /
`monthlies` / `open_gate` once summed.

---

## SearchStopScreen

**File:** `feature/trip-planner/ui/.../searchstop/SearchStopScreen.kt`
**Endpoint today:** `GET /v1/tp/stop_finder` (NSW); also reads `sandook` (bundled stops in app).
**Polling:** debounced 500ms per keystroke.
**Mapper:** `StopResultMapper`

**Confirmed scope:** search stays **local** in the app. The BFF distributes a versioned stops dataset via a manifest
pattern (per [MODERNIZATION_PLAN.md](../archive/MODERNIZATION_PLAN.md) §1.1). This screen's data therefore comes from the local
dataset only — no per-keystroke BFF call.

| UI element | Source                         | Transformation                                    |
|------------|--------------------------------|---------------------------------------------------|
| Stop name  | `locations[].disassembledName` | passthrough                                       |
| Stop ID    | `locations[].id`               | passthrough (skipped if null)                     |
| Mode chips | `locations[].productClasses[]` | filter by selected modes; sort by `mode.priority` |

**Drops:** full `name`, `parent.*`, `coord` (kept only for distance sort if user grants location), `matchQuality`,
`isBest`, `properties.*`.

For the **stops dataset proto** (the file shipped via manifest), the same fields above plus `coord`.

---

## TimeTableScreen (trip results)

**File:** `feature/trip-planner/ui/.../timetable/TimeTableScreen.kt`
**Endpoint today:** `GET /v1/tp/trip` (NSW)
**Polling:** none (search-once); "Load More" / "Show Previous" buttons fetch with time offset.
**Mapper:** `TripResponseMapper` → `TimeTableState.JourneyCardInfo`

### Card header (always shown)

| UI element                             | Source field path                                                        | Transformation                                     |
|----------------------------------------|--------------------------------------------------------------------------|----------------------------------------------------|
| `timeText` ("in 5 mins")               | `legs[0].origin.departureTimeEstimated`                                  | relative string                                    |
| `originTime` ("11:30 am")              | (estimated ∨ planned)                                                    | UTC → AEST HH:MM                                   |
| `destinationTime` ("11:55 am")         | `legs[-1].destination.arrivalTimeEstimated` ∨ planned                    | UTC → AEST HH:MM                                   |
| `scheduledOriginTime` (strikethrough)  | `legs[0].origin.departureTimePlanned`                                    | HH:MM, null if no deviation                        |
| `travelTime` ("25 min")                | dest − origin                                                            | duration string                                    |
| `totalWalkTime` ("3 mins")             | `legs[].footPathInfo[].duration` (where `footPathInfoRedundant != true`) | summed and formatted                               |
| `platformText` / `platformNumber`      | `legs[0].origin.disassembledName`                                        | regex extract "Platform N" / "Stand X" / "Wharf Y" |
| `transportModeLines[]` (badge per leg) | `legs[].transportation.product.productClass` + `disassembledName`        | mode + line name + color                           |
| `departureDeviation`                   | est vs planned diff                                                      | OnTime / Late("3 mins late") / Early               |
| `totalUniqueServiceAlerts`             | `legs[].infos[]`                                                         | dedupe by ID, count                                |

### Expanded leg (when card tapped)

For **transit leg**:

| UI element                            | Source                                                                                                             | Transformation                                 |
|---------------------------------------|--------------------------------------------------------------------------------------------------------------------|------------------------------------------------|
| Display text ("Burwood to Liverpool") | `transportation.destination.name` (Train/Metro) ∨ `transportation.description`                                     | `resolveServiceDisplayText`                    |
| Stop list — name + time               | `stopSequence[].disassembledName` ∨ `name`, plus `dep*Estimated` ∨ `dep*Planned` ∨ `arr*Estimated` ∨ `arr*Planned` | first non-null → UTC→AEST HH:MM                |
| Stop wheelchair flag                  | `stopSequence[].properties.wheelchairAccess`                                                                       | bool                                           |
| Leg duration ("12 min")               | `duration` ∨ first/last stop diff                                                                                  | duration string                                |
| Walk interchange                      | `footPathInfo[0].position`, `footPathInfo[0].duration`                                                             | enum (BEFORE/AFTER/IDEST) + formatted duration |
| Service alerts                        | `infos[]`                                                                                                          | `subtitle` → heading, `content` → body         |
| Tracking key (Star + Track buttons)   | `transportation.id` + `transportation.properties.realtimeTripId`                                                   | concat — used on Track screen                  |

For **walking leg**: only `duration` ("5 mins").

**Drops:** `coords` (only used in JourneyMapScreen — kept as map-only field), `distance`, `niveau`, `modes`, full
`parent.*`, `transportation.operator.*`, `product.iconId`, `infos.version`, `infos.timestamps.*`,
`footPathInfo.footPathElem`, `systemMessages`, `Download[]`, base-timetable times.

---

## JourneyMapScreen

**File:** `feature/trip-planner/ui/.../journeymap/JourneyMapScreen.kt`
**Endpoint today:** none — reuses TripResponse from TimeTableScreen for the selected journey.
**Mappers:** `JourneyMapMapper` → `JourneyMapFeatureMapper` (GeoJSON for MapLibre)

| UI element                                                  | Source                                                                         | Transformation                                         |
|-------------------------------------------------------------|--------------------------------------------------------------------------------|--------------------------------------------------------|
| Polyline per leg                                            | `legs[].coords[]` (preferred) ∨ `legs[].interchange.coords[]` ∨ stop-connector | `[lat,lon] → LatLng`                                   |
| Polyline color                                              | line name + mode                                                               | `NswTransportLine` ∨ mode color, walking = grey dashed |
| Stop markers (origin / destination / interchange / regular) | `stopSequence[].coord` ∨ `parent.coord`                                        | filter stops missing coordinates                       |
| Stop callout (name, line, platform, arr/dep time)           | (per-stop fields above)                                                        | UTC → AEST HH:MM                                       |
| Camera bounds                                               | calculated from features                                                       | client-side only                                       |
| Freshness badge ("Updated X mins ago")                      | client-side timer                                                              | shows after 1 min, "scheduled times only" after 5 min  |

**Drops:** all non-coordinate metadata not in the callout.

**Future merge with TrackTripScreen:** confirmed by user. Implication for the BFF: a single static-journey endpoint
returning `legs[]` + map polylines + stop coords, paired with a separate live-overlay endpoint polled on its own
cadence. See [API_SCHEMA_DESIGN.md](API_SCHEMA_DESIGN.md) §5.

---

## TrackTripScreen (live tracking)

**File:** `feature/trip-planner/ui/.../tracktrip/TrackTripScreen.kt`
**Endpoints today:**

- `GET /v1/tp/trip` (NSW) — refreshed every **60s** (`TrackingConfig.POLL_INTERVAL_MS`)
- `GET /v[12]/gtfs/realtime/{feed}` + `GET /v2/gtfs/vehiclepos/{feed}` (NSW) — polled every **30s** while map
  subscribed (`GTFS_RT_POLL_INTERVAL_MS`)

**Mappers:** `TripResponseMapper` (track variant) → `TrackedJourneyDisplay`; `GtfsRealtimeMatcher` →
`LiveVehiclePosition` + `Map<stopId, delaySeconds>`

### Static journey data (from `/v1/tp/trip`)

| UI element                       | Source                                              | Transformation                    |
|----------------------------------|-----------------------------------------------------|-----------------------------------|
| from / to stop ID + name         | deep link / TripResponse                            | passthrough                       |
| `originTime` / `destinationTime` | `legs[0].origin.dep*` / `legs[-1].destination.arr*` | UTC → AEST HH:MM                  |
| `scheduledOriginTime`            | planned (if differs)                                | HH:MM, null if on-time            |
| `travelTime`                     | dest − origin                                       | duration string                   |
| `departureDeviation`             | est vs planned                                      | OnTime / Late(text) / Early(text) |

For each `TrackedLeg.Transport`:
| UI element | Source | Transformation |
|---|---|---|
| `transportMode` | `transportation.product.productClass` | `modeFromProductClass` |
| `lineName` | `transportation.disassembledName` | passthrough |
| `lineColorCode` | line + mode lookup | `NswTransportLine` ∨ mode |
| `headsign` | `transportation.destination.name` | passthrough |
| `stops[]` per stop | `name`, `scheduledTime`, `estimatedTime` (only if differs), `utcTime`, `scheduledUtcTime`, `lat`,
`lon` | TimeTable-style + UTC retained |
| `realtimeTripId` | `transportation.properties.realtimeTripId` | passthrough — needed for GTFS-RT matching |
| `routePathCoordinates[]` | `legs[].coords[]` | LatLng list for polyline |

### Live overlay (from GTFS-RT)

| UI element               | Source                                                            | Transformation                                                                         |
|--------------------------|-------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| Vehicle position per leg | `VehiclePosition.position.{latitude,longitude,bearing}`           | match by `realtime_trip_id` (then endsWith, then `route_id`, then pre-trip same route) |
| Vehicle status           | `VehiclePosition.current_status`                                  | enum INCOMING_AT / STOPPED_AT / IN_TRANSIT_TO                                          |
| Last updated epoch       | `VehiclePosition.timestamp`                                       | seconds                                                                                |
| Per-stop delay           | `TripUpdate.stop_time_update[].arrival.delay` ∨ `departure.delay` | seconds (signed)                                                                       |
| Last-modified            | upstream `Last-Modified` header                                   | ISO-8601, optional                                                                     |

**Polling pauses when screen left** (Flow `WhileSubscribed(5000)`); resumes on return; clears when `isArrived = true`.
The 4-tier vehicle-matching logic + feed-name lookup-by-`iconId` (15+ feeds: `sydneytrains`, `metro`, `nswtrains`,
`lightrail/cbdandsoutheast`, `buses`, etc.) are excellent candidates to move server-side — the app would then receive
only the matched vehicle for its journey, not the full feed.

---

## ServiceAlertScreen

**File:** `feature/trip-planner/ui/.../alerts/ServiceAlertScreen.kt`
**Endpoint today:** none — consumes `legs[].infos[]` already on hand from TripResponse.

| UI element | Source              | Transformation          |
|------------|---------------------|-------------------------|
| Heading    | `infos[].subtitle`  | passthrough             |
| Body       | `infos[].content`   | passthrough (HTML-safe) |
| Group key  | hashCode of heading | client-only             |

**Drops:** `infos.version`, all `infos.timestamps.*`, IDs (currently); `priority` is in the data but unused — could
drive sorting.

---

## InfoTile (cross-screen banner)

**Source:** Firebase Remote Config payload (NOT NSW). Loaded once at app startup.

| UI element                                                 | Source                   |
|------------------------------------------------------------|--------------------------|
| Dismissal key                                              | `key`                    |
| Title / description                                        | `title`, `description`   |
| Type (CRITICAL_ALERT / INFO / APP_UPDATE / INVITE_FRIENDS) | `type`                   |
| Active window                                              | `startDate`, `endDate`   |
| CTA                                                        | `primaryCta.{text, url}` |

**No NSW dependency.** Could move to BFF later as a centralised content endpoint; out of scope for current plan.

---

## Screens with no NSW data

- DateTimeSelectorScreen — local form state (option / hour / minute / date)
- SettingsScreen, ThemeSelectionScreen, IntroScreen — local prefs
- DiscoverScreen — Firebase RC content cards (not NSW)

---

## Cross-screen patterns (justifies shared types)

These shapes appear repeatedly across the field tables above and become the shared proto messages in the schema design:

| Pattern                                                         | Where it appears                                                                         |
|-----------------------------------------------------------------|------------------------------------------------------------------------------------------|
| Stop (id + name + position + platform + wheelchair)             | TimeTable expanded leg, Track stops, JourneyMap callout, departure board, search results |
| TransitLine (mode + lineName + colorHex + iconName)             | TimeTable badges, departure board, JourneyMap polyline, Track leg header                 |
| StopTime (scheduled + estimated + UTC + delay + display string) | TimeTable card, Track journey, departure board                                           |
| Deviation (Late / Early / OnTime + minutes + label)             | TimeTable header, Track                                                                  |
| ServiceAlert (heading + body + priority)                        | TimeTable, ServiceAlertScreen, Track                                                     |
| LatLng                                                          | JourneyMap polyline, JourneyMap stops, Track vehicle                                     |
| WalkSegment (duration + position)                               | TimeTable interchange, Walk leg                                                          |

---

## productClass → color/icon mapping (where it lives today)

**File:** `core/transport/.../TransportMode.kt` (mode → hex color, mode → icon name) and
`.../nsw/NswTransportLine.kt` (~46 line-specific overrides).

Two-tier lookup currently performed client-side:

1. **Named lines** (T1–T9, F1–F10, L1–L3, BMT, CCN, HUN, SCO, SHL, NLR, Stkn) have line-specific hex colors.
2. **Generic services** fall back to mode color (Train #F6891F, Metro #009B77, LightRail #E4022D, Bus #00B5EF, Coach
   #742282, Ferry #5AB031).

**Icons** are mode-only (no per-line icon variants), referenced by name from the `taj` design system.

**Move to BFF:** ship the same two-tier lookup result as `color_hex` + `icon_name` per `TransitLine` in the response.
App becomes a renderer.

---

## Time formatting (where it lives today)

**File:** `core/date-time/.../DateTimeHelper.kt`. NSW returns ISO-8601 UTC; app converts to AEST and formats:

- `toHHMM()` → "11:30 am"
- `toDepartureRelativeString()` → "in 5 mins" / "3 mins ago"
- `toDepartureDateLabel()` → "Today" / "Tomorrow" / "Mon 25 Sep"

**Move to BFF:** ship both ISO-8601 UTC (so the app can re-render against its ticking clock) **and** pre-formatted
display strings ("11:30 am", "in 5 mins" at response moment). Clients re-render relative time on each tick from the UTC
value.

---

## Polling cadences across the app

| Surface                                 | Cadence                   | Triggered by                                       |
|-----------------------------------------|---------------------------|----------------------------------------------------|
| Departure board accordion               | 30s                       | card expanded                                      |
| Departure board relative-text re-render | 10s timer                 | client-only                                        |
| Track trip detail                       | 60s                       | screen subscribed                                  |
| Track GTFS-RT overlay                   | 30s                       | screen subscribed                                  |
| Park & Ride availability                | on demand                 | card expanded; cooldowns 120s peak / 600s off-peak |
| Stop finder                             | debounced 500ms           | each keystroke                                     |
| Trip search                             | once per submit           | user action                                        |
| Stops dataset refresh                   | on cold start + every 24h | client-side check                                  |

---

## Fields NSW sends that KRAIL never displays (consolidated strip list)

A bandwidth audit at a glance:

**TripResponse:** entire `systemMessages`; `legs[].distance`; `legs[].coords` (kept only for map screens);
`legs[].stopSequence[].niveau`, `.modes`, most of `.parent.*`, `.properties.*` except `wheelchairAccess`;
`legs[].transportation.id` (kept as tracking key only), `.operator.*`, `.product.iconId`;
`legs[].footPathInfo.footPathElem`; `legs[].infos[].version`, all `.timestamps.*`; base-timetable times; `Download[]`.

**StopFinderResponse:** `locations[].matchQuality`, `.isBest`, most of `.parent.*`, `.properties.*`; `coord` only when
distance sort is on.

**DepartureMonitorResponse:** `stopEvents[].location.parent.*`, `.location.name` (use disassembledName),
`.location.modes[]`, `.transportation.operator.*`, `.product.iconId`; `Download[]`; version objects.

**CarParkFacilityDetailResponse:** `tsn`, `tfnswFacilityId`, `zones[].parentZoneId`, granular zone occupancy beyond sum,
`occupancy.loop` (unreliable).

**Estimated bandwidth saving from screen-shaping:** 60–85% per response, on top of the existing 83% protobuf reduction.
See API_SCHEMA_DESIGN.md §1 for per-endpoint sizing.
