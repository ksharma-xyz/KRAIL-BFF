# Testing the BFF — JSON and protobuf endpoints

> Audience: KRAIL agent / app maintainer. Self-contained — read
> top-to-bottom or jump to the section you need.
> Pairs with `API_REFERENCE.md` for the endpoint specs themselves.

---

## 1 · Prereqs

- BFF running locally:
  ```bash
  cd /Users/ksharma/code/apps/KRAIL-BFF
  ./scripts/dev.sh up
  curl -s -w '%{http_code}\n' http://localhost:8080/health   # expect 200
  ```
- Dashboard at <http://localhost:8000/api-tester.html> (auto-started
  by `dev.sh up`).
- Optional, only for proto inspection:
  - `brew install bufbuild/buf/buf` for one-line decode → JSON.
  - `brew install protobuf` for `protoc --decode` as an alternative.

For KRAIL-side tests, the `:io:bff-api` module needs to be wired
(Phase C foundation; per the integration report this is already done).

---

## 2 · Five testing methods

| Method | When to use |
|---|---|
| **2.1** curl + size check | Fastest sanity — endpoint reachable, returns sensible bytes, status code OK |
| **2.2** Dashboard | Point-and-click against any endpoint, see Highlights panel |
| **2.3** `jq` decode (JSON) / `buf curl` decode (proto) | Inspect actual response shape |
| **2.4** Live KMP test from `:io:bff-api` | Closest to real production codepath |
| **2.5** Snapshot fixture in CI | No live BFF needed; run on every PR |

Default: do 2.1 + 2.2 first. Drop to 2.3 to verify a specific field.
Move to 2.4 when wiring up the actual app integration. Add 2.5 once
the pattern is stable enough to commit.

---

## 2.1 · curl + size check

Smoke. Confirms the endpoint is up and returning sensible bytes.

```bash
# JSON endpoint — trip planner
curl -s -o /tmp/trip.json \
     -w 'http=%{http_code} ct=%{content_type} bytes=%{size_download}\n' \
     'http://localhost:8080/v1/tp/trip?\
name_origin=200070&name_destination=215020&\
depArrMacro=dep&type_destination=any&type_origin=any&\
calcNumberOfTrips=6&TfNSWTR=true&version=10.2.1.42&\
coordOutputFormat=EPSG:4326&itOptionsActive=1&\
computeMonomodalTripBicycle=false&cycleSpeed=16&\
useElevationData=1&outputFormat=rapidJSON'
# Expect: http=200 ct=application/json bytes ~ 360,000

# Proto endpoint — same trip, smaller wire
curl -s -o /tmp/trip.pb \
     -w 'http=%{http_code} ct=%{content_type} bytes=%{size_download}\n' \
     'http://localhost:8080/api/v1/trip/plan-proto?...same params...'
# Expect: http=200 ct=application/protobuf bytes ~ 100,000

# Departures (JSON)
curl -s -o /tmp/dep.json \
     -w 'http=%{http_code} bytes=%{size_download}\n' \
     'http://localhost:8080/v1/stops/200060/departures'
# Expect: http=200 bytes ~ 70,000

# Park & Ride batch (JSON)
curl -s -o /tmp/parking.json \
     -w 'http=%{http_code} bytes=%{size_download}\n' \
     'http://localhost:8080/v1/parking/availability?stopIds=2155384'
# Expect: http=200 bytes ~ 1,900-2,000

# GTFS-RT vehicles (binary proto)
curl -s -o /tmp/gtfs.pb \
     -w 'http=%{http_code} bytes=%{size_download}\n' \
     'http://localhost:8080/v2/gtfs/vehiclepos/sydneytrains'
# Expect: http=200 bytes ~ 30,000-500,000 (varies with time of day)

# For binary protobuf, sanity check it isn't an error JSON:
xxd /tmp/trip.pb | head -1     # First bytes look like proto field tags
xxd /tmp/gtfs.pb | head -1     # Same — not the start of {"error":...}
```

Pass criteria: HTTP 200, Content-Type matches, bytes > 0. Anything else
is a config or upstream issue — see section 8.

---

## 2.2 · Dashboard (point-and-click)

<http://localhost:8000/api-tester.html> — works while `dev.sh up` is
running.

Procedure:

1. Open the dashboard.
2. Pick an endpoint from the left sidebar (grouped by KRAIL screen:
   Home / Search / Trip / Track).
