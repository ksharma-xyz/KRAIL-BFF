# KRAIL ↔ BFF migration guide

> Audience: KRAIL agent / app maintainer.
> Self-contained. Pairs with `API_REFERENCE.md` (specs) and
> `TESTING_GUIDE.md` (verification).
> Last updated 2026-05-10 against `proto-submodule` branch.

---

## 1 · TL;DR — five phases, in order

| Phase | Goal | Risk |
|---|---|---|
| **A** | Local-debug override on KRAIL — prove the wire reaches end-to-end | Very low; release unaffected |
| **B** | Production rollout behind Firebase RC + cohort 10 / 50 / 100 | Medium; rollout discipline matters |
| **C** | Adopt the proto trip endpoint (`/api/v1/trip/plan-proto`) | Medium; iOS Wire codegen needs care |
| **D** | Local stop search via the distributed stops dataset (.pb) | Low; weekly dataset already publishing |
| **E** | Delete the in-app NSW API key | Trivial once D is done |

Phase A on the KRAIL side is **already done** (validated 2026-05-10
against the local BFF, 24 requests / 0 failures). Phase C foundation
landed in the KRAIL repo too — `:io:bff-api` module compiles with
`KRAIL-API-PROTO v0.2.0`, behind a flag.

What's outstanding:
- Phase B is blocked on BFF deployment (DigitalOcean App Platform —
  see `STATUS.md` in repo root).
- Phase C flag flip awaits a coordinated step with the BFF team.
- D + E follow naturally.

Read your phase's section, ignore the rest until you get there.

---

## 2 · Phase A — local-debug override (DONE for primary path)

KRAIL has Phase A wired and validated (24 BFF requests / 0 failures
on AVD, 2026-05-10). This section is useful only if:
- Adding a new endpoint to the override path.
- A new developer sets up Phase A on a fresh machine.
- Reverting a Phase B regression (flip the flag, fall back to NSW direct).

### 2.1 What Phase A is

A build-time switch (`krail.bffBaseUrl` from `local.properties` →
BuildKonfig). When set, four KRAIL services route through the BFF
instead of NSW direct, on **debug builds only**. Release behavior is
unchanged.

### 2.2 Files involved

In the KRAIL repo:

| File | Purpose |
|---|---|
| `core/network/build.gradle.kts` | Add `KRAIL_BFF_BASE_URL` BuildKonfig field from `local.properties`. |
| `core/network/src/commonMain/.../BaseUrl.kt` | Export `KRAIL_BFF_BASE_URL` + `IS_BFF_LOCAL_OVERRIDE_SET`. |
| `local.properties` (gitignored) | `krail.bffBaseUrl=http://10.0.2.2:8080` (Android) or `http://localhost:8080` (iOS). |
| `feature/trip-planner/network/.../RealTripPlanningService.kt` | `trip()` branches to BFF when override is set; `stopFinder()` stays on NSW. |
| `feature/departures/network/.../RealDeparturesService.kt` | `departures()` branches to BFF (`/v1/stops/{id}/departures` shape). |
| `feature/park-ride/network/.../RealParkRideService.kt` | Both `fetchCarParkFacilities()` overloads branch to BFF. |
| `feature/track/network/.../RealGtfsRealtimeService.kt` | `buildUrl()` returns BFF base when override is set, for all three feed types. |
| `androidApp/src/debug/AndroidManifest.xml` | Cleartext exception for `10.0.2.2` (debug-only). |
| `androidApp/src/debug/res/xml/network_security_config.xml` | The actual cleartext config. |
| `iosApp/iosApp/Info.plist` | `NSAppTransportSecurity` exception for `localhost`. |

### 2.3 Code pattern for each migrated service

```kotlin
import xyz.ksharma.krail.core.network.IS_BFF_LOCAL_OVERRIDE_SET
import xyz.ksharma.krail.core.network.KRAIL_BFF_BASE_URL

private val baseUrl: String =
    if (IS_BFF_LOCAL_OVERRIDE_SET) KRAIL_BFF_BASE_URL else NSW_TRANSPORT_BASE_URL

// In trip() — same path, drop-in URL swap:
httpClient.get("$baseUrl/v1/tp/trip") { url { /* same params */ } }
```

