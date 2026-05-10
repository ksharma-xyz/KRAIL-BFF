# Proto trip endpoint — polyline gap, fix plan, and how KRAIL tests it

> Audience: KRAIL agent / app maintainer.
> Status as of 2026-05-10: gap identified. Fix in progress on
> `proto-submodule` (BFF) + `KRAIL-API-PROTO` (v0.2.0). This doc
> updates as those land — see §6 progress log at the bottom.

---

## §1 · TL;DR

The JSON trip endpoint (`/v1/tp/trip`) is now a true pass-through and
includes `legs[].coords[]` for JourneyMap polylines (separate handover:
`TRIP_POLYLINE_FIX_HANDOVER.md`).

The proto trip endpoint (`/api/v1/trip/plan-proto`) returns a typed
`JourneyList` message. Its `v0.1.0` schema doesn't have polyline /
coordinate fields, so KRAIL's JourneyMap rendering would silently break
the moment you flip `IS_BFF_PROTO_FOR_TRIP_RESULTS_ENABLED` to `true`.

This doc:
- Names the proto schema gaps
- Plans `KRAIL-API-PROTO v0.2.0` to fill them
- Updates the BFF mapper to populate the new fields
- Gives KRAIL a step-by-step recipe for testing the proto endpoint
  end-to-end (decoding bytes, verifying field population, snapshot
  tests, wire-size comparisons)

---

## §2 · The gaps in `JourneyList` proto v0.1.0

NSW returns this geometry data (verified live, real Town Hall → Bondi
Junction trip, 6 journeys, 3111 polyline points total):

| NSW JSON path | Used by KRAIL for | In proto v0.1.0? |
|---|---|---|
| `journeys[].legs[].coords[]` (e.g. 488 pts on a Sydney Trains leg) | JourneyMap polyline for the transit ride | ❌ |
| `journeys[].legs[].interchange.coords[]` (~17 pts on a station walk) | Walking-interchange polyline | ❌ |
| `journeys[].legs[].stopSequence[].coord` (single `[lat, lng]`) | Stop pin on the map | ❌ |
| `journeys[].legs[].destination.parent` (parent station info) | Group platforms under stations | ❌ |

The corresponding proto messages currently look like:

```proto
message TransportLeg {
  TransportModeLine transport_mode_line = 1;
  optional string display_text = 2;
  string total_duration = 3;
  repeated Stop stops = 4;
  optional WalkInterchange walk_interchange = 5;
  repeated ServiceAlert service_alert_list = 6;
  optional string trip_id = 7;
  // <-- no coords[]
}

message Stop {
  string name = 1;
  string time = 2;
  bool is_wheelchair_accessible = 3;
  // <-- no coord
}

message WalkInterchange {
  string duration = 1;
  WalkPosition position = 2;
  // <-- no coords[]
}
```

So the KRAIL app, on adopting the proto endpoint, would lose every
polyline / point-on-map signal it currently has from the JSON shape.

---

## §3 · Schema changes — KRAIL-API-PROTO v0.2.0

### New `Coord` message (in `proto/api/trip.proto`)

```proto
// Geographic point in decimal degrees, EPSG:4326. Used for transit-leg
// polylines, walking-interchange polylines, and per-stop pins.
//
// Defined locally in trip.proto rather than shared with stops_dataset.proto's
// LatLng to keep v0.2.0 small. If a third schema needs lat/lng, we move
// LatLng to proto/core/ in a follow-up.
message Coord {
  // contract: required — latitude in decimal degrees.
  double lat = 1;
  // contract: required — longitude in decimal degrees.
  double lon = 2;
}
```

### Additions to existing messages

```proto
// TransportLeg gains polyline coords + interchange coords already covered.
message TransportLeg {
  // ... existing fields 1–7 ...

  // contract: optional — polyline points for the transit ride from origin
  // to destination. Order: origin → destination. Empty list when NSW
  // didn't return geometry (e.g. some bus routes).
  repeated Coord coords = 8;
}

// WalkingLeg gains polyline coords for fully-walking legs.
message WalkingLeg {
  string duration = 1;

  // contract: optional — polyline points for the walk path. May be empty
  // when NSW returns walk-step instructions but no geometry.
  repeated Coord coords = 2;
}

// WalkInterchange gains polyline coords for the walking portion between
// transit legs.
message WalkInterchange {
  string duration = 1;
  WalkPosition position = 2;

  // contract: optional — walking-interchange polyline. NSW returns these
  // for station-internal walks (e.g. Wynyard platform → underground exit).
  repeated Coord coords = 3;
}

// Stop gains an optional point-on-map.
message Stop {
  string name = 1;
  string time = 2;
  bool is_wheelchair_accessible = 3;

  // contract: optional — point-on-map for this stop. NSW returns it on
  // most stops but not all (some planning-only stops have no published
  // coordinate).
  optional Coord coord = 4;
}
```

