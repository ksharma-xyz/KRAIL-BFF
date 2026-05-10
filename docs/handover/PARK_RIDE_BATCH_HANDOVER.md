# Park & Ride batching — handover for the KRAIL app

> **Audience:** the KRAIL agent / app maintainer. From: KRAIL-BFF team.
> **Status as of 2026-05-10:** Phase A landed on the BFF on the
> `proto-submodule` branch (commit `c029e29`). Local-tested against the
> live BFF on :8080. Phase B is a future protocol bump.

---

## 1 · TL;DR

We've added two new behaviors to `GET /v1/parking/availability` so the
home-screen parking cards can stop firing N sequential HTTP calls when
they mount.

- **`?ids=486,487,488`** — fan-out batch. Same facility-IDs you already
  have, in one HTTP call. **Lowest-friction migration: keep your
  existing Firebase RC `NSW_PARK_RIDE_FACILITIES` flag, just send the
  facility-id list to the BFF.**
- **`?stopIds=275010,2155384`** — pass saved-trip stop IDs directly. The
  BFF resolves stop → facility(s) server-side and fans out the NSW
  calls. **You never see facility IDs.** Once adopted, you can delete
  `RealNswParkRideFacilityManager` and the `NSW_PARK_RIDE_FACILITIES`
  RC flag entirely.

Both modes return 200 with structured success/error splits even on
partial NSW failure. Per-IP rate limit counts each batch as 1 call.

**Phase B (future):** move the stop→facility lookup into the weekly
stops dataset (.pb). Once that ships in `KRAIL-API-PROTO v0.2.0`, you
can read facility IDs straight from your already-cached dataset and
the server-side lookup table here goes away.

---

## 2 · What landed in the BFF (this work)

### Endpoint contract

`GET /v1/parking/availability` with two query-param modes (mutually
exclusive — if both are sent, `stopIds` wins).

#### Mode 1 — facility-ID batch (`?ids=`)

```
GET /v1/parking/availability?ids=486,487,488
```

Response (200 OK, partial success allowed):

```json
{
  "facilities": {
    "486": { /* full NSW carpark body for facility 486 */ },
    "487": { /* full NSW carpark body for facility 487 */ }
  },
  "errors": {
    "488": {
      "code": "upstream_404",
      "message": "NSW carpark returned 404 "
    }
  },
  "correlationId": "942cf2c5-…"
}
```

Each value under `facilities[id]` is the **same JSON shape** as today's
single-facility endpoint (`/v1/parking/facilities/{id}/availability`).
Your existing `CarParkFacilityDetailResponse` parser works unchanged
per-facility.

Errors are keyed by the same id with `{code, message}`. The full set of
codes you'll see:

| `code` | When |
|---|---|
| `invalid_facility_id` | The id failed regex `^[A-Za-z0-9_-]{1,40}$` |
| `upstream_404` (or `_400`, `_429`, …) | NSW returned that 4xx |
| `upstream_error` | NSW returned 5xx, or returned non-JSON |
| `daily_budget_exceeded` | BFF's daily NSW call quota has run out |

Caps:
- ≤ 20 ids per request → otherwise 400 `too_many_ids`.
- Empty / missing `ids` → 400 `missing_ids`.
- Duplicate ids are silently deduped before fan-out.

#### Mode 2 — stop-ID resolution (`?stopIds=`)

```
GET /v1/parking/availability?stopIds=275010,2155384
```

Response (200 OK):

```json
{
  "stops": {
    "275010": {
      "facilities": {
        "21": { /* Penrith at-grade NSW body */ },
        "22": { /* Penrith multi-level NSW body */ }
      },
      "errors": {}
    },
    "2155384": {
      "facilities": {
        "26": { /* Tallawong P1 */ },
        "27": { /* Tallawong P2 */ },
        "28": { /* Tallawong P3 */ }
      },
      "errors": {}
    }
  },
  "unknownStops": [],
  "correlationId": "942cf2c5-…"
}
```

When a requested stop has no mapped facilities, it goes to
`unknownStops` (top-level array of stop IDs). Per-facility upstream
failures within a known stop go to that stop's `errors` block.

`NSW:` namespace prefix is stripped before lookup — `NSW:213110` and
`213110` both resolve to facility 486. The response key preserves
whatever you sent, so you can use either form consistently in your
caller.