For departures, the URL shape changes — see `API_REFERENCE.md` for
the new `?date=&time=` query params and `stopId` in the path.

For Park & Ride, prefer the new batch endpoint over per-facility
calls — section 5 below covers the choice.

### 2.4 Smoke after wiring

```bash
# From the KRAIL repo:
./scripts/fullQualityChecks.sh   # ask the user to run; it's the canonical Android build

# Then on AVD: search a trip; tap a saved trip's parking; open journey map.
# Watch the BFF log:
cd /Users/ksharma/code/apps/KRAIL-BFF && ./scripts/dev.sh logs
```

Expected log lines per screen:

| Screen | BFF log line |
|---|---|
| Trip search | `GET /v1/tp/trip` |
| Departures | `GET /v1/stops/{id}/departures` |
| Park & Ride list | `GET /v1/parking/facilities` (or `…/availability?stopIds=`) |
| Live tracking | `GET /v[1\|2]/gtfs/realtime/{feed}` + `/v2/gtfs/vehiclepos/{feed}` |

If a screen renders but no matching log line: override didn't activate
for that service — check the `if (IS_BFF_LOCAL_OVERRIDE_SET)` branch.

If the screen broke that used to work: the `else` branch broke. Revert.

---

## 3 · Phase B — production rollout

Blocked on BFF deployment to DigitalOcean. When that's done, this
section becomes actionable.

### 3.1 Prereqs the BFF team must have done first

- BFF reachable at the production hostname (`bff.krail.app` or
  whatever ends up deployed) over HTTPS, returning 200 on `/health`.
- Cloudflare in front, Transform Rule sets `CF-Origin-Token` on
  every request.
- DO firewall locked to Cloudflare's published IP ranges.
- BFF env vars set: `NSW_API_KEY`, `CF_ORIGIN_TOKEN`,
  `MIN_APP_VERSION` (start at 0.0.0; raise later),
  `STOPS_MANIFEST_URL` and `ROUTES_MANIFEST_URL`,
  `BFF_DEV_PASSTHROUGH=false`.

If any of those isn't done, you're not in Phase B yet — Phase A
remains your reality.

### 3.2 What changes on the KRAIL side

1. **Two BuildConfig values, not one.** Debug points at `localhost`;
   release at the production host.
   ```kotlin
   buildkonfig {
       defaultConfigs {
           buildConfigField(STRING, "KRAIL_BFF_BASE_URL", "")
       }
       defaultConfigs("debug") {
           buildConfigField(STRING, "KRAIL_BFF_BASE_URL",
               localPropertiesGetOrEmpty("krail.bffBaseUrl"))
       }
       defaultConfigs("release") {
           buildConfigField(STRING, "KRAIL_BFF_BASE_URL",
               "https://bff.krail.app")
       }
   }
   ```

2. **`X-Krail-Version` on every BFF call**, once in the shared Ktor
   client config:
   ```kotlin
   defaultRequest {
       header("X-Krail-Version", appInfoProvider.versionName)
   }
   ```

3. **`CF-Origin-Token` on every BFF call** (production only). Same
   pattern; token from BuildKonfig sourced from CI secrets, not
   committed.

4. **Firebase Remote Config flags**, defaults all `false`:
   - `bff_kill_switch` — panic button. When true, every endpoint
     forces back to NSW direct regardless of per-endpoint flags.
   - `bff_use_for_trip_results`
   - `bff_use_for_departures`
   - `bff_use_for_park_ride`
   - `bff_use_for_track`

   RC fetch interval ≤ 5 min so flag flips propagate fast.

5. **Per-endpoint branching:**
   ```kotlin
   suspend fun trip(...): TripResponse {
       val useBff = !flags.bffKillSwitch && flags.bffUseForTripResults
       return if (useBff) callBff(...) else callNsw(...)
   }
   ```

6. **Cohort rollout** per endpoint:
   - Day 1: 10% of users.
   - Day 4 (no regressions): 50%.
   - Day 7 (no regressions): 100%.
   - Watch crash-free %, ANR rate, error reports for the BFF cohort.
   - If anything regresses: per-endpoint flag → false. Worst case
     `bff_kill_switch = true`.

7. **Two weeks of grace at 100%** before deleting the NSW path. Keep
   the NSW code as the fallback, gated on the flag.

