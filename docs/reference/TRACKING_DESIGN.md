# Live Trip Tracking — BFF design

> Design for the realtime tracking feature set: live vehicle location,
> per-carriage occupancy, fleet type (Waratah/Tangara/…), stop-by-stop
> progress, and share-a-trip deep links. Supersedes the app-side
> approach in the handover spec (`krail-realtime-integration-spec.md`,
> 2026-06) — reviewed below — and extends the seed sketch in
> [API_SCHEMA_DESIGN.md §2.5b](API_SCHEMA_DESIGN.md).
>
> Status: **design**. Implementation phases at the bottom feed TODO.md.

---

## 1. What we're building (user-visible)

1. **Track screen** (exists in app, flag-gated `TRIP_TRACKING_ENABLED`,
   never launched): user follows a journey A → B; sees the vehicle's
   live position, which stop it's at/approaching (A → a1 → b1 → … → B),
   times that update as reality changes, fleet type badge ("Waratah"),
   and a per-carriage occupancy strip (green/amber/red per car) when
   data exists.
2. **Share** (button exists in `JourneyCard`): generates a link; the
   recipient taps it, the app opens straight onto the live track screen
   for that journey.

Why it never shipped: the Trip Planner API alone can't do this — it has
no vehicle positions and no per-car occupancy. GTFS-Realtime feeds do,
but joining them client-side meant every phone polling multiple binary
feeds with the baked-in NSW key, decoding MB-scale protobufs to find
one vehicle. **The BFF flips that:** one server-side poll + join serves
every tracker; the app receives one small, purpose-built proto.

## 2. Review of the handover spec — what it got right, what's missing

Right (adopt as-is): the `trip_id` join spine; trip-id set-type parsing
table (§3a); occupancy enum→color mapping; graceful degradation
(per-car → train-level → none); Wire for proto2 + TfNSW extensions;
trip-id normalization warning.

Gaps found (each shapes this design):

| # | Gap | Consequence |
|---|---|---|
| G1 | Written **app-side**; pre-dates the BFF | Whole pipeline moves server-side. App never vendors the TfNSW extension protos, never touches GTFS-R. The spec's §6–7 module plan applies to the *BFF*, not the app. |
| G2 | **No share/deep-link flow at all** | Designed in §6 below. App already has encoder/handlers — needs domain migration + expiry semantics. |
| G3 | Treats **Trip Updates feed as "optional"** | It's *required*: the A→a1→b1→B stop progress timeline with live times comes from `TripUpdate.stop_time_updates`. Vehicle positions alone give a dot on a map but no ETAs. |
| G4 | **GTFS static bundle** (carriage layouts) handled "download daily, parse in app" | Multi-hundred-MB zips don't belong on phones or on a 512 MB basic-xxs at request time. Pipeline: GitHub Actions weekly (like the stops dataset) → small derived `.pb` → BFF loads in memory. Deferred to Phase 3; v1 car strip uses consist order from the live feed. |
| G5 | **No caching/fan-out model** (assumed one user) | BFF feed cache (§5) is what makes this scale within the 5 rps NSW limit: poll each feed at most once per 15–30 s regardless of user count. |
| G6 | **Bus feed fragmentation unaddressed** | Buses ship as multiple `vehiclepos` sub-feeds. Matching a bus leg may mean knowing route→feed mapping. Phase-gated: trains/metro/light rail/ferry first; buses after the feed inventory (open item O2). |
| G7 | **No staleness/error contract** | Production-grade rule: never render fabricated freshness. Every snapshot carries `measured_at`; every leg carries an explicit `status`; the app shows "last seen 45 s ago", not a confidently wrong dot. Encoded in the proto (§4). |
| G8 | Fleet type framed train-only | Ferries have vessel names (`vehicle.label`, e.g. "Freshwater"), buses sometimes a model. Proto carries a generic `FleetInfo` so the UI can badge any mode. |

## 3. Architecture