All additions are `optional` / `repeated`, so they're additive and
backward-compatible. `buf breaking` will accept them; no `major-bump`
label needed.

### What's NOT changing in v0.2.0

- `JourneyCardInfo` — no new top-level fields.
- `Stop.parent_station` — deferred. The "group platforms under stations
  on the map" use case is real but adds another nested reference; can
  land in v0.3.0 if needed.
- `Fare` representation — deferred. Not a blocker for JourneyMap.
- `interchanges` count summary — deferred. Derivable from `legs.size`
  for now.

---

## §4 · BFF-side changes (this branch)

The BFF's `JourneyListMapper` translates `TripResponse` (NSW JSON parse)
into `JourneyList` (proto). For v0.2.0 to actually deliver coords, the
mapper has to populate the new fields.

### Steps on the BFF side

1. Bump `KRAIL-API-PROTO` submodule from `v0.1.0` → `v0.2.0`.
2. Add `coords`, `coord` fields to `TripResponse.Leg` and
   `TripResponse.StopSequence` Kotlin models (NSW returns these but the
   typed model didn't declare them — same root cause as the JSON
   pass-through bug, except this time the typed model is intentional).
3. Update `JourneyListMapper.mapLeg()` to map
   `TripResponse.Leg.coords` → `TransportLeg.coords` (or
   `WalkingLeg.coords`).
4. Update `JourneyListMapper.mapStop()` to map
   `TripResponse.StopSequence.coord` → `Stop.coord`.
5. Update walk-interchange mapping for `WalkInterchange.coords`.
6. Extend `JourneyListContractTest` to assert coords are populated when
   the upstream fixture has them.

After these, the proto endpoint at `/api/v1/trip/plan-proto` returns a
`JourneyList` with polyline data on every applicable leg.

### Wire-size impact

Per a real Town Hall → Bondi Junction sample (6 journeys, 3111 coord
points across all legs):

| Format | Approx. size | Notes |
|---|---|---|
| NSW JSON (full pass-through) | 366 KB | What `/v1/tp/trip` returns today |
| Proto v0.1.0 (no coords) | ~12 KB | Currently from `/api/v1/trip/plan-proto` |
| Proto v0.2.0 (with coords) | ~45 KB (estimate) | Once mapper populates them |

The proto-with-coords is still ~88% smaller than NSW JSON because the
field naming and structure are tighter. Polyline points add ~32 KB
(3111 points × ~10 bytes per `Coord` message in proto3 binary). gzip
on top should drop another 30–40%.

---

## §5 · How KRAIL tests the proto endpoint

This is the section to keep open while you wire it up. Three layers:
curl-level smoke, instrumented-test, and dashboard-level.

### §5.1 · curl-level smoke

The proto endpoint returns binary bytes. To inspect:

```bash
# Hit the endpoint, save bytes to /tmp.
curl -s 'http://localhost:8080/api/v1/trip/plan-proto?\
name_origin=200070&name_destination=215020&\
depArrMacro=dep&type_destination=any&type_origin=any&\
calcNumberOfTrips=6&TfNSWTR=true&version=10.2.1.42&\
coordOutputFormat=EPSG:4326&itOptionsActive=1&\
computeMonomodalTripBicycle=false&cycleSpeed=16&\
useElevationData=1&outputFormat=rapidJSON' \
  -o /tmp/trip.pb

# Sanity check it's binary protobuf.
file /tmp/trip.pb       # should report "data"
xxd /tmp/trip.pb | head  # raw bytes; field tags + varints visible

# Decode it. Two options:

# A) Use protoc + the proto file (server-side):
protoc --decode=app.krail.bff.proto.JourneyList \
       --proto_path=krail-api-proto/proto \
       krail-api-proto/proto/api/trip.proto < /tmp/trip.pb | head -40

# B) Use buf, which does the same thing without proto_path arithmetic:
buf convert krail-api-proto --type=app.krail.bff.proto.JourneyList \
            --from /tmp/trip.pb --to=/tmp/trip.json
jq '.journeys[0].legs[0] | keys' /tmp/trip.json
# Should include "coords" once v0.2.0 is in.

# Quick wire-size comparison:
echo "Proto: $(wc -c < /tmp/trip.pb) bytes"
curl -s 'http://localhost:8080/v1/tp/trip?...same params...' \
  | wc -c     # JSON for the same trip
```

### §5.2 · KRAIL-side instrumented test