### 3.3 Done criteria

- All four services have `bff_use_for_<endpoint>` flag in code, off
  by default.
- `bff_kill_switch` is wired (kills all four with one flip).
- 10 / 50 / 100 cohort each clean for ≥ 3 days.
- 100% cohort runs for ≥ 14 days at parity or better than NSW direct.
- Crashlytics shows no BFF-attributable regression.

---

## 4 · Phase C — adopt the proto trip endpoint

`/api/v1/trip/plan-proto` returns binary protobuf, ~44% smaller on
the wire than JSON pass-through (gzipped). The wire-format contract
is `KRAIL-API-PROTO v0.2.0`, which already includes polyline data.

Foundation (already in KRAIL):
- `krail-api-proto` submodule pinned at `v0.2.0`.
- `:io:bff-api` KMP module with Wire 6.2.0 codegen, compiles on
  Android + iOS.
- `RealTripPlanningService.trip()` has a flagged-off proto branch
  with a `JourneyListMapper` stub.

To turn it on:

1. **Implement the mapper.** `JourneyList → TripResultsDomain`. Per
   the contract convention (see `API_REFERENCE.md` section 11), each
   `// contract: required` proto field maps to a non-null domain
   field; missing → typed parse error → kill-switch fallback. Each
   `// contract: optional` field maps to a nullable domain field.

2. **Wire the proto path:**
   ```kotlin
   suspend fun trip(...): TripResultsDomain {
       val useProto = useBff && flags.bffUseProtoForTripResults
       return if (useProto) {
           val bytes: ByteArray = httpClient.get("$BFF/api/v1/trip/plan-proto") { /* params */ }.body()
           JourneyList.ADAPTER.decode(bytes).toDomain()
       } else if (useBff) {
           // JSON path from Phase B
           val nsw: TripResponse = httpClient.get("$BFF/v1/tp/trip") { /* params */ }.body()
           nsw.toDomain()
       } else {
           // NSW direct, unchanged
           ...
       }
   }
   ```

3. **Cohort the proto migration separately** with
   `bff_use_proto_for_trip_results`. Same 10 / 50 / 100 ladder. Watch
   for parsing errors in the new mapper.

4. **Test approach:** `TESTING_GUIDE.md` sections 2.4 + 2.5 cover the
   patterns. Snapshot a real proto response, decode in CI, assert
   coords + journey count.

### What's in `KRAIL-API-PROTO v0.2.0`

- `JourneyList` with `journeys[]` of `JourneyCardInfo`.
- `TransportLeg.coords[]` for transit-leg polylines.
- `WalkingLeg.coords[]` and `WalkInterchange.coords[]` for walk
  polylines.
- `Stop.coord` for per-stop point-on-map.
- `Coord { lat, lon }` message.

What's NOT in v0.2.0 (deferred to a future minor bump):
- `Fare` message and `journeys[].fare`. NSW returns `fare.tickets`
  but no current screen needs it.
- `interchanges` count summary on `JourneyCardInfo`. Derivable from
  `legs.size`.
- `Stop.parent_station`. The "group platforms under stations" use
  case is real but not yet active in the app.

If you hit a missing field on the proto path that JSON has, raise it
with the BFF team — schema bump + mapper update + minor version cut.

---

## 5 · Park & Ride — `?ids=` vs `?stopIds=` migration

Only relevant section because the parking endpoint has a
**stop-resolution variant** unique to this domain. Other migrations
are straightforward URL swaps.

### 5.1 Background

KRAIL home shows one parking card per saved trip. Each stop can have
1–3 facilities (e.g. Tallawong stopId 2155384 has facilities 26 / 27
/ 28 = P1 / P2 / P3). Pre-batching, that meant N HTTP calls per home
mount.

The BFF now serves a batch endpoint with two modes:

- `GET /v1/parking/availability?ids=486,487,488` — facility-ID batch.
  KRAIL keeps its existing facility-ID list (currently in Firebase RC
  `NSW_PARK_RIDE_FACILITIES`) and just batches the calls.
- `GET /v1/parking/availability?stopIds=275010,2155384` — stop-ID
  resolution. The BFF resolves stop → facility(s) server-side.
  KRAIL never sees facility IDs.

### 5.2 Recommendation

