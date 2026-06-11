# Tracking integration — handover for the KRAIL app

> How the app consumes `POST /api/v1/track/snapshot` (T1 + T1.5, each
> live-verified against real NSW feeds). Contract: `track.proto` in
> krail-api-proto (pushed). Design rationale:
> [`docs/reference/TRACKING_DESIGN.md`](../reference/TRACKING_DESIGN.md).
> Try it interactively first: `./scripts/dev.sh up` →
> <http://localhost:8000/track-tester.html>.

## The contract in one paragraph

Send the transit legs you want to track (max 8) with their
`RealtimeTripId` from the trip response, the product class, and today's
Sydney service date. Poll. Each leg comes back with an explicit
`status`, and — when tracking — live position with a staleness
timestamp, fleet info, occupancy, the stop timeline with live times,
and the current delay. **Never re-plan to refresh a tracked trip**; the
trip is locked to its trip_id (the whole reason the old Trip-Planner-
polling approach was unreliable).

## Request

```
POST /api/v1/track/snapshot
Content-Type: application/x-protobuf   (the app; JSON also accepted)
X-Krail-Version: <version>             (when the gate is enabled)
```

`TrackRequest.TrackLeg` per transit leg (skip walking legs):

| Field | Source in the app today |
|---|---|
| `leg_ref` | any stable key, echoed back (e.g. `"leg-0"`) |
| `realtime_trip_id` | `transportation.properties.RealtimeTripId` — **verbatim**, no normalization needed (verified byte-identical to GTFS-R across trains + buses) |
| `transportation_id` | `transportation.id` (optional, future fallback matching) |
| `product_class` | `transportation.product.class` |
| `origin_stop_id` / `destination_stop_id` | leg origin/destination ids |
| `service_date` | YYYYMMDD **Sydney time** for the day the trip runs |
| `planned_departure_utc` | leg planned departure (ISO-8601) — enables NOT_STARTED before the trip enters the feeds |

`include_geometry`: send `true` on the **first poll only**. That
response carries, per leg, `geometry` (encoded polyline for the map)
and per-stop `latitude`/`longitude` pins. Steady-state polls leave it
false and get neither — cache the first-poll geometry client-side for
the life of the tracked trip.

## Poll loop contract