```
                 ┌────────────────────── BFF ──────────────────────┐
 app             │                                                  │        NSW
 ──POST /api/v1/track/snapshot──►  TrackService                     │
   TrackRequest (legs to track)      │ for each leg:                │
                                     │  feed = feedFor(productClass)│
                                     ▼                              │
                                  FeedCache ──(TTL expired?)──fetch──► /v2/gtfs/vehiclepos/{feed}
                                     │   15–30s TTL per feed,       │  /v1|v2/gtfs/realtime/{feed}
                                     │   single-flight dedupe       │  (Authorization: apikey, server key)
                                     ▼                              │
                                  VehicleMatcher (normalize ids,    │
                                     │  4-tier match — ported from  │
                                     │  app's GtfsRealtimeMatcher)  │
                                     ▼                              │
                                  enrich: TripIdParser (set type),  │
                                     │  PassLoad (per-car occupancy),│
                                     │  TfnswVehicleDescriptor,     │
                                     │  TripUpdate stop times       │
                                     ▼                              │
 ◄──TrackResponse (small proto)── assemble LegTracking per leg      │
                 └──────────────────────────────────────────────────┘
```

Decisions:

- **`POST /api/v1/track/snapshot`** with a proto request body (a
  journey has multiple legs; too much for query params). Polled by the
  app every `suggested_poll_seconds` (server-controlled, default 30,
  raised under load/incident — cadence tuning without an app release).
- **TfNSW extension protos (`PassLoad`, `TfnswVehicleDescriptor`) are
  vendored in the BFF only**, compiled with Wire next to the vendored
  `gtfs-realtime.proto`. The app's public contract (`track.proto` in
  krail-api-proto) never exposes GTFS-R internals — NSW quirks stay
  swappable server-side (multi-city later).
- **FeedCache** is the linchpin: per-feed `(bytes, decoded, fetchedAt)`
  with TTL 15 s (vehiclepos) / 30 s (trip updates), single-flight so
  concurrent requests during a refresh don't stampede NSW. Worst-case
  upstream cost is `feeds × (86400/TTL)` per day — bounded and
  user-count-independent. Reuses/extends the §2a roadmap cache.
- Existing raw passthrough GTFS routes stay during migration; retire
  for app use once tracking is at 100% (dashboard may keep them).

## 4. `track.proto` (new file in krail-api-proto)

Design rules: every enum has `*_UNSPECIFIED = 0`; everything not
guaranteed is `optional` or repeated-empty; explicit status instead of
absent-means-something; timestamps are epoch seconds UTC (no string
parsing on hot paths); client never needs GTFS knowledge.