Once `:io:bff-api` is wired and KRAIL pulls the v0.2.0 submodule, write
a single round-trip test that decodes a real BFF response and asserts
the field is populated. This is the same pattern the BFF uses for its
contract tests, mirrored on the consumer side.

```kotlin
// io/bff-api/src/commonTest/kotlin/.../JourneyListContractTest.kt
class JourneyListContractTest {

    @Test
    fun `proto response from local BFF includes coords on transit legs`() = runTest {
        val httpClient = bffHttpClient()  // hits http://10.0.2.2:8080
        val bytes: ByteArray = httpClient.get("$BFF_BASE_URL/api/v1/trip/plan-proto") {
            url {
                parameters.append("name_origin", "200070")
                parameters.append("name_destination", "215020")
                parameters.append("depArrMacro", "dep")
                // ... other required params per KRAIL_API_REFERENCE.md ...
            }
        }.body()

        val journeyList = JourneyList.ADAPTER.decode(bytes)

        assertTrue(journeyList.journeys.isNotEmpty(), "expected at least one journey")

        val firstTransitLeg = journeyList.journeys
            .flatMap { it.legs }
            .firstNotNullOf { it.transport_leg }

        assertTrue(
            firstTransitLeg.coords.isNotEmpty(),
            "first transit leg should have coords[] populated; got empty list"
        )

        // Sanity-check the coord values are in Sydney's bounding box.
        for (c in firstTransitLeg.coords.take(5)) {
            assertTrue(c.lat in -34.5..-33.5, "lat ${c.lat} outside Sydney bbox")
            assertTrue(c.lon in 150.0..152.0, "lon ${c.lon} outside Sydney bbox")
        }
    }
}
```

This requires a live local BFF on `10.0.2.2:8080` (or `localhost` for
iOS sim). For CI you'd want either:
- A snapshot fixture: capture the bytes once, commit them, decode in
  the test (no live BFF needed).
- An integration suite that spins up the BFF in a Docker container.

For initial wiring, manual run is fine. Set up the snapshot fixture
once the proto path goes live in production.

### §5.3 · Snapshot fixture pattern

When you want CI without a live BFF:

```bash
# Capture once, on a real machine with the BFF running:
curl -s 'http://localhost:8080/api/v1/trip/plan-proto?...' \
  > io/bff-api/src/commonTest/resources/fixtures/trip-townhall-bondi.pb
```

Then in the test:

```kotlin
@Test
fun `decoding the captured proto snapshot has expected polyline shape`() {
    val bytes = readResource("/fixtures/trip-townhall-bondi.pb")
    val journeyList = JourneyList.ADAPTER.decode(bytes)

    val polylinePoints = journeyList.journeys
        .flatMap { it.legs }
        .mapNotNull { it.transport_leg }
        .sumOf { it.coords.size }

    // Empirical: this trip had ~507 polyline points across its transit
    // legs at capture time. Use a lower bound to allow NSW data drift
    // without the test becoming a brittle fixture diff.
    assertTrue(
        polylinePoints >= 100,
        "expected ≥ 100 polyline points across transit legs, got $polylinePoints"
    )
}
```