3. Fill in params (defaults populate for known-good cases).
4. Click **Send**.
5. Inspect:
   - **Top "Highlights" panel** — surfaces the important fields with
     copy buttons (correlation ID, journey count, next departure, error
     code).
   - **Body inspector** — full response. JSON gets a foldable tree;
     protobuf gets a hex preview.
   - **Compare-with-NSW** button (trip-planner endpoints only) — fires
     the same query directly at NSW and side-by-sides the response.
     Useful for "is the BFF dropping a field?" investigations.
   - **Sparkline** in the sidebar tracks per-endpoint latency over the
     session.

For most "is this endpoint working" questions the dashboard is the
fastest path.

---

## 2.3 · Decoding response bodies for inspection

### JSON (trip, departures, parking)

```bash
# What top-level keys does the response have?
jq 'keys' /tmp/trip.json
# → ["error","journeys","systemMessages","version"]

# Trip-planner: how many journeys, how many polyline points per leg?
jq '.journeys | length' /tmp/trip.json
jq '.journeys[0].legs[].coords | length' /tmp/trip.json

# Departures: next departure on a stop?
jq '.stopEvents[0] | {time: .departureTimeEstimated, line: .transportation.disassembledName, dest: .transportation.destination.name}' /tmp/dep.json

# Parking batch (stop-ID variant): which facilities resolved?
jq '.stops | keys' /tmp/parking.json
jq '.unknownStops' /tmp/parking.json
```

### Protobuf — `buf curl` (recommended, one-line decode)

```bash
# Decode an already-saved .pb file to JSON for inspection.
buf convert /Users/ksharma/code/apps/KRAIL-BFF/krail-api-proto \
            --type app.krail.bff.proto.JourneyList \
            --from /tmp/trip.pb \
            --to /tmp/trip-decoded.json

# Now inspect like any JSON.
jq '.journeys[0].legs[0] | keys' /tmp/trip-decoded.json
# Should include "coords", "stops", "transportModeLine", etc.

jq '.journeys[0].legs[0].coords | length' /tmp/trip-decoded.json
# Expected: 50–500 for a transit leg
```

### Protobuf — `protoc --decode` (no buf install)

```bash
protoc --decode=app.krail.bff.proto.JourneyList \
       --proto_path=/Users/ksharma/code/apps/KRAIL-BFF/krail-api-proto/proto \
       /Users/ksharma/code/apps/KRAIL-BFF/krail-api-proto/proto/api/trip.proto \
       < /tmp/trip.pb \
       | head -40
# Output is text-format protobuf, human-readable. coords blocks look like:
#   coords {
#     lat: -33.8829
#     lon: 151.2061
#   }
```

### GTFS-RT (the schema is from gtfs.org, not us)

```bash
# Get the GTFS-RT proto schema once.
curl -s -o /tmp/gtfs-realtime.proto \
  https://gtfs.org/realtime/proto/gtfs-realtime.proto

# Decode a feed.
protoc --decode=transit_realtime.FeedMessage \
       --proto_path=/tmp \
       /tmp/gtfs-realtime.proto \
       < /tmp/gtfs.pb \
       | head -50
# Look for: entity[].vehicle.position.{latitude,longitude} for vehiclepos feeds
# Or:       entity[].trip_update.stop_time_update[] for trip-update feeds
```

### What to look for after decoding

For trip plan (JSON or proto):

| Field | Should contain |
|---|---|
| `journeys[].legs[*].coords[]` | 50–500 points per transit leg |
| `journeys[].legs[*].stops[*].coord` (proto) / `stopSequence[].coord` (JSON) | populated for ~95% of stops |
| `journeys[].legs[*].walk_interchange.coords[]` (proto) / `legs[].interchange.coords` (JSON) | populated when station-internal walks exist |
| `journeys[].legs[*].transport_mode_line` (proto) / `legs[].transportation` (JSON) | always populated on transit legs |

For departures: `stopEvents[]` with the next service line, destination,
and times.

For parking: `facilities` (success) + `errors` (per-facility) +
`stops[].facilities` for the stop-ID variant.

For GTFS-RT: `entity[]` with `vehicle.position.{latitude,longitude}` or
`trip_update.stop_time_update[]`.

---

## 2.4 · Live KMP test from `:io:bff-api`

The closest analog to a real production codepath. Hits the actual
local BFF, exercises the actual HTTP client + decoder.

