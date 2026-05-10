# Testing BFF protobuf endpoints from the KRAIL app

> Audience: KRAIL agent / app maintainer.
> Self-contained: read this one file end-to-end, follow the commands.
> Last updated: 2026-05-10. BFF on `proto-submodule` branch, KRAIL-API-PROTO at `v0.2.0`.

---

## 1 · TL;DR

The BFF serves four protobuf endpoints. All return raw binary bytes, no
JSON wrapper, content-type `application/protobuf` (or
`application/x-protobuf` for GTFS-RT to match NSW).

| Endpoint | Schema | Source of truth |
|---|---|---|
| `GET /api/v1/trip/plan-proto` | `app.krail.bff.proto.JourneyList` | `KRAIL-API-PROTO/proto/api/trip.proto` |
| `GET /v1/gtfs/realtime/{feed}` | `transit_realtime.FeedMessage` (GTFS-RT) | NSW Open Data, byte-for-byte |
| `GET /v2/gtfs/realtime/{feed}` | same | same |
| `GET /v2/gtfs/vehiclepos/{feed}` | same | same |

Five ways to test, in increasing rigour:

1. **`curl` + size check** — prove the endpoint returns bytes, not 4xx/5xx (3).
2. **Dashboard** — point-and-click against any saved-trip-style request (4).
3. **`buf curl` or `protoc --decode`** — decode bytes to JSON for manual inspection (5).
4. **KMP test against the live local BFF** — actual app codepath (6).
5. **Snapshot fixture in CI** — record once, decode in test (7).

---

## 2 · Prereqs

### Required

- Local BFF running. From the BFF repo root:
  ```bash
  cd /Users/ksharma/code/apps/KRAIL-BFF
  ./scripts/dev.sh up
  # Confirm: curl -s -w '%{http_code}\n' http://localhost:8080/health
  ```
- KRAIL-API-PROTO submodule pinned at `v0.2.0` or later in the BFF
  (already true on `proto-submodule` branch). Verify:
  ```bash
  cat /Users/ksharma/code/apps/KRAIL-BFF/krail-api-proto/version.txt
  # Expected: 0.2.0
  ```

### Optional but useful

- **`buf`** — for one-line decode of binary proto into JSON for
  inspection. Install via Homebrew:
  ```bash
  brew install bufbuild/buf/buf
  ```
- **`protoc`** — alternative decoder. Install via Homebrew:
  ```bash
  brew install protobuf
  ```
- **`xxd`** — for raw byte hex view. Already on macOS.

For KMP-side tests in the KRAIL repo, you'll need the `:io:bff-api`
module already wired (per Phase C foundation per the integration
report).

---

## 3 · curl + size check (smoke)

Easiest sanity test. Confirms the endpoint is reachable, returning
binary, with a size in the expected range.

```bash
# Trip planner proto endpoint, real route Town Hall → Bondi Junction.
curl -s -o /tmp/trip.pb -w 'http=%{http_code} ct=%{content_type} bytes=%{size_download}\n' \
  'http://localhost:8080/api/v1/trip/plan-proto?\
name_origin=200070&name_destination=215020&\
depArrMacro=dep&type_destination=any&type_origin=any&\
calcNumberOfTrips=6&TfNSWTR=true&version=10.2.1.42&\
coordOutputFormat=EPSG:4326&itOptionsActive=1&\
computeMonomodalTripBicycle=false&cycleSpeed=16&\
useElevationData=1&outputFormat=rapidJSON'

# Expected output (numbers will vary):
#   http=200 ct=application/protobuf bytes=106154

# GTFS-RT vehicle positions (Sydney Trains).
curl -s -o /tmp/sydneytrains-vehicles.pb -w 'http=%{http_code} bytes=%{size_download}\n' \
  http://localhost:8080/v2/gtfs/vehiclepos/sydneytrains
# Expected: http=200, bytes ~ 200,000-500,000 depending on time of day
```

Pass criteria: HTTP 200, content-type `application/protobuf` or
`application/x-protobuf`, bytes > 0. Anything else is a config or
upstream issue (see 9).