**Use `?stopIds=`.** It deletes more KRAIL surface area:

- `RealNswParkRideFacilityManager` → delete.
- `NswParkRideFacility` model → delete.
- `NSW_PARK_RIDE_FACILITIES` Firebase RC flag → delete (after 2-week grace).
- Per-card facility resolution code → delete.

The lookup table (47 entries, hand-curated, ports the RC payload)
lives on the BFF in `server/src/main/kotlin/app/krail/bff/data/ParkRideStopFacilityMap.kt`.
Updates require a BFF redeploy, not an app release.

### 5.3 Code pattern

```kotlin
// :feature:park-ride/network/.../RealParkRideService.kt
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

Models:

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

@Serializable
data class BatchError(val code: String, val message: String)
```

The home view-model passes `savedTrips.map { it.stopId }` directly
and gets back availability per stop. No facility-ID translation
client-side.

### 5.4 Sequencing

Same flag as the rest of Phase B: `bff_use_for_park_ride`. No need
for a separate parking flag — the batch endpoint is a strict
improvement.

After 2 weeks at 100%: delete `RealNswParkRideFacilityManager`,
delete the RC flag, delete the model. That's the surface-area win.

---

## 6 · Phase D — local stop search via stops dataset

**Why:** the BFF doesn't have a `stop_finder` endpoint. Stop search is
high-frequency (every keystroke) and NSW's `stop_finder` is slow +
rate-limited. The plan: weekly stops dataset distributed via GitHub
Releases, KRAIL caches locally, all stop search runs offline.

### 6.1 Steps

1. **Pull the manifest at startup:**
   ```kotlin
   val manifest: StopsManifest = httpClient
       .get("$bffBaseUrl/v1/data/stops/manifest")  // 302 → GitHub Releases
       .body()
   // { version: "20260509", file_url: "...", sha256: "...", size_bytes: ... }
   ```

2. **Compare against cached version.** If equal, do nothing. If
   different (or no cache), download `manifest.file_url`, verify SHA-256.

3. **Decode and cache in-memory:**
   ```kotlin
   val bytes = downloadedFile.readBytes()
   require(bytes.sha256() == manifest.sha256)
   val dataset = StopsDataset.ADAPTER.decode(bytes)
   // dataset.stops is List<DatasetStop>, ~10 000 entries
   ```

4. **Replace `RealTripPlanningService.stopFinder()`** with local
   search:
   ```kotlin
   suspend fun stopFinder(query: String, type: StopType): List<DatasetStop> =
       withContext(ioDispatcher) {
           localStopsRepository.dataset
               .stops
               .filter { matchesQuery(it, query, type) }
               .take(20)
       }
   ```

5. **Delete the NSW stop_finder client code.** First place you
   actually remove NSW-direct logic. Dataset rebuilds weekly; stops
   list stays current.

### 6.2 Done criteria

- App starts with the dataset cached.
- Stop search latency < 50ms p99 (local CPU instead of network).
- Top results match what NSW returned for the same query (use
  `Compare with NSW` in the dashboard for spot checks).
- App works offline for stop search.

---

## 7 · Phase E — delete the NSW API key from the app

Trivial step, large symbolic value. After A–D have run at 100% for
≥ 2 weeks with no regressions:

1. Confirm zero NSW direct calls remain in the app:
   ```bash
   grep -rn "NSW_TRANSPORT_BASE_URL\|api.transport.nsw.gov.au" \
     core/ feature/ composeApp/ --include="*.kt"
   # Expect: no matches.
   ```

2. Confirm no readers of the in-app NSW key:
   ```bash
   grep -rn "NSW_API_KEY\|nswApiKey" core/ feature/ --include="*.kt"
   # Expect: no matches except in test setup, if any.
   ```

3. Remove the BuildKonfig field, the `local.properties` entry, the
   CI secret. Commit.

4. **In the NSW Open Data console**, revoke the in-app key. The
   BFF's server-side key is unaffected.

After this, NSW credentials live in exactly one place: the BFF's
`NSW_API_KEY` env var on DigitalOcean. That's the security goal.

---

## 8 · Common pitfalls (across phases)

- **Forgetting `submodules: true` on `actions/checkout`.** CI
  doesn't fetch `krail-api-proto/`, Wire codegen fails. Set it on
  every workflow that compiles.