```proto
syntax = "proto3";
package krail.api.track;

message TrackRequest {
  repeated TrackLeg legs = 1;        // transit legs only; ≤ 8 enforced

  message TrackLeg {
    string leg_ref = 1;              // client correlation key, echoed back
    string realtime_trip_id = 2;     // Trip Planner RealtimeTripId (may be "")
    string transportation_id = 3;    // stable route id, e.g. "nsw:020T1:W:R:sj2"
    int32 product_class = 4;         // Trip Planner mode → feed selection
    string origin_stop_id = 5;
    string destination_stop_id = 6;
    string service_date = 7;         // YYYYMMDD — scopes trip_id to a day
    string planned_departure_utc = 8; // disambiguation + expiry checks
  }
}

message TrackResponse {
  int64 fetched_at_epoch_sec = 1;
  int32 suggested_poll_seconds = 2;  // app obeys; server tunes cadence
  repeated LegTracking legs = 3;     // same order as request
}

message LegTracking {
  string leg_ref = 1;

  enum Status {
    STATUS_UNSPECIFIED = 0;
    TRACKING = 1;                    // matched; fields below populated
    NOT_STARTED = 2;                 // trip in future; no vehicle yet
    NO_REALTIME = 3;                 // service runs but feed has no match
    ENDED = 4;                       // trip completed / past service day
    EXPIRED = 5;                     // service_date in the past (stale share link)
    UPSTREAM_UNAVAILABLE = 6;        // NSW feed down; retry next poll
  }
  Status status = 2;

  optional VehicleLive vehicle = 3;
  optional FleetInfo fleet = 4;
  optional OccupancyInfo occupancy = 5;
  repeated StopProgress stops = 6;   // origin→destination timeline
  optional sint32 delay_seconds = 7; // negative = early
}

message VehicleLive {
  double latitude = 1;
  double longitude = 2;
  optional float bearing_degrees = 3;
  optional float speed_mps = 4;
  int64 measured_at_epoch_sec = 5;   // ALWAYS set — UI shows data age

  enum StopRelation {
    STOP_RELATION_UNSPECIFIED = 0;
    INCOMING_AT = 1;
    STOPPED_AT = 2;
    IN_TRANSIT_TO = 3;
  }
  StopRelation stop_relation = 6;
  optional string at_or_next_stop_id = 7;
}

message FleetInfo {
  string display_name = 1;           // "Waratah", "Mariyung", "Freshwater"
  optional string set_code = 2;      // "A", "T", … (trains)
  optional int32 car_count = 3;

  enum Source {
    SOURCE_UNSPECIFIED = 0;
    SCHEDULED = 1;                   // parsed from trip_id (set type table)
    LIVE = 2;                        // TfnswVehicleDescriptor — substitutions
  }
  Source source = 4;
}

message OccupancyInfo {
  enum Level {                       // mirrors GTFS occupancy_status
    LEVEL_UNSPECIFIED = 0;
    EMPTY = 1;
    MANY_SEATS_AVAILABLE = 2;
    FEW_SEATS_AVAILABLE = 3;
    STANDING_ROOM_ONLY = 4;
    CRUSHED_STANDING_ROOM_ONLY = 5;
    FULL = 6;
    NOT_ACCEPTING_PASSENGERS = 7;
  }
  Level overall = 1;                 // train-level; Tier-2 fallback
  repeated CarOccupancy cars = 2;    // Tier-1; empty when feed lacks PassLoad

  message CarOccupancy {
    int32 sequence = 1;              // 1 = front of travel direction
    optional string label = 2;       // car label when feed provides it
    Level level = 3;
    optional bool reaches_platform = 4; // Phase 3 (vehicle-boardings data)
  }
}

message StopProgress {
  string stop_id = 1;
  string stop_name = 2;
  optional int64 planned_epoch_sec = 3;
  optional int64 estimated_epoch_sec = 4;   // from TripUpdate; moves live

  enum State {
    STATE_UNSPECIFIED = 0;
    DEPARTED = 1;
    CURRENT = 2;                     // vehicle at/approaching this stop
    UPCOMING = 3;
    SKIPPED = 4;                     // SCHEDULE_RELATIONSHIP skipped
  }
  State state = 5;
}
```