Caps:
- ≤ 20 stop IDs per request → otherwise 400 `too_many_stop_ids`.
- Empty / missing `stopIds` → 400 `missing_stop_ids`.
- Stop IDs that fail format regex → join `unknownStops` (treated as
  unmapped, not as a hard 400, so a typo on one stop doesn't take
  down the whole batch).

The BFF dedupes facility IDs across resolved stops, so two stop IDs
aliasing the same facility (e.g. both Hornsby stops → facility 25) =
exactly one NSW call. Each unique facility is fetched concurrently;
total wall-clock = roughly the slowest NSW call, not the sum.

### What's in the lookup table

The 47-entry stop→facility map lives in
`server/src/main/kotlin/app/krail/bff/data/ParkRideStopFacilityMap.kt`.
It's a literal port of your current `NSW_PARK_RIDE_FACILITIES` Firebase
Remote Config payload. Same stop IDs, same facility IDs, same names.

Update flow when NSW opens / closes a park-and-ride:
1. Edit `ParkRideStopFacilityMap.kt`.
2. Open a PR; CI (`pr.yml`) runs the contract tests.
3. Merge; deploy.
4. Every consumer (KRAIL Android, KRAIL iOS, future web) sees the
   new mapping without an app release.

---

## 3 · Migration choices for KRAIL

You have three places to land. Pick based on how much surface area you
want to delete.

### Option 3.1 — minimal (use `?ids=` only)

Keep `RealNswParkRideFacilityManager`, keep the Firebase RC flag. Just
batch the per-facility calls.

In your `ParkRideService` (or wherever the home-card mount fires the
N requests):

```kotlin
// before — N sequential calls
suspend fun fetchAvailabilityFor(stopId: String): List<CarParkFacilityDetailResponse> {
    val facilityIds = facilityManager.facilityIdsForStop(stopId)
    return facilityIds.map { fetchSingle(it) }   // N HTTP calls
}

// after — 1 batch call
suspend fun fetchAvailabilityFor(stopId: String): List<CarParkFacilityDetailResponse> {
    val facilityIds = facilityManager.facilityIdsForStop(stopId)
    if (facilityIds.isEmpty()) return emptyList()
    val ids = facilityIds.joinToString(",")
    val batch: ParkingBatchResponse =
        httpClient.get("$KRAIL_BFF_BASE_URL/v1/parking/availability") {
            url { parameters.append("ids", ids) }
        }.body()
    // Order by the requested ids; missing ids = upstream error.
    return facilityIds.mapNotNull { id -> batch.facilities[id] }
}
```

`ParkingBatchResponse` is a new model:

```kotlin
@Serializable
data class ParkingBatchResponse(
    val facilities: Map<String, CarParkFacilityDetailResponse> = emptyMap(),
    val errors: Map<String, BatchError> = emptyMap(),
    val correlationId: String? = null,
)

@Serializable
data class BatchError(val code: String, val message: String)
```

**Win:** N → 1 HTTP round trip. Latency drops from `Σ(NSW per-call)` to
`max(NSW per-call)`. KRAIL-side measured 270ms → 146ms for 3 facilities
during BFF smoke testing.

**Surface deleted:** zero. Same `RealNswParkRideFacilityManager`, same
RC flag.

### Option 3.2 — recommended (`?stopIds=`)

Pass saved-trip stop IDs directly. The BFF abstracts away facility IDs
entirely.

```kotlin
// :feature:park-ride/network/.../service/RealParkRideService.kt
override suspend fun fetchAvailabilityForStops(
    stopIds: List<String>,
): ParkRideStopAvailabilities {
    val joined = stopIds.joinToString(",")
    val response: ParkingStopBatchResponse =
        httpClient.get("$KRAIL_BFF_BASE_URL/v1/parking/availability") {
            url { parameters.append("stopIds", joined) }
        }.body()
    return response.toDomain()
}
```

`ParkingStopBatchResponse`:

```kotlin
@Serializable
data class ParkingStopBatchResponse(
    val stops: Map<String, StopFacilities> = emptyMap(),
    val unknownStops: List<String> = emptyList(),
    val correlationId: String? = null,
)

@Serializable
data class StopFacilities(
    val facilities: Map<String, CarParkFacilityDetailResponse> = emptyMap(),
    val errors: Map<String, BatchError> = emptyMap(),
)
```