- **Forgetting `X-Krail-Version` in tests.** Unit tests pass, the
  request 400s in production once the floor is raised. Add the
  header in the shared HTTP client config, not per-call.
- **Hard-coding the BFF URL.** Always go through BuildKonfig.
- **Logging response bodies.** Stop IDs + times + saved-trip
  combos can deanonymise users in aggregate. Log status codes,
  sizes, correlation IDs — not bodies.
- **Calling both endpoints in parallel for production users.**
  Compare-mode is a dev tool only.
- **Treating `bff_kill_switch` as a regular flag.** Panic button.
  If you flip it more than once a quarter, something's wrong.
- **Deleting the NSW path too early.** Wait the full 2-week 100%
  grace. Premature deletion has caused real incidents.
- **Skipping cohort steps.** 0 → 100 in one push has caused real
  outages. Use 10 / 50 / 100 even when slow.
- **iOS Wire build trouble.** If Wire's KMP-iOS misbehaves, the
  fallback is `kotlinx-serialization-protobuf` with hand-mapped
  messages. Don't switch unless Wire actually breaks; the migration
  is one-way painful.
- **Cleartext config leaking to release builds.** Place
  `network_security_config.xml` under `src/debug/`, not `src/main/`.
  Same for the iOS Info.plist exception — debug-only if your iOS
  scheme supports it.

---

## 9 · Phase A closing checklist (for the record)

For a new developer setting up Phase A, all 17 items must tick:

- [ ] BFF `/health` returns 200 from your dev machine.
- [ ] BuildKonfig field `KRAIL_BFF_BASE_URL` exists.
- [ ] `local.properties` has `krail.bffBaseUrl=http://10.0.2.2:8080`.
- [ ] `BaseUrl.kt` exposes `KRAIL_BFF_BASE_URL` and
      `IS_BFF_LOCAL_OVERRIDE_SET`.
- [ ] `RealTripPlanningService.trip()` routes via BFF when override
      is set; `stopFinder()` untouched.
- [ ] `RealDeparturesService.departures()` routes via BFF.
- [ ] `RealParkRideService` (both overloads) route via BFF.
- [ ] `RealGtfsRealtimeService.buildUrl()` returns BFF base when
      override is set, for all three feed types.
- [ ] `androidApp/src/debug/res/xml/network_security_config.xml`
      allows cleartext for `10.0.2.2` / `localhost`.
- [ ] `androidApp/src/debug/AndroidManifest.xml` references it.
- [ ] `iosApp/iosApp/Info.plist` has `NSAppTransportSecurity`
      exception.
- [ ] `./scripts/fullQualityChecks.sh` passes.
- [ ] iOS Simulator builds in Xcode.
- [ ] Trip search renders journeys (BFF log: `GET /v1/tp/trip`).
- [ ] Departures list renders (BFF log:
      `GET /v1/stops/{id}/departures`).
- [ ] Park & Ride renders (BFF log:
      `GET /v1/parking/availability?stopIds=…` or
      `…/availability` per facility).
- [ ] Live tracking renders with vehicles (BFF log:
      `GET /v[1\|2]/gtfs/realtime/...` and
      `/v2/gtfs/vehiclepos/...`).
- [ ] Stop search still works (NSW direct, untouched).

---

## 10 · BFF-side context (for cross-reference)

Things that aren't your job but worth knowing:

- BFF runs on JDK 17 / Ktor 3.4 / Wire 6.2. Singleton-scoped
  HttpClient, Koin DI, structured JSON logging via Logback +
  logstash-encoder.
- Daily NSW upstream budget (default 10 000 calls/day; returns 503
  when exhausted). Sydney midnight reset.
- Per-IP rate limit: 5 RPS / 10 burst, configurable.
- Origin lockdown via `CF-Origin-Token` (production only, off in dev).
- Version gate via `X-Krail-Version` (off in dev with floor `0.0.0`).
- Metrics exposed via Dropwizard, scraped by DO App Platform.
- Deployment target: DigitalOcean App Platform, Sydney region,
  basic-xxs (~A$8/mo flat). Cloudflare in front (free plan).

For any of those, the canonical read is `STATUS.md` + the docs
under `docs/reference/`.