This catches both BFF-side mapper regressions ("we stopped populating
coords") and proto-schema regressions ("a future bump dropped the
field").

### §5.4 · Dashboard testing (lightweight check)

The local API tester at <http://localhost:8000/api-tester.html> already
has a Trip planner — proto endpoint button. After v0.2.0 lands you'll
see the response size grow (~12 KB → ~45 KB) which is the visible
signal that polylines are flowing. The Highlights panel will show the
journey count + first-leg modes; the body inspector renders the binary
hex preview but doesn't decode coords visually.

If you want a coords-aware view in the dashboard, add a JourneyList
proto decoder to the inspector — that's a 50-line dashboard change,
not blocking.

### §5.5 · Wire-size comparison

After v0.2.0 lands, run this to confirm the proto path is still smaller
than JSON despite carrying coords:

```bash
JSON_BYTES=$(curl -s 'http://localhost:8080/v1/tp/trip?...' | wc -c)
PROTO_BYTES=$(curl -s 'http://localhost:8080/api/v1/trip/plan-proto?...' | wc -c)
echo "JSON:  $JSON_BYTES bytes"
echo "Proto: $PROTO_BYTES bytes"
echo "Proto saves: $((100 - PROTO_BYTES * 100 / JSON_BYTES))%"
```

Typical results we'd expect:
- JSON: ~366 KB
- Proto with coords: ~45 KB
- Saving: ~88%

With gzip Compression on (default for both endpoints), both shrink
proportionally and the proto stays smaller.

### §5.6 · Common pitfalls when testing proto

| Symptom | Cause | Fix |
|---|---|---|
| `JourneyList.ADAPTER.decode(bytes)` throws | Body is JSON, not proto. Check `Content-Type`. | Confirm endpoint is `/api/v1/trip/plan-proto` not `/api/v1/trip/plan`. |
| `journeys` is empty list | NSW returned `error: {...}` (no journeys found for those params). | Check params; the BFF doesn't filter NSW errors out of the proto either. Try a known-good route. |
| `coords` is empty on every leg | (Pre-v0.2.0) schema has no field. | Bump `krail-api-proto` submodule to v0.2.0+ in your repo. |
| `coords` is empty post-v0.2.0 | NSW didn't return geometry for that route, or BFF mapper isn't populating yet. | Hit `/v1/tp/trip` with the same params; check if `legs[*].coords` is populated. If JSON has it and proto doesn't, BFF mapper bug. |
| iOS Wire codegen fails | Wire 6.2.0 + KMP-iOS quirks. | Pin Wire version, follow `:io:gtfs` setup as the working reference per the integration report. |
| Different proto encoding lib than Wire | KRAIL uses Wire; if anyone tries `kotlinx.serialization.protobuf`, field-tag handling can differ subtly on `optional` scalars. | Stick to Wire. |

---

## §6 · Progress log

Ticked as each step lands. This section is the authoritative status.

- [ ] **KRAIL-API-PROTO v0.2.0 cut.** Adds `Coord`, plus `coords`/`coord`
      fields on `TransportLeg`/`WalkingLeg`/`WalkInterchange`/`Stop`.
- [ ] **BFF submodule bumped to v0.2.0.**
- [ ] **`TripResponse.Leg.coords` and `TripResponse.StopSequence.coord`
      added to BFF Kotlin model** so the JSON parse no longer drops
      them on the typed-mapper path.
- [ ] **`JourneyListMapper` updated** to populate the new proto fields
      from the typed `TripResponse`.
- [ ] **`JourneyListContractTest` extended** to assert coords are
      populated when upstream has them.
- [ ] **End-to-end verified** — `curl /api/v1/trip/plan-proto` →
      `protoc --decode` → `journeys[*].legs[*].coords[]` non-empty
      for at least one transit leg.
- [ ] **`KRAIL_API_REFERENCE.md §8`** updated to describe the new proto
      shape (the JSON section already had a TODO to add coords).
- [ ] **This doc updated** — flip "in progress" to "done" once the
      checklist is green.

---

## §7 · Action items for the KRAIL agent

When v0.2.0 ships and the BFF is bumped:

1. Run `git submodule update --remote krail-api-proto` in the KRAIL
   repo (or wait for `proto-bump.yml` to open the PR — it runs daily).
2. Pull, run `:io:bff-api:compileKotlinMetadata` to regenerate Kotlin
   classes from the new proto.
3. Verify the new fields exist on the generated classes
   (`TransportLeg.coords`, `Stop.coord`, etc.). They'll be
   `List<Coord>` (with empty as default) and `Coord?` respectively.
4. Update the JourneyMap rendering code to read polyline data from the
   proto path *if* it's currently flagged on. The mapping is:
   - Proto `TransportLeg.coords` → screen polyline (same as JSON
     `legs[].coords`).
   - Proto `Stop.coord` → stop pin on the map (same as JSON
     `stopSequence[].coord`).
5. Smoke-test on AVD with `IS_BFF_PROTO_FOR_TRIP_RESULTS_ENABLED = true`.
   Same JourneyMap polyline render as the JSON path, just from a
   smaller payload.

If you flip the proto flag *before* v0.2.0 ships, JourneyMap loses its
polyline (because the v0.1.0 schema doesn't carry the data). So
sequence: KRAIL-API-PROTO v0.2.0 → BFF bump → KRAIL bump → flip flag.

---

## §8 · Why this isn't done yet — sequencing

The chain has three repos:

```
KRAIL-API-PROTO          KRAIL-BFF             KRAIL (app)
─────────────────        ─────────────         ───────────
1. v0.2.0 cut    ──→     2. bump  ──→          3. bump + JourneyMap wiring
   (this doc plans       submodule;            (when proto flag flips on)
    + the work below)    update mapper;
                         tests pass
```

§3 is the v0.2.0 schema work that lands first.
§4 is the BFF-side work that follows once v0.2.0 is tagged.
§7 is the KRAIL-side work that follows once the BFF bump is published.

This doc lives in the BFF repo because the BFF's `JourneyListMapper` is
the place where coords go from "in NSW JSON" to "in proto bytes." The
KRAIL agent reads it to understand what's coming and how to verify
when it arrives.
