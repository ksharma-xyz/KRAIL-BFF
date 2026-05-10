# Trip pass-through fix + answers to "anything else missing?" / "is it all proto?"

> Audience: KRAIL agent / app maintainer.
> Status: Fix landed on `proto-submodule` branch (commit `421ffff`),
> verified live against the local BFF, all 28 BFF tests pass.

---

## §1 · TL;DR

Two questions came in alongside the polyline bug report:
1. *"Hope nothing else is missing?"*
2. *"Are you sure all data will be given from BFF to client in proto format only, not JSON?"*

Short answers:

1. **Quite a lot was missing — same root cause as polyline.** Fixed by
   making `/v1/tp/trip` a true byte-for-byte pass-through. 229 fields and
   ~70% of the response payload were being silently stripped by typed
   serialization. Now everything NSW returns flows through unchanged.
2. **No — most BFF endpoints return JSON, not proto.** Only GTFS-RT live
   tracking is binary protobuf today. Proto trip endpoint exists but is
   gated off in your app (Phase C). Full breakdown in §3.

Plus a separate concern surfaced: the proto trip schema
(`KRAIL-API-PROTO/proto/api/trip.proto`) is missing polyline fields. Not
urgent (you're not on proto yet), but should land in `v0.2.0`. See §4.

---

## §2 · The polyline fix — what changed

### Root cause

The "pass-through" endpoint wasn't actually pass-through. The flow was:

```
NSW response (361 KB, 229 unique field paths)
  ↓
NswClient.getTrip() — deserializes to TripResponse Kotlin data class
  (Json { ignoreUnknownKeys = true } → silently drops unknown fields)
  ↓
TripResponse holds only ~30 declared fields out of 229
  ↓
respond(TripResponse) → re-serialize to JSON
  ↓
BFF response (86 KB, 30 unique field paths — 70% data loss)
```

`coords` wasn't declared on `TripResponse.Leg` → dropped. So were
`coupledTripsInfo`, `fare`, `interchanges`, stop-level `coord`,
`parent`, `daysOfService`, and ~225 other fields.

### Fix

`/v1/tp/trip` and `/api/v1/trip/plan` are now true pass-throughs. New
`NswClient.getTripRaw()` returns NSW's body byte-for-byte; the route
handler responds with that text directly. The typed `TripResponse`
model is no longer in the request path for these endpoints.

`/api/v1/trip/plan-proto` (the proto endpoint, gated off in your app)
still uses the typed parse — its mapper requires structure. **This is
why the proto schema needs polyline fields added separately, see §4.**

### Verification

Diff measured live (BFF on `:8080`, real Town Hall → Bondi Junction
trip with KRAIL's exact query params):

| Metric | NSW direct | BFF before fix | BFF after fix |
|---|---|---|---|
| Body size | 361 632 B | 86 015 B | 365 907 B |
| Unique field paths | 229 | 30 | 229 |
| `legs[*].coords` present | yes (3111 pts total) | **no** | yes |
| `journeys[*].fare.tickets` | yes | no | yes |
| `legs[*].coupledTripsInfo` | yes | no | yes |
| `legs[*].destination.coord` | yes | no | yes |
| `legs[*].destination.parent` | yes | no | yes |

KRAIL's `JourneyMapScreen` polyline rendering should now have data to
draw. No KRAIL-side change needed; the same `Leg` model with
`@SerialName("coords") val coords: List<List<Double>>? = null` already
parses correctly.

### Why pass-through (not "fix the model")

Maintaining a 229-field typed model would catch known fields and miss
any future NSW addition the same way. Pass-through is robust by
construction — anything NSW returns flows through. The only cost is
that the BFF can't trivially intercept / reshape / cache the body
content. That's fine for `/v1/tp/trip` which is documented as
pass-through anyway. For shape-aware endpoints (`/api/v1/trip/plan-proto`)
the typed parse stays.

---

## §3 · BFF response formats — the full picture

| BFF endpoint | Response format | Used by KRAIL? | Notes |
|---|---|---|---|
| `/v1/tp/trip` | **JSON** | ✅ today (Phase A) | Full NSW shape, byte-for-byte. `coords[]` now flows. |
| `/api/v1/trip/plan` | **JSON** | not yet | Same shape as `/v1/tp/trip` — pass-through. Reserved for future screen-shaped JSON. |
| `/api/v1/trip/plan-proto` | **binary protobuf** | not yet (Phase C foundation) | `JourneyList` schema. Smaller wire (~83% smaller than NSW JSON). Polyline fields missing — see §4. |
| `/v1/stops/{id}/departures` | **JSON** | ✅ today | NSW `departure_mon` shape, pass-through. |
| `/v1/parking/facilities` | **JSON** | ✅ today | `Map<facilityId, name>`, NSW shape. |
| `/v1/parking/facilities/{id}/availability` | **JSON** | ✅ today | NSW `carpark` body. |
| `/v1/parking/availability?ids=` | **JSON** | when you adopt batching | New batch wrapper around the above. |
| `/v1/parking/availability?stopIds=` | **JSON** | recommended | Server-side stop→facility resolution. |
| `/v1/gtfs/realtime/{feed}` | **binary protobuf** | ✅ today | Standard GTFS-RT `FeedMessage`, NSW bytes verbatim. |
| `/v2/gtfs/realtime/{feed}` | **binary protobuf** | ✅ today | Same, for sydneytrains / metro. |
| `/v2/gtfs/vehiclepos/{feed}` | **binary protobuf** | ✅ today | Same, for vehicle positions. |
| `/v1/data/stops/manifest` | 302 → JSON | future (Phase D) | Manifest is JSON; the asset behind it is `StopsDataset` proto. |
| `/v1/data/routes/manifest` | 302 → JSON | future (Phase D) | Same shape, for routes. |
| `/health`, `/ready` | **JSON** (`{}`) | smoke only | Don't call from app. |

**TL;DR on formats:** what KRAIL is using today is mostly NSW JSON
pass-through (4 endpoint groups), plus binary GTFS-RT (which is also
pass-through bytes). Proto trip endpoint exists but you haven't adopted
it yet. Once you do (Phase C), trip results become binary proto, but
the rest stays JSON until screen-shaped endpoints land.

---

## §4 · KRAIL-API-PROTO updates needed (deferred)

### What's missing in `trip.proto`

The proto `JourneyList` schema doesn't carry polyline / coordinate
data:

| Field needed | Why | Where it goes |
|---|---|---|
| `repeated LatLng coords` on `TransportLeg` | Polyline for the train/bus route | Same place NSW puts `legs[].coords` |
| `repeated LatLng coords` on `WalkInterchange` | Walking-interchange polyline | NSW puts it under `legs[].interchange.coords` |
| `LatLng coord` on `Stop` | Per-stop point on the map | NSW puts it on `stopSequence[].coord` |
| `Stop parent_station` (or `string parent_station_id`) | Group platforms under stations on the map | NSW puts it on `stop.parent` |

A `LatLng` message already exists in `proto/data/stops_dataset.proto`
— `TripResultsResponse` could import it once the proto repo's layout
allows cross-package imports cleanly. Or define a local
`Coord` message in `trip.proto` to avoid the dependency.

### When to ship

**Not now**, because:
1. The KRAIL app currently uses `/v1/tp/trip` (JSON pass-through), not
   the proto endpoint. JSON has all the fields.
2. The proto endpoint is gated off in KRAIL behind
   `IS_BFF_PROTO_FOR_TRIP_RESULTS_ENABLED` (default false).
3. Cutting `v0.2.0` for one schema field is overkill; bundle with the
   next round of schema work (likely the screen-shaped messages —
   `TripResultsResponse`, `DepartureBoardResponse`).

**Trigger to revisit:** when KRAIL flips
`IS_BFF_PROTO_FOR_TRIP_RESULTS_ENABLED` to true. At that point,
JourneyMapScreen would lose polylines on the proto path. Add the
`coords` fields to `TransportLeg` + `Stop` + `WalkInterchange`, cut
KRAIL-API-PROTO `v0.2.0`, the BFF's auto-bump workflow opens a PR,
the BFF's `JourneyListMapper.toProto()` populates the new fields from
`TripResponse.Leg.coords`, KRAIL adopts the bump, JourneyMapScreen
works on proto too.

### Other proto gaps (non-blocking)

- `Stop.is_wheelchair_accessible` is just a `bool`; NSW returns more
  detail (`stop.properties.wheelchairAccess` can be "true" / "false" /
  "unknown" / "limited"). Mapper currently flattens to bool. Fine for
  v1; revisit if anyone asks for nuance.
- No `fare` representation. NSW returns `journeys[].fare.tickets` with
  per-mode fare info. Not currently surfaced in the JourneyList proto;
  add `Fare` message when the screen needs it.
- No `interchanges` summary at the journey level (NSW puts it on
  `journeys[].interchanges` — count of transfers). Could be derived
  from `legs.size - 1` for transit-only journeys, but NSW's number is
  authoritative. Add when the screen needs it.

None of these block Phase C.

---

## §5 · So is anything else missing?

After the polyline fix, the answer is "you tell me." The BFF's
`/v1/tp/trip` is now byte-for-byte identical to NSW for JSON. If
KRAIL's typed `TripResponse` model declares a field, it's there. If a
KRAIL feature needs a field NSW returns, it's there.

The pass-through eliminates a class of bug rather than fixing one
instance. So: your existing parsers should now work everywhere they
used to work against NSW direct, no surprises.

The only places where data could still go missing:

- `/api/v1/trip/plan-proto` — typed mapper to `JourneyList` proto.
  Drops anything not in the proto schema. Mitigated by §4 above. Not
  in your hot path until Phase C.
- The dataset endpoints (`stops_dataset.proto`, `routes_dataset.proto`).
  These are designed schemas, not pass-throughs. The dataset builder
  reads NSW GTFS and emits the .pb. If you find a dataset field
  missing, it's a `KRAIL-API-PROTO` schema gap, not a BFF runtime bug.

For everything else (departures, parking, GTFS-RT) — already
pass-through, already byte-for-byte. No changes needed.

---

## §6 · Is the data all proto, or mostly JSON?

Mostly JSON today. Reproducing §3 in narrative form:

- **Trip planner (used today):** JSON pass-through. NSW shape verbatim,
  including coords[].
- **Departures:** JSON pass-through.
- **Park & Ride list + detail + batch:** JSON pass-through (NSW shape
  for the per-facility bodies; BFF wraps in a thin batch envelope).
- **GTFS-Realtime (live tracking + map):** binary protobuf. NSW returns
  bytes; BFF passes them through. KRAIL decodes with the standard
  GTFS-RT `FeedMessage` schema.
- **Stops + routes datasets (future):** binary protobuf (`StopsDataset`,
  `RoutesDataset`). Distributed via 302 redirect to GitHub Releases
  asset.
- **Trip planner proto (future, gated off):** binary protobuf
  (`JourneyList`).

So when KRAIL talks to the BFF for the four migrated endpoints today,
3 are JSON and 1 (GTFS-RT) is binary. The proto trip endpoint is wire
ready but you haven't flipped it on.

---

## §7 · For the API reference doc

`KRAIL_API_REFERENCE.md` §3 (trip-planner sample) currently shows a
small slice of the response shape and doesn't include `coords[]`.
That's an oversight that probably contributed to the silent regression.
Fix as a follow-up: extend §3 to show the full leg shape including
`coords`, `coupledTripsInfo`, `fare`, `interchanges`, etc. — pulled
from a real BFF response after this fix.

I haven't updated the API reference yet — you can do it as part of
the next docs commit, or I can do it now if you prefer. The fix itself
is wire-compatible, so the doc gap is descriptive rather than blocking.

---

## §8 · Action items for the KRAIL agent

Nothing required for this fix to take effect on your side — the BFF
now returns the data your existing parsers expect.

For the JourneyMap polyline rendering specifically, you should now see
`leg.coords` populated. Run through the end-to-end:

```bash
# Smoke test from your dev machine
curl -s 'http://localhost:8080/v1/tp/trip?name_origin=200070&name_destination=215020&depArrMacro=dep&type_destination=any&calcNumberOfTrips=6&type_origin=any&TfNSWTR=true&version=10.2.1.42&coordOutputFormat=EPSG:4326&itOptionsActive=1&computeMonomodalTripBicycle=false&cycleSpeed=16&useElevationData=1&outputFormat=rapidJSON' \
  | jq '.journeys[0].legs[0].coords | length'
# Expect: a number > 0 (50–500 typical for a transit leg)
```

Then exercise JourneyMapScreen on the AVD. You should see the polyline
draw between origin and destination via the actual route geometry.

If you don't, the issue is on the KRAIL parsing / rendering side
rather than the BFF — the data is now definitely in the response.