UI degradation ladder (app side, driven purely by what's populated):
`cars[]` non-empty → full car strip; else `overall` set → single
indicator; else hide occupancy. `vehicle` absent but `stops[]` present
→ timeline-only mode. `status != TRACKING` → friendly state, never an
error dialog for `NOT_STARTED`/`ENDED`.

## 5. Server implementation notes (prod-grade checklist)

- **Validation:** ≤ 8 legs/request; ids through the existing regex
  allowlists; reject unknown `product_class` with per-leg
  `NO_REALTIME`, not a request-level 400 (one bad leg must not kill
  the journey).
- **trip_id normalization** in ONE place (`TripIdNormalizer`): trim,
  case, known prefix/suffix variants between Trip Planner JSON and
  GTFS-R. Property-test with captured real pairs (G7 from spec §8.4).
- **Matching tiers** (port app's `GtfsRealtimeMatcher`): exact
  `realtime_trip_id` → normalized → `(route, start_time, start_date)`
  from `transportation_id` + planned departure → no match ⇒
  `NO_REALTIME`. Log match-tier counters (metrics) to watch quality.
- **Never throw across legs**: each leg resolves independently;
  upstream failure for one feed ⇒ that leg `UPSTREAM_UNAVAILABLE`,
  others unaffected. Circuit breaker + daily budget already wrap
  `NswClient` — tracking inherits both.
- **Cache hygiene:** decoded `FeedMessage` cached (decode once per
  TTL, not per request); memory cap = a few MB/feed × ~8 feeds —
  measure on basic-xxs before launch; single-flight per feed key.
- **No new PII:** request contains stop ids + trip ids only. Don't log
  request bodies; metrics are counters/timers only.
- **Tests:** golden GTFS-R fixture files (captured real feed bytes,
  one per mode) drive matcher/occupancy/fleet unit tests; contract
  test pins `track.proto` wire-compat like `JourneyListContractTest`.

## 6. Share links

Exists today: `JourneyCard` share button → `TripDeepLinkEncoder` →
`https://ksharma-xyz.github.io/trip?d=<base64url(TripDeepLink)>` →
Android App Links / iOS Universal Links → `TrackTripScreen`.

Changes needed:

1. **Domain migration → `https://krail.app/trip?d=…`** (after the DNS
   move): host `/.well-known/assetlinks.json` (Android) and
   `/.well-known/apple-app-site-association` (iOS, no extension,
   `Content-Type: application/json`) on the KRAIL-WEBSITE repo; update
   `DeepLinkConfig`, AndroidManifest intent filter, iOS associated
   domains. ⚠ GitHub Pages + Jekyll can drop dotfile paths — the site
   needs a `.nojekyll` file (verify `/.well-known/` serves 200 before
   shipping). Keep the old `ksharma-xyz.github.io` handler one release
   for links in the wild.
2. **Payload v2:** current `TripDeepLink` legs carry
   `transportationId` + `productClass` — not enough to re-resolve a
   specific service later or feed `TrackRequest`. Add (with a
   `version` field for forward-compat): `realtimeTripId`,
   `serviceDate`, `plannedDepartureUtc`, `originStopId`,
   `destinationStopId`, origin/destination display names (so the
   screen renders instantly pre-fetch). Decoder must accept v1
   payloads (old links) and degrade.
3. **Expiry by design:** GTFS trip ids are per-service-day. A link
   opened tomorrow returns `EXPIRED` → app shows "this trip has ended"
   + offers re-planning A→B (names are in the payload). No error
   states leak.
4. **Privacy note:** the link encodes the journey itself — that's the
   point of sharing — but nothing about the sharer (no device id, no
   user id). Keep it that way.

## 7. Phases

| Phase | Scope | Depends on |
|---|---|---|
| **T0** | Land `track.proto` in krail-api-proto; vendor GTFS-R + TfNSW extension protos in BFF; capture golden fixtures; resolve O1–O3 | BFF deployed |
| **T1** | FeedCache + `/api/v1/track/snapshot` for **trains + metro + light rail + ferry**: position, stop progress (TripUpdates), delay, fleet-from-trip_id, train-level occupancy | T0 |
| **T2** | Per-carriage occupancy (PassLoad) + live fleet (TfnswVehicleDescriptor); car strip ships behind data-presence check | T1 + O1 |
| **T3** | Buses (feed inventory per O2); carriage-layout dataset via GitHub Actions (`vehicle-couplings`/`boardings` → `reaches_platform`) | T1 |
| **A1** (app) | Point `TrackTripViewModel`/`TripPoller` at the BFF endpoint behind `bff_use_for_track` RC flag; delete client-side GTFS-R matcher after 100% + grace | T1 |
| **A2** (app) | Deep link v2 payload + krail.app domain migration + well-known files on website | DNS move |

## 8. Open items (confirm before T1 code)

- **O1:** Exact proto field numbers/paths for `PassLoad` and
  `TfnswVehicleDescriptor` — pull the published TfNSW `.proto` /
  RTTA technical doc §3.4–3.6. Also: is per-carriage PassLoad
  populated in the *public* vehiclepos feed today, and for which
  lines? (Determines whether T2 ships a car strip or waits.)
- **O2:** Current `vehiclepos` feed inventory — confirm exact strings
  per mode on the Open Data Hub, especially whether buses are still
  split into sub-feeds or have a consolidated v2 feed; need
  route→feed mapping strategy for bus matching.
- **O3:** trip_id format equality between Trip Planner
  `RealtimeTripId` and GTFS-R `trip.trip_id` — capture ~20 real pairs
  across modes, derive the normalizer rules from data.
- **O4:** NSW key registration includes the realtime API products
  (the BFF-only key from the audit must have Trip Planner + GTFS-R +
  GTFS static all enabled).
- **O5:** `/.well-known/` serves correctly from GitHub Pages for the
  website (`.nojekyll`), or becomes the trigger to move hosting to
  Cloudflare Pages (TODO has that as optional).