For GTFS-RT, confirm it's protobuf and not error-JSON:

```bash
xxd /tmp/sydneytrains-vehicles.pb | head -3
# First bytes should look like protobuf field tags (low bytes: 0x08, 0x0a, etc).
# If you see {"error": you got a JSON error envelope — check status code.
```

---

## 4 · Dashboard (point-and-click)

The local API tester at <http://localhost:8000/api-tester.html> lists
every BFF endpoint in the left sidebar grouped by KRAIL screen.

Procedure:

1. Open the dashboard. The BFF must be running on `:8080` (port `:8000`
   is the dashboard itself).
2. Click **Trip search → Trip planner — protobuf** in the left sidebar.
3. Fill in stop IDs (defaults to a known-good route).
4. Click **Send**.
5. The Highlights panel at top shows: HTTP status, response size,
   journey count (decoded), correlation ID. Body inspector shows the
   first ~256 bytes as hex preview.

The dashboard doesn't decode the full proto into a friendly tree (yet),
but the size + journey-count signals are usually enough to confirm
"the endpoint works." For full inspection, drop to 5.

For GTFS-RT endpoints, the dashboard groups them under
**Track trip + map (GTFS-RT)**.

---

## 5 · Decode bytes to JSON (`buf` or `protoc`)

### With `buf` (one line, recommended)

```bash
# Hit the endpoint and decode in one pipeline.
curl -s 'http://localhost:8080/api/v1/trip/plan-proto?...your params...' | \
  buf curl -p /Users/ksharma/code/apps/KRAIL-BFF/krail-api-proto \
           --schema=proto/api/trip.proto \
           --type=app.krail.bff.proto.JourneyList \
           --decode

# Or if you've already saved bytes to /tmp/trip.pb:
buf convert /Users/ksharma/code/apps/KRAIL-BFF/krail-api-proto \
            --type app.krail.bff.proto.JourneyList \
            --from /tmp/trip.pb \
            --to /tmp/trip.json
jq '.journeys[0].legs[0] | keys' /tmp/trip.json
# Should now include "coords"
jq '.journeys[0].legs[0].coords | length' /tmp/trip.json
# Expected: a number (50–500 typical for a transit leg)
```

### With `protoc` (more flags, no buf install)

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

### For GTFS-RT (the FeedMessage schema is from gtfs.org, not us)

```bash
# Get the GTFS-RT proto schema (one-time):
curl -s -o /tmp/gtfs-realtime.proto \
  https://gtfs.org/realtime/proto/gtfs-realtime.proto

# Decode a feed:
protoc --decode=transit_realtime.FeedMessage \
       --proto_path=/tmp \
       /tmp/gtfs-realtime.proto \
       < /tmp/sydneytrains-vehicles.pb \
       | head -50
```

### What to look for after decoding

For trip plan-proto:

- `journeys[*].legs[*].transport_leg.coords[]` — present on every transit
  leg, ≥ 10 points typically.
- `journeys[*].legs[*].transport_leg.stops[*].coord` — present on most
  stops; missing is OK.
- `journeys[*].legs[*].transport_leg.walk_interchange.coords[]` — present
  on legs with station-internal walks.
- `journeys[*].legs[*].walking_leg.coords[]` — present on standalone
  walking legs.

For GTFS-RT:

- `entity[].vehicle.position.{latitude, longitude}` — vehicle positions
  feed.
- `entity[].trip_update.stop_time_update[]` — trip updates feed.

---

## 6 · KMP test against the live local BFF

The closest analog to a real production code path — exercises Wire's
KMP-iOS codegen, the actual HTTP client, and the actual decode.

In the KRAIL repo, in the `:io:bff-api` module's `commonTest`:

```kotlin
// io/bff-api/src/commonTest/kotlin/.../TripPlanProtoSmokeTest.kt
package xyz.ksharma.krail.io.bff.api

import app.krail.bff.proto.JourneyList
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO  // or okhttp on Android-only
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hits the local BFF's /api/v1/trip/plan-proto endpoint and asserts
 * the decoded JourneyList has populated polylines.
 *
 * Manual-run only — requires the BFF to be running on :8080. Skip in CI
 * unless we add a Docker-based integration suite.
 */
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

            // Sanity-check the first coord is in Sydney's bbox.
            val first = firstTransit.coords.first()
            assertTrue(first.lat in -34.5..-33.5, "lat ${first.lat} outside Sydney bbox")
            assertTrue(first.lon in 150.0..152.0, "lon ${first.lon} outside Sydney bbox")
        } finally {
            client.close()
        }
    }

    companion object {
        // Town Hall → Bondi Junction. Always-valid route in production data.
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

Run with:

```bash
# From the KRAIL repo root:
./gradlew :io:bff-api:commonTest --tests TripPlanProtoSmokeTest
```

Pitfalls:

- On Android emulator targets, the URL is `10.0.2.2`. On JVM tests
  (which is what `commonTest` actually runs on for this kind of thing),
  use `localhost`. For a single test that runs on both, parameterise
  the host.
- iOS Simulator targets need their own host (`localhost` works there).
- This needs a network call. Don't put it in your default CI suite —
  mark with `@Ignore` or a `@LiveBffTest` annotation that only runs
  manually.

---

## 7 · Snapshot fixture pattern (CI-friendly)

Capture a real BFF response once, commit the bytes, decode them in a
test that has no network dependency.

### Capturing

```bash
# From KRAIL repo root, with the BFF running:
mkdir -p io/bff-api/src/commonTest/resources/fixtures
curl -s -o io/bff-api/src/commonTest/resources/fixtures/trip-townhall-bondi.pb \
  'http://localhost:8080/api/v1/trip/plan-proto?\
name_origin=200070&name_destination=215020&\
depArrMacro=dep&type_destination=any&type_origin=any&\
calcNumberOfTrips=6&TfNSWTR=true&version=10.2.1.42&\
coordOutputFormat=EPSG:4326&itOptionsActive=1&\
computeMonomodalTripBicycle=false&cycleSpeed=16&\
useElevationData=1&outputFormat=rapidJSON'

# Sanity check — the file should be non-trivial.
ls -lh io/bff-api/src/commonTest/resources/fixtures/trip-townhall-bondi.pb
# Expected: ~100 KB
```

### Test

```kotlin
// io/bff-api/src/commonTest/kotlin/.../TripPlanProtoSnapshotTest.kt
class TripPlanProtoSnapshotTest {

    @Test
    fun `captured proto snapshot decodes with non-trivial polyline coverage`() {
        val bytes = readResource("/fixtures/trip-townhall-bondi.pb")
        val list = JourneyList.ADAPTER.decode(bytes)

        // Empirical baselines from the captured fixture (2026-05-10):
        // 6 journeys, ~500 polyline points across all transit legs.
        // Use lower bounds, not exact equality, so NSW data drift doesn't
        // break the test on every dataset refresh.
        assertTrue(list.journeys.size in 1..10, "journeys ${list.journeys.size}")

        val totalCoords = list.journeys
            .flatMap { it.legs }
            .mapNotNull { it.transport_leg }
            .sumOf { it.coords.size }

        assertTrue(totalCoords >= 100, "expected ≥ 100 transit-leg coords, got $totalCoords")

        val stopsWithCoord = list.journeys
            .flatMap { it.legs }
            .mapNotNull { it.transport_leg }
            .flatMap { it.stops }
            .count { it.coord != null }

        assertTrue(stopsWithCoord > 0, "expected some stops with coord populated, got 0")
    }
}
```

`readResource` is your existing JVM/iOS-friendly helper (`:io:gtfs`
already has one for its FeedMessage fixtures — copy that pattern).

This catches both BFF-mapper regressions (suddenly populating fewer
points) and proto-schema regressions (a future bump dropping a field
silently).

---

## 8 · Wire-size comparison (proto vs JSON)

Useful for the cohort-rollout decision: how much data are we actually
saving?

```bash
PARAMS='name_origin=200070&name_destination=215020&depArrMacro=dep&type_destination=any&type_origin=any&calcNumberOfTrips=6&TfNSWTR=true&version=10.2.1.42&coordOutputFormat=EPSG:4326&itOptionsActive=1&computeMonomodalTripBicycle=false&cycleSpeed=16&useElevationData=1&outputFormat=rapidJSON'