```kotlin
// io/bff-api/src/commonTest/kotlin/.../TripPlanProtoSmokeTest.kt
package xyz.ksharma.krail.io.bff.api

import app.krail.bff.proto.JourneyList
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class TripPlanProtoSmokeTest {

    @Test
    fun `proto response includes coords on transit legs`() = runTest {
        val client = HttpClient(CIO)
        try {
            val bytes = client.get(BFF_URL).readRawBytes()
            val list = JourneyList.ADAPTER.decode(bytes)

            assertTrue(list.journeys.isNotEmpty(), "expected at least one journey")

            val firstTransit = list.journeys
                .flatMap { it.legs }
                .firstNotNullOf { it.transport_leg }

            assertTrue(
                firstTransit.coords.isNotEmpty(),
                "first transit leg should have coords[] populated; got empty list"
            )

            // Sanity-check the coord values are in Sydney's bbox.
            val first = firstTransit.coords.first()
            assertTrue(first.lat in -34.5..-33.5)
            assertTrue(first.lon in 150.0..152.0)
        } finally {
            client.close()
        }
    }

    companion object {
        private const val BFF_URL =
            "http://10.0.2.2:8080/api/v1/trip/plan-proto?" +
                "name_origin=200070&name_destination=215020&" +
                "depArrMacro=dep&type_destination=any&type_origin=any&" +
                "calcNumberOfTrips=6&TfNSWTR=true&version=10.2.1.42&" +
                "coordOutputFormat=EPSG:4326&itOptionsActive=1&" +
                "computeMonomodalTripBicycle=false&cycleSpeed=16&" +
                "useElevationData=1&outputFormat=rapidJSON"
    }
}
```

Run:

```bash
# From the KRAIL repo root, with the BFF up:
./gradlew :io:bff-api:commonTest --tests TripPlanProtoSmokeTest
```

Notes:
- Android emulator: `10.0.2.2`. iOS Simulator: `localhost`. JVM/test:
  `localhost`. Parameterise the host if you want one test for both.
- This needs network. **Don't put it in the default CI suite.** Mark
  with `@Ignore` or a `@LiveBffTest` annotation that only runs
  manually. CI gets the snapshot version below.

---

## 2.5 · Snapshot fixture for CI

Capture once, commit the bytes, decode in a network-free test.

```bash
# From the KRAIL repo root, BFF running:
mkdir -p io/bff-api/src/commonTest/resources/fixtures
curl -s -o io/bff-api/src/commonTest/resources/fixtures/trip-townhall-bondi.pb \
  'http://localhost:8080/api/v1/trip/plan-proto?...your params...'

ls -lh io/bff-api/src/commonTest/resources/fixtures/trip-townhall-bondi.pb
# Expected ~ 100 KB
```

```kotlin
// io/bff-api/src/commonTest/kotlin/.../TripPlanProtoSnapshotTest.kt
class TripPlanProtoSnapshotTest {

    @Test
    fun `captured proto snapshot decodes with non-trivial polyline coverage`() {
        val bytes = readResource("/fixtures/trip-townhall-bondi.pb")
        val list = JourneyList.ADAPTER.decode(bytes)

        assertTrue(list.journeys.size in 1..10)

        val totalPolylinePts = list.journeys
            .flatMap { it.legs }
            .mapNotNull { it.transport_leg }
            .sumOf { it.coords.size }

        assertTrue(
            totalPolylinePts >= 100,
            "expected ≥ 100 transit-leg coords, got $totalPolylinePts"
        )
    }
}
```

`readResource` is your existing JVM/iOS-friendly helper (the `:io:gtfs`
module already has one for `FeedMessage` fixtures — copy that pattern).

This catches both BFF-mapper regressions (suddenly populating fewer
points) and proto-schema regressions (a future bump silently dropping
a field).

For JSON endpoints, the same approach works with a captured `.json`
fixture and `Json.decodeFromString<TripResponse>(bytes)`.

---

## 3 · Wire-size comparison (proto vs JSON)

Useful for the cohort-rollout decision: how much data are we actually
saving?

```bash
PARAMS='name_origin=200070&name_destination=215020&depArrMacro=dep&type_destination=any&type_origin=any&calcNumberOfTrips=6&TfNSWTR=true&version=10.2.1.42&coordOutputFormat=EPSG:4326&itOptionsActive=1&computeMonomodalTripBicycle=false&cycleSpeed=16&useElevationData=1&outputFormat=rapidJSON'

JSON=$(curl -s "http://localhost:8080/v1/tp/trip?$PARAMS" | wc -c)
PROTO=$(curl -s "http://localhost:8080/api/v1/trip/plan-proto?$PARAMS" | wc -c)
JSON_GZ=$(curl -sH 'Accept-Encoding: gzip' "http://localhost:8080/v1/tp/trip?$PARAMS" | wc -c)
PROTO_GZ=$(curl -sH 'Accept-Encoding: gzip' "http://localhost:8080/api/v1/trip/plan-proto?$PARAMS" | wc -c)

cat <<EOF
Format       | Raw     | Wire (gzipped)
-------------|---------|----------------
JSON         | $JSON  | $JSON_GZ
Proto v0.2.0 | $PROTO   | $PROTO_GZ
Saving       | $((100 - PROTO * 100 / JSON))% raw | $((100 - PROTO_GZ * 100 / JSON_GZ))% wire
EOF
```