- Wait **exactly `suggested_poll_seconds`** between polls (server-tuned;
  don't hardcode 30).
- Stop polling when backgrounded; resume with a fresh poll on focus.
- Stop permanently once every leg is `ENDED` / `EXPIRED`.

## Status → screen state

| `status` | What the app shows |
|---|---|
| `TRACKING` | live screen: marker, timeline, occupancy |
| `NOT_STARTED` | "starts at HH:MM" + timeline when present (it usually is — TripUpdates carry future trips with full estimated times) |
| `NO_REALTIME` | timeline-less fallback: planned data the app already has, "live data unavailable" |
| `ENDED` | "journey complete". Fires when (a) the **user's destination** is behind the vehicle — even though the vehicle keeps running to its terminus — (b) every stop has departed, or (c) the trip vanished from the feeds with a departure >3h past (app returning from background long after arrival). The BFF omits `vehicle`/`occupancy` on ENDED — never show a live train the user already left. **Client must also self-detect**: NSW trims passed stops from the feed, so when your remembered destination stop reaches DEPARTED, treat the leg as complete locally too. |
| `EXPIRED` | "this trip has ended" + offer re-planning (stale share link) |
| `UPSTREAM_UNAVAILABLE` | keep last rendered state + "live data delayed"; next poll usually recovers |

## Rendering rules (the degradation ladder)

1. **Position:** `vehicle` present → marker at lat/lng. ALWAYS show data
   age from `measured_at_epoch_sec`; grey the marker past ~90 s.
   `has_bearing` is false on Sydney Trains (feed omits it) — show a dot,
   not an arrow, or derive bearing from successive positions.
2. **Occupancy:** `occupancy.cars[]` non-empty → car strip (sequence is
   1-based front-of-travel; `label` when the mode provides it, e.g.
   metro "DTC1"). Else `occupancy.overall != LEVEL_UNSPECIFIED` →
   single indicator. Else hide. The BFF already suppresses all-unknown
   consists.
3. **Fleet badge:** `fleet.display_name` ("Waratah", "OSCAR",
   "Alstom Metropolis"). `source = LIVE` reflects actual substituted
   rolling stock; `SCHEDULED` is parsed from the trip id.
4. **Timeline:** `stops[]` is a **complete snapshot of the vehicle's
   full run** in running order — the BFF keeps short-term per-trip
   memory, so stops NSW has already trimmed from its feed (passed
   stops) are re-attached as `DEPARTED`. A client joining mid-trip
   (share link) or waking from background gets the same complete
   timeline as one that watched from the start. Caveats: memory
   begins when the BFF first observes the trip, and a server restart
   clears it — so still overlay your planned stop list from the trip
   response where available; treat the snapshot as authoritative for
   states/times. Each stop is tagged with `segment`: `JOURNEY`
   (between the requested origin and destination), `BEFORE_JOURNEY`,
   or `AFTER_JOURNEY`. Render JOURNEY stops normally; the surrounding
   groups are the client's choice — recommended: collapsed
   "▸ continues to N more stops" expanders (the dashboard demonstrates
   this). If every stop is `SEGMENT_UNSPECIFIED` the endpoint ids
   didn't match the trip's sequence — render all stops normally.
   `state` drives the glyphs; `estimated_epoch_sec` when present, else
   planned time from the app's own trip data. `stop_name` is
   **server-resolved on every poll** (T1.5): the BFF's platform-level
   directory names train platform ids ("Central Station Platform 16")
   the app's local stops DB doesn't have. Render it when non-empty;
   fall back to the local DB only when it's blank (directory gap).
   `expected_occupancy` (T1.6) ships **once, with geometry** (first
   poll) — cache it client-side like the polyline. Render rule: show
   ONLY while the stop is CURRENT/UPCOMING; a forecast for a departed
   stop is noise (live `occupancy` is the truth from then on). It is
   best-effort Trip Planner enrichment validated against the locked
   trip_id — absent whenever NSW has no forecast (common off-peak);
   absence never changes structure.
5. **Map line (first poll):** `geometry.encoded_polyline` is a Google
   polyline (precision 5) of the vehicle's full run. `source` tells
   you what you're drawing: `GTFS_SHAPES` = real track geometry
   (normal case), `STOP_STRAIGHT_LINES` = honest fallback connecting
   the stop pins (dataset miss — draw it dashed if you like). Absent
   `geometry` with `include_geometry:true` = not even stop coordinates
   were known; map shows the vehicle marker only.
6. **Delay:** `has_delay == true` → show `delay_seconds` (negative =
   early). Don't invent a delay when `has_delay` is false.

## App-side work checklist (phase A1)

- [ ] Point `TripPoller` at the BFF endpoint behind the
      `bff_use_for_track` RC flag (kill-switch compatible).
- [ ] Build `TrackRequest` from `TimeTableState.JourneyCardInfo.Leg`
      (the legs already carry `tripId`/`transportationId`).
- [ ] Stop-name fallback by stop_id from the local stops DB (server
      `stop_name` wins when non-empty).
- [ ] Decode + cache the first-poll `geometry` polyline for the map.
- [ ] **Fix the app's vendored `gtfs-realtime.proto`** (while it still
      exists): `TripDescriptor.direction_id` is declared `string` but
      the spec says `uint32` — metro feeds populate it and will crash
      the app's own decoder. (Found while vendoring server-side.)
- [ ] After 100% + grace: delete the app's GTFS-R client, vendored
      protos, and `GtfsRealtimeMatcher` entirely.

## Share links (phase A2, not yet built)

Current `TripDeepLink` payloads ("v1") carry only `transportationId` +
`productClass` — **not trackable** (no trip id). The v2 payload adds
`realtimeTripId`, `serviceDate`, stop ids + display names, and a
`version` field; decoder must accept both. Domain moves to
`krail.app/trip` after the DNS migration. The dashboard's share-link
simulator decodes both shapes today and flags v1 as untrackable.

## Known limitations (by design, tracked in TODO)

- `expected_occupancy` is best-effort: present only when NSW's Trip
  Planner carries a forecast for the locked trip (often absent
  off-peak). Never rendered for DEPARTED stops.
- Bus legs: geometry falls back to `STOP_STRAIGHT_LINES` (no bus
  shapes dataset yet — 10–50× larger, folds in with T3 carriage
  layouts); names come from the search dataset (bus stops are in it).
- Coaches (product class 7): always `NO_REALTIME` (no public feed).
- Sydney Trains `at_or_next_stop_id` carries a location string, not a
  stop id (feed quirk) — don't join on it for trains.

## Fixtures

Real captured feeds live in `server/src/test/resources/gtfsrt/` —
reuse them for app-side unit tests rather than mocking shapes by hand.