**Win:** same latency win as Option 3.1, plus you can delete the
following from KRAIL:

- `RealNswParkRideFacilityManager.kt`
- `NswParkRideFacilityManager` interface
- `NswParkRideFacility` model
- The `NSW_PARK_RIDE_FACILITIES` Firebase Remote Config flag
- The `JsonConfig.lenient.parseToJsonElement` wiring used to read it
- All call sites that resolved stop → facility on the app side

**Cost:** more BFF coupling. But that's the point of a BFF — concentrate
this kind of lookup in one place.

This is the option I'd take. The `?ids=` mode stays available as an
escape hatch / debugging tool.

### Option 3.3 — leave as-is for now

Keep firing N HTTP calls per home-card mount. Move on. The new endpoint
sits unused.

The thing is, the `:feature:track:` GTFS-RT polling already loads the
home screen with a few network calls; cutting parking from N→1 is
roughly a 10-15% latency improvement on the cold home-screen render.
Probably worth doing once you have a free evening.

---

## 4 · Testing the new endpoint locally

Same dev workflow you've been using. The BFF on `:8080` already serves
the new endpoint as of `proto-submodule` `c029e29`.

```bash
# From a Mac terminal:

# Mode 1 — facility-id batch
curl 'http://localhost:8080/v1/parking/availability?ids=486,487,488' | jq

# Mode 2 — stop-id resolution (Tallawong has 3 facilities)
curl 'http://localhost:8080/v1/parking/availability?stopIds=2155384' | jq

# Mode 2 — multiple stops + an unknown one
curl 'http://localhost:8080/v1/parking/availability?stopIds=275010,2155384,9999999' | jq
```

For Android emulator testing, use `http://10.0.2.2:8080`. For iOS sim,
`http://localhost:8080`. (Same as in the master plan.)

The dashboard at <http://localhost:8000/api-tester.html> doesn't have
the new endpoint registered yet — you can hit it via the **Debug any
URL** form at the top of the sidebar in the meantime.

---

## 5 · Phase B — what's deferred (future, after KRAIL-API-PROTO v0.2.0)

**Goal:** retire the server-side `ParkRideStopFacilityMap.kt` lookup
and move the stop→facility mapping into the weekly stops dataset (.pb).
This makes parking metadata follow the same distribution pattern as
everything else (versioned via SemVer, cached locally on the app).

### What changes

1. **Proto schema bump in `KRAIL-API-PROTO`** — add a field to
   `DatasetStop`:
   ```proto
   // proto/data/stops_dataset.proto
   message DatasetStop {
     string stop_id = 1;
     string name = 2;
     optional LatLng position = 3;
     repeated TransportMode modes = 4;

     // contract: optional — facility IDs of any park-and-ride at this stop.
     // Empty list when the stop has no park-and-ride.
     repeated string park_ride_facility_ids = 5;
   }
   ```
   This is a minor version bump (additive to an `optional`-spirit field;
   `buf breaking` allows it).

2. **BFF dataset builder updates.** The weekly `BuildStopsDataset`
   tool reads `ParkRideStopFacilityMap` (still server-side) and
   populates `park_ride_facility_ids` per stop. The map stays as the
   source of truth; the .pb is the redistribution layer.

3. **KRAIL side:**
   - `proto-bump.yml` opens its standard auto-PR bumping the submodule.
   - You add a `parkRideFacilityIds` field reader on the cached
     `DatasetStop`.
   - Replace the call to `/v1/parking/availability?stopIds=` with:
     ```kotlin
     val facilityIds = stopsDataset.find(stopId)?.parkRideFacilityIds.orEmpty()
     if (facilityIds.isNotEmpty()) {
         httpClient.get("$BFF_BASE_URL/v1/parking/availability") {
             url { parameters.append("ids", facilityIds.joinToString(",")) }
         }
     }
     ```
   - Stop search + parking metadata now both read from the same
     locally-cached file. Zero network call to find out which
     facilities a stop has.

4. **Eventually delete the BFF's `?stopIds=` mode** — the `?ids=` mode
   is enough once the app already knows facility IDs from its dataset.
   Removal would be a major bump for the BFF endpoint, so coordinate.

### Why this isn't done now