JSON=$(curl -s "http://localhost:8080/v1/tp/trip?$PARAMS" | wc -c)
PROTO=$(curl -s "http://localhost:8080/api/v1/trip/plan-proto?$PARAMS" | wc -c)

# Same params, gzipped client-side (mimicking what mobile actually transfers):
JSON_GZ=$(curl -sH 'Accept-Encoding: gzip' --compressed "http://localhost:8080/v1/tp/trip?$PARAMS" | wc -c)
PROTO_GZ=$(curl -sH 'Accept-Encoding: gzip' --compressed "http://localhost:8080/api/v1/trip/plan-proto?$PARAMS" | wc -c)

cat <<EOF
Format       | Raw     | gzipped (Compression plugin)
-------------|---------|------------------------------
JSON         | $JSON  | $JSON_GZ
Proto v0.2.0 | $PROTO   | $PROTO_GZ
Saving       | $((100 - PROTO * 100 / JSON))%
EOF
```

### Live measurements (2026-05-10, Town Hall → Bondi Junction, 6 journeys)

Captured from the local BFF on `:8080` with the same query string.
Reproducible by running the script above against your own BFF.

| Format | Raw bytes | Over-the-wire (gzipped) |
|---|---|---|
| **JSON** (`/v1/tp/trip`) | 367 216 B (~358 KB) | 53 798 B (~52 KB) |
| **Proto v0.2.0** (`/api/v1/trip/plan-proto`) | 103 632 B (~101 KB) | 30 600 B (~30 KB) |
| **Proto win** | **−72%** | **−44%** |

Note: gzip compresses repeated JSON keys (`"departureTimeEstimated"`
appearing 50× in one response) efficiently, so the raw delta is wider
than the wire delta. On mobile, **the wire number is what counts** —
that's actual cellular bytes transferred. **44% smaller on the wire,
72% smaller in memory after decompression.**

Comparison points for the other endpoints (all JSON pass-through; no
proto variants exist yet):

| Endpoint | Raw bytes | Gzipped wire |
|---|---|---|
| `/v1/stops/215020/departures` (Bondi Jct) | 71 281 B | 6 199 B |
| `/v1/parking/availability?stopIds=2155384` (Tallawong, 3 facilities) | 1 926 B | 540 B |
| `/v2/gtfs/vehiclepos/sydneytrains` (already proto) | 37 885 B | 12 359 B |

Why the proto path is still worth adopting even though gzip closes
some of the gap:

- **Wire size matters on cellular regardless of compression.** 44% =
  ~23 KB saved per trip request. With the home-screen rendering 6
  journey options (1× trip request) + departures + GTFS-RT, total
  per-cold-render savings are ~50–80 KB on a typical Sydney commute
  query.
- **Decode is faster.** Wire's binary decode is roughly 3-5× faster
  than `kotlinx.serialization` JSON decode for the equivalent nested
  structure. Real impact on lower-end devices.
- **Smaller heap allocation post-decode.** No tokenizer state, fewer
  intermediate strings.
- **Stable schema, no NSW-rename surprises.** Wire-format fields are
  identified by number; renaming is a major-bump consumer-side. JSON
  is identified by string key, so an upstream NSW rename would
  silently break the parser.

The 23 KB wire saving alone is worth ~70 ms of cellular transfer at
2 Mbps (typical 4G in low-signal Sydney transit corridors). For a
trip-card mount that already takes ~600–950 ms warm, that's a
measurable user-visible improvement.

---

## 9 · Common pitfalls when testing proto

| Symptom | Cause | Fix |
|---|---|---|
| `curl` returns HTML | Hit a non-existent route. | Check the path; `/api/v1/trip/plan-proto` not `/v1/api/trip/plan-proto`. |
| HTTP 400 `missing_origin` | Required query param absent. | Send `name_origin=` and `name_destination=`. |
| HTTP 502 / 503 | NSW upstream down or BFF can't reach it. | Check BFF logs (`./scripts/dev.sh logs`); often resolves on retry. |
| HTTP 503 `daily_budget_exceeded` | BFF's daily NSW quota hit. | Wait for Sydney midnight or bump the env var. |
| `JourneyList.ADAPTER.decode(bytes)` throws | Body is JSON or HTML, not proto. | Confirm endpoint path and `Content-Type` header. |
| `journeys` is empty list | NSW returned `error: {...}` for those params. | Check params; pick a known-good route (Town Hall 200070 → Bondi Junction 215020). |
| `coords` is empty everywhere | (a) Submodule pinned below v0.2.0; (b) BFF mapper bug. | (a) `cat krail-api-proto/version.txt` should be 0.2.0+. (b) Hit `/v1/tp/trip` for the same params; if JSON has `legs[].coords` and proto doesn't, the BFF mapper's broken — file an issue. |
| `coord` always `null` on stops | Same as above — submodule too old, or NSW didn't publish geometry for those stops. | Try a different route. |
| iOS test crashes on decode | Wire/iOS codegen quirk. | Confirm Wire 6.2.0+ + KMP iOS targets configured per `:io:gtfs` precedent. |
| Tests in CI flake | Live BFF tests aren't deterministic — NSW data drifts. | Use snapshot fixtures (7), not live calls, in CI. |

---

## 10 · Reference table — what's expected to be populated

For a real Town Hall → Bondi Junction trip (multi-train, 6 journey
options):

| Field | Cardinality | Typical value |
|---|---|---|
| `journeys` | 1–6 | 6 |
| `journeys[].legs` | 1–4 | 2–3 (transit + interchange) |
| `journeys[*].legs[*].transport_leg.coords[]` | 0–500+ | 50–500 per transit leg |
| `journeys[*].legs[*].transport_leg.stops[]` | 0–30 | 3–15 |
| `journeys[*].legs[*].transport_leg.stops[*].coord` | nullable | populated for ~95% of stops |
| `journeys[*].legs[*].transport_leg.walk_interchange.coords[]` | 0–30 | 5–20 when present |
| `journeys[*].legs[*].walking_leg.coords[]` | 0–50 | 5–20 for first/last walk |
| `journeys[*].legs[*].transport_leg.service_alert_list` | 0–N | usually 0; some routes have alerts |
| `journeys[*].legs[*].transport_leg.trip_id` | nullable | populated for trains/metros (GTFS-RT trackable) |

If your test sees a route returning materially less than these, the
route may not be a good test fixture — pick something else, or just
check NSW's actual response shape via `/v1/tp/trip` with the same
params.

---

## 11 · Closing checklist for the KRAIL agent

When wiring this up on the KRAIL side:

- [ ] BFF running locally, `/health` returns 200.
- [ ] `krail-api-proto` submodule in KRAIL bumped to `v0.2.0` or later.
- [ ] `:io:bff-api:compileKotlinMetadata` regenerates Wire classes.
- [ ] Generated `JourneyList`, `TransportLeg`, `Stop`, `Coord` types
      are visible from `commonMain`.
- [ ] `curl` smoke (3) returns 200 and `~100 KB`.
- [ ] `buf curl` decode (5) shows `coords` populated on transit legs.
- [ ] KMP smoke test (6) passes when the BFF is up.
- [ ] Snapshot fixture committed (7) and CI test passes without
      network.
- [ ] Wire-size comparison (8) recorded in the proto-flag rollout
      doc — gives concrete numbers for the cohort decision.
- [ ] JourneyMapScreen reads polylines from the proto path when the
      flag is on; same render as the JSON path.

When the proto-flag rollout reaches 100% and stays clean for 2 weeks,
you can delete the JSON-path branch in `RealTripPlanningService` —
that's the long-term win that motivated the proto work in the first
place.