Live numbers (2026-05-10, Town Hall → Bondi Junction trip):

| Format | Raw | Wire (gzipped) |
|---|---|---|
| JSON | 367 KB | 54 KB |
| Proto v0.2.0 | 104 KB | 31 KB |
| Saving | -72% raw, **-44% wire** |

The wire (gzipped) number is what matters for cellular cost. Proto
saves ~23 KB per trip request after gzip. See `API_REFERENCE.md`
section 11 for the full benchmark table including the other endpoints.

---

## 4 · What's expected to be populated (reference cardinalities)

For a real Town Hall → Bondi Junction trip (multi-train, 6 journey
options):

| Field | Cardinality |
|---|---|
| `journeys[]` | 1–6 (cap is `calcNumberOfTrips`) |
| `journeys[].legs[]` | 1–4 (transit + interchange) |
| `journeys[].legs[*].coords[]` | 50–500 per transit leg |
| `journeys[].legs[*].stops[]` | 3–15 |
| `journeys[].legs[*].stops[*].coord` | populated for ~95% of stops |
| `journeys[].legs[*].walk_interchange.coords[]` | 5–20 when present |
| `journeys[].legs[*].walking_leg.coords[]` | 5–20 for first/last walk |
| `journeys[].legs[*].service_alert_list[]` | usually 0; some routes have alerts |
| `journeys[].legs[*].trip_id` | populated for trains/metros (GTFS-RT trackable) |

If your test sees a route returning materially less than these, the
route may not be a good test fixture — pick something else, or check
NSW's actual response shape via `/v1/tp/trip` with the same params.

---

## 5 · Common pitfalls

| Symptom | Cause | Fix |
|---|---|---|
| `curl` returns HTML | Hit a non-existent route. | Check the path; `/api/v1/trip/plan-proto` not `/v1/api/trip/plan-proto`. |
| HTTP 400 `missing_origin` | Required query param absent. | Send `name_origin=` and `name_destination=`. |
| HTTP 400 `missing_ids` (parking) | `?ids=` or `?stopIds=` missing on the batch endpoint. | One of them is required. |
| HTTP 502 / 503 | NSW upstream failed. | Check BFF logs (`./scripts/dev.sh logs`); often resolves on retry. |
| HTTP 503 `daily_budget_exceeded` | BFF's daily NSW quota hit. | Wait for Sydney midnight or bump the env var. |
| `JourneyList.ADAPTER.decode(bytes)` throws | Body is JSON or HTML, not proto. | Confirm endpoint path and `Content-Type` header. |
| `journeys` is empty list | NSW returned `error: {...}` for those params. | Pick a known-good route (Town Hall 200070 → Bondi Junction 215020). |
| `coords` is empty everywhere | Submodule pinned below v0.2.0, or BFF mapper bug. | `cat krail-api-proto/version.txt` should be 0.2.0+. If JSON has `coords` and proto doesn't, it's a BFF mapper bug. |
| `coord` always `null` on stops | Submodule too old, or NSW didn't publish geometry for those stops. | Try a different route. |
| iOS test crashes on decode | Wire/iOS codegen quirk. | Confirm Wire 6.2.0+ + KMP iOS targets per `:io:gtfs` precedent. |
| Tests in CI flake | Live BFF tests aren't deterministic — NSW data drifts. | Use snapshot fixtures (section 2.5), not live calls, in CI. |
| Body cut off in dashboard | Some browsers truncate the inline preview at 5 MB. | Save via curl, decode locally. |

---

## 6 · Closing checklist

When you've finished testing an endpoint:

- [ ] BFF `/health` returns 200 from your dev machine.
- [ ] curl smoke (section 2.1) returns 200 + sensible byte count.
- [ ] Decoded response (section 2.3) shows the fields you expected
      from `API_REFERENCE.md`.
- [ ] Live KMP test (section 2.4) passes when the BFF is up
      (manual run only — not in CI).
- [ ] Snapshot fixture (section 2.5) committed and CI test passes
      without network.
- [ ] Wire-size comparison (section 3) recorded if this endpoint
      drives a cohort-rollout decision.
- [ ] No obvious regressions vs the cardinalities in section 4.

If anything failed: check section 5 first. If still stuck, the BFF
log (`./scripts/dev.sh logs`) and the dashboard's "Compare with NSW"
button cover most cases.