- It needs a proto schema change in `KRAIL-API-PROTO`. We just cut
  `v0.1.0`; adding a single field doesn't justify a new release on
  its own. It'll go out with the next minor bump that bundles other
  schema work (likely the screen-shaped messages —
  `TripResultsResponse`, `DepartureBoardResponse`, etc.).
- The current Phase A solution is operationally good: 47-entry static
  map updated via a single PR + redeploy. NSW opens new park-and-rides
  ~quarterly; redeploy cadence is fine.
- The dataset builder needs a small change too (read
  `ParkRideStopFacilityMap` and emit facility IDs into each
  `DatasetStop`). That's coupled to whoever owns the next dataset
  workflow change.

### Trigger to revisit

When **either** of these happens:

1. The KRAIL-API-PROTO repo cuts `v0.2.0` (e.g. when `TripResultsResponse`
   lands). At that point, adding `park_ride_facility_ids` to the same
   release is essentially free.
2. The lookup table grows past ~150 entries (mass NSW expansion). At
   that scale, hand-editing a Kotlin file becomes a maintenance
   smell and the dataset distribution model is a better fit.

Until then: leave Phase A in place.

---

## 6 · Open questions for KRAIL side

1. **Which option (3.1 vs 3.2)?** I recommend 3.2 — clean abstraction
   and you delete code. But 3.1 is one PR away if you'd rather take
   it incrementally.

2. **When to flip the home screen to use the batch endpoint?** Behind
   a separate Firebase RC flag (e.g. `bff_use_for_park_ride`)? Or
   coupled to the existing `bff_use_for_park_ride` from the master
   plan? I'd say **same flag** — the batch endpoint is a strict
   improvement on the per-facility endpoint, no need to gate them
   independently. Cohort 10 / 50 / 100 the whole BFF parking path
   together.

3. **Telemetry to confirm the win in production:** track
   `ParkRideHomeCardMountToFirstRender` p95 before and after the BFF
   parking flag flips. Should drop noticeably (your report measured
   3-in-burst calls totalling ~150ms cold; batch should be ~50ms
   cold for the same cards).

4. **Future RC flag to delete `NSW_PARK_RIDE_FACILITIES`** if you go
   with 3.2: Firebase RC has no concept of "deprecated" — schedule
   the flag deletion ≥ 2 weeks after the BFF parking path is at 100%
   and you've shipped the matching `RealNswParkRideFacilityManager`
   removal.

---

## 7 · References

- Endpoint code: `server/src/main/kotlin/app/krail/bff/routes/ParkingRoutes.kt`
- Lookup table: `server/src/main/kotlin/app/krail/bff/data/ParkRideStopFacilityMap.kt`
- Tests: `server/src/test/kotlin/routes/ParkingBatchTest.kt` (17 tests)
- Master plan (parent doc): `KRAIL_INTEGRATION_MASTER_PLAN.md` 6 / 13
- KRAIL-API-PROTO docs (for the Phase B proto schema bump):
  <https://ksharma-xyz.github.io/KRAIL-API-PROTO/>

---

## 8 · Checklist for the KRAIL agent

When you're ready to wire this up:

- [ ] Decide between Option 3.1 (`?ids=`) and Option 3.2 (`?stopIds=`).
- [ ] Add the new request/response models (`ParkingBatchResponse` and/or
      `ParkingStopBatchResponse`) to
      `feature/park-ride/network/.../model/`.
- [ ] Add a `fetchAvailability*` method to `ParkRideService` that hits
      the new endpoint when `IS_BFF_LOCAL_OVERRIDE_SET` is on (or behind
      the future Phase B Firebase RC flag).
- [ ] Update the home-card view-model to use the batch method instead
      of N per-facility calls.
- [ ] (Option 3.2 only) Delete `RealNswParkRideFacilityManager`,
      `NswParkRideFacilityManager`, the `NSW_PARK_RIDE_FACILITIES` RC
      flag wiring on the app side. Schedule the Firebase RC flag
      deletion ≥ 2 weeks after BFF parking path is at 100%.
- [ ] Smoke-test on Android emulator + iOS Simulator. Watch the BFF
      log (`./scripts/dev.sh logs` from the BFF repo) — should see one
      `GET /v1/parking/availability?...` log line per home-screen mount,
      not three.
- [ ] (Optional) Add a `BatchedHomeRender` snapshot or instrumentation
      test asserting the new behavior — exactly one parking call per
      mount, not N.
