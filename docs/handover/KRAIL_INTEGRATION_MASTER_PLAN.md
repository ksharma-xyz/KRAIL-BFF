# KRAIL ↔ BFF integration — master plan

> **Audience:** an LLM (or human) working in the **KRAIL app repo**, given
> the task of moving the KRAIL Sydney transit app off direct NSW Open Data
> calls and onto the KRAIL-BFF.
>
> **Status as of 2026-05-09:** the BFF is feature-complete for v1, the
> shared proto repo is published at `v0.1.0`, the BFF runs locally on
> :8080. **You can start integrating today.**
>
> **This doc is the canonical entry point.** It sequences the work into
> five phases (A → E) and points you at the deeper docs for each. Read
> 0 then 1, then walk the phases in order.

---

## 0 · Documents in this set

You'll see references to four other docs. Use them when this one points
you at a specific spec:

| Doc | Contents | When to read |
|---|---|---|
| `KRAIL_APP_INTEGRATION_HANDOVER.md` | Step-by-step playbook for **Phase A** (debug-only override). | Doing Phase A. |
| `KRAIL_API_REFERENCE.md` | Field-by-field BFF endpoint specs with real captured response bodies. | Whenever you need request params or response schema. |
| `PROTO_REPO_MIGRATION.md` | Snapshot of what was done in `KRAIL-API-PROTO` + the BFF-side submodule swap. | Doing Phase C (proto adoption). |
| `BFF_ADOPTION_GUIDE.md` (in `docs/reference/`) | Older long-form playbook with feature flags, cohort rollout, mapper consolidation. | Doing Phase B (production rollout). |

External:

- KRAIL-API-PROTO repo: <https://github.com/ksharma-xyz/KRAIL-API-PROTO>
- KRAIL-API-PROTO docs site: <https://ksharma-xyz.github.io/KRAIL-API-PROTO/>
- BFF dashboard (when running locally): <http://localhost:8000/api-tester.html>

---

## 1 · State of play — what's built, what's not

### What's built (BFF side)

Endpoints — all GET, all return JSON unless marked binary, none require auth from the client:

| BFF path | NSW upstream | Used by KRAIL screen | Status |
|---|---|---|---|
| `/v1/tp/trip` | `/v1/tp/trip` (same shape) | Trip results | ✅ |
| `/api/v1/trip/plan` | (BFF-shaped JSON) | Future — screen-shaped | ✅ |
| `/api/v1/trip/plan-proto` (binary) | (BFF protobuf) | Future — Phase C | ✅ |
| `/v1/stops/{stopId}/departures` | `/v1/tp/departure_mon` | Departures (Saved Trips) | ✅ |
| `/v1/parking/facilities` | `/v1/carpark` (no facility=) | Park & Ride list | ✅ |
| `/v1/parking/facilities/{id}/availability` | `/v1/carpark?facility={id}` | Park & Ride detail | ✅ |
| `/v1/gtfs/realtime/{feed}` (binary) | `/v1/gtfs/realtime/{feed}` (same) | Live tracking (v1 feeds) | ✅ |
| `/v2/gtfs/realtime/{feed}` (binary) | `/v2/gtfs/realtime/{feed}` (same) | Live tracking (sydneytrains, metro) | ✅ |
| `/v2/gtfs/vehiclepos/{feed}` (binary) | `/v2/gtfs/vehiclepos/{feed}` (same) | Map markers | ✅ |
| `/v1/data/stops/manifest` (302) | (GitHub Releases asset) | Stops dataset distribution | ✅ |
| `/v1/data/routes/manifest` (302) | (GitHub Releases asset) | Routes dataset distribution | ✅ |
| `/health`, `/ready` | (operational probes) | Smoke tests | ✅ |

Server-side guarantees:

- Per-IP rate limit (token bucket: 5 RPS / 10 burst, configurable).
- Daily NSW upstream budget (default 10 000 calls/day, soft cap; returns 503 once exhausted).
- `X-Krail-Version` floor enforcement (configurable; disabled at `0.0.0`).
- `CF-Origin-Token` shared-secret gate (production only; disabled when env var is unset).
- gzip Compression installed.
- Correlation ID generation + log propagation.
- Structured JSON logging with mobile context (deviceModel / osName / appVersion etc.) in MDC.
- Metrics exposed via Dropwizard.

### What's NOT built yet

- **Screen-shaped JSON / proto endpoints** beyond trip planner. `DepartureBoardResponse`, `TripResultsResponse`, `ParkRideResponse` etc. are designed in `API_SCHEMA_DESIGN.md` but not implemented. The BFF currently passes through NSW JSON unchanged for those endpoints. **Phase A and B** can both ship without screen-shaped endpoints.
- **Stop-finder replacement.** The BFF has no `stop_finder` endpoint. Stop search stays on NSW direct until **Phase D** (local search against the stops dataset).
- **Production deploy.** The .do/app.yaml and DEPLOYMENT.md exist but `bff.krail.app` isn't live. Phase A is local-only; Phase B requires the deploy.

### Where the work happens — KRAIL repo files

| Concern | KRAIL file |
|---|---|
| Base URL constants | `core/network/src/commonMain/kotlin/xyz/ksharma/krail/core/network/BaseUrl.kt` |
| HTTP client config | `core/network/src/commonMain/kotlin/xyz/ksharma/krail/core/network/HttpClient.kt` (and Android/iOS actuals) |
| BuildKonfig wiring | `core/network/build.gradle.kts` |
| Trip planner service | `feature/trip-planner/network/src/commonMain/kotlin/xyz/ksharma/krail/trip/planner/network/api/service/RealTripPlanningService.kt` |
| Departures service | `feature/departures/network/src/commonMain/kotlin/xyz/ksharma/krail/departures/network/api/service/RealDeparturesService.kt` |
| Park & Ride service | `feature/park-ride/network/src/commonMain/kotlin/xyz/ksharma/krail/park/ride/network/service/RealParkRideService.kt` |
| GTFS-RT service | `feature/track/network/src/commonMain/kotlin/xyz/ksharma/krail/feature/track/network/RealGtfsRealtimeService.kt` |
| Android manifest (debug) | `androidApp/src/debug/AndroidManifest.xml` |
| Android cleartext config | `androidApp/src/debug/res/xml/network_security_config.xml` |
| iOS Info.plist | `iosApp/iosApp/Info.plist` |
| Local config | `local.properties` (gitignored) |

---

## 2 · Architecture in one paragraph

KRAIL app → HTTPS → Cloudflare (production) → DigitalOcean App Platform (BFF host) → KRAIL-BFF (Kotlin/Ktor server, holds NSW API key) → HTTPS → NSW Open Data Transport API. The BFF transforms responses where it can (trip planner emits a smaller `JourneyList` proto + screen-shaped JSON), and pass-through proxies what it can't reshape yet (departures, park & ride, GTFS-RT). All client→BFF traffic is keyless from the app's perspective; the BFF rate-limits per IP and budgets daily NSW calls.

The proto contract that both sides depend on is published as a separate
public repo, [KRAIL-API-PROTO](https://github.com/ksharma-xyz/KRAIL-API-PROTO),
versioned via SemVer git tags. Each consumer pins it as a git submodule
and runs Wire codegen locally (BFF on JVM, KRAIL on KMP-common).

---

## 3 · The five phases

You don't have to do them all in one go. Each phase is a coherent
chunk of work that ships independently. Order matters — later phases
build on earlier ones.

| Phase | Goal | Risk | Deliverable |
|---|---|---|---|
| **A** | Prove BFF wire reaches the app on a local debug build | Very low — release unaffected | URL swap on 4 services; cleartext exception in `:androidApp/src/debug/`; smoke test passes |
| **B** | Roll out to production users behind a Firebase RC flag | Medium — rollout discipline matters | Deployed BFF + RC flags + cohort 10/50/100 + 2 weeks at 100% |
| **C** | Adopt the proto contract (use `/api/v1/trip/plan-proto`) | Medium — codegen on KMP-iOS can be fiddly | `:io:bff-api` module with Wire codegen + `JourneyList` mapper |
| **D** | Move stop search local (download dataset, search in app) | Low — datasets already published weekly | Manifest fetch + `.pb` cache + local search; delete NSW `stop_finder` calls |
| **E** | Delete the in-app NSW API key | Trivial once D is done | NSW Open Data app-key revoked; only BFF holds NSW credentials |

Realistic timeline (one person, evenings):

- A: one or two evenings
- B: two weeks of cohort dwell time + a handful of evenings of plumbing
- C: a weekend (most time will be the iOS Wire build)
- D: one or two evenings (manifest / fetch / cache logic)
- E: 30 minutes after D ships

---

## 4 · Phase A — Local debug proof-of-life

**Detailed playbook:** see `KRAIL_APP_INTEGRATION_HANDOVER.md`. This is
the executable doc — its 6 walks step-by-step through the four URL
swaps.

### One-paragraph summary

Add a build-time switch (`krail.bffBaseUrl` from `local.properties` →
BuildKonfig). When set, four services route through the BFF instead of
NSW direct: `RealTripPlanningService.trip()`, `RealDeparturesService.departures()`,
`RealParkRideService.fetchCarParkFacilities()` (both overloads), and
`RealGtfsRealtimeService.buildUrl()`. `RealTripPlanningService.stopFinder()`
stays on NSW (BFF has no stop_finder; Phase D handles it). Add cleartext
exceptions for `10.0.2.2` / `localhost` under `:androidApp/src/debug/`
and `iosApp/iosApp/Info.plist`. Build, run, exercise each screen,
confirm the BFF log shows the matching `GET` lines, done.

### What you commit at the end of Phase A

- New `local.properties` line: `krail.bffBaseUrl=http://10.0.2.2:8080`
  (gitignored; **don't commit** local.properties).
- New BuildKonfig field `KRAIL_BFF_BASE_URL` in `core/network/build.gradle.kts`.
- `BaseUrl.kt` exports `KRAIL_BFF_BASE_URL` and `IS_BFF_LOCAL_OVERRIDE_SET`.
- Branched `if (IS_BFF_LOCAL_OVERRIDE_SET)` blocks in the four service files.
- `androidApp/src/debug/res/xml/network_security_config.xml` (new).
- `androidApp/src/debug/AndroidManifest.xml` (new or extended).
- `iosApp/iosApp/Info.plist` `NSAppTransportSecurity` exception.

### Phase A success criteria

- `assembleDebug` succeeds (ask the user to run `./scripts/fullQualityChecks.sh`).
- iOS Simulator build succeeds (ask the user, do not run `xcodebuild` headless).
- All four screens render real data with the override on.
- BFF log (`./scripts/dev.sh logs` from the BFF repo) shows the matching `GET` lines:
    - `GET /v1/tp/trip` for trip search
    - `GET /v1/stops/{stopId}/departures` for departures
    - `GET /v1/parking/facilities` and `…/availability` for Park & Ride
    - `GET /v[1|2]/gtfs/realtime/{feed}` and `/v2/gtfs/vehiclepos/{feed}` for live tracking
- Stop search still works (NSW direct, untouched).
- Release build behavior unchanged when `krail.bffBaseUrl` is empty.

---

## 5 · Phase B — Production rollout

**Detailed playbook:** `docs/reference/BFF_ADOPTION_GUIDE.md` covers the
flag-gating + cohort math. Read the "Migration playbook (per endpoint)"
section.

### Prerequisites — done by the BFF maintainer, not you

Before you can do Phase B you need:

- BFF deployed to DigitalOcean App Platform (`docs/reference/DEPLOYMENT.md`).
- Cloudflare in front, with `CF-Origin-Token` set as a Transform Rule.
- DO firewall locked to Cloudflare's published IP ranges.
- `bff.krail.app` (or whatever the deploy URL is) reachable at HTTPS, returning 200 on `/health`.
- BFF env vars set: `NSW_API_KEY`, `CF_ORIGIN_TOKEN`, `MIN_APP_VERSION` (start at 0.0.0; raise later), `STOPS_MANIFEST_URL` and `ROUTES_MANIFEST_URL` (point at the dataset releases), `BFF_DEV_PASSTHROUGH=false` (must stay false in prod).

If any of those isn't done, **you're not in Phase B yet — Phase A
remains your reality.**

### What changes in the app for Phase B

1. **Two BuildConfig fields, not one.** Debug points at `localhost` /
   `10.0.2.2`; release points at `https://bff.krail.app`. Do this with
   a build-variant default:
   ```kotlin
   // core/network/build.gradle.kts (sketch)
   buildkonfig {
       defaultConfigs {
           buildConfigField(STRING, "KRAIL_BFF_BASE_URL", "")  // disabled
       }
       defaultConfigs("debug") {
           buildConfigField(STRING, "KRAIL_BFF_BASE_URL",
               localPropertiesGetOrEmpty("krail.bffBaseUrl"))
       }
       defaultConfigs("release") {
           buildConfigField(STRING, "KRAIL_BFF_BASE_URL",
               "https://bff.krail.app")  // production
       }
   }
   ```

2. **Send `X-Krail-Version` on every BFF call.** Add it once in the
   shared Ktor client config (`core/network/`), not per-call:
   ```kotlin
   // core/network/.../HttpClient.kt
   defaultRequest {
       header("X-Krail-Version", appInfoProvider.versionName)
   }
   ```

3. **Send `CF-Origin-Token` on every BFF call** (production only).
   Same pattern. The token comes from BuildKonfig, sourced from
   `local.properties` for debug or CI secrets for release. Don't
   commit the token.

4. **Firebase Remote Config flags** (default all `false`):
   - `bff_kill_switch` — panic button. When true, every endpoint
     forces back to NSW direct regardless of per-endpoint flags.
   - `bff_use_for_trip_results`
   - `bff_use_for_departures`
   - `bff_use_for_park_ride`
   - `bff_use_for_track`

   RC fetch interval ≤ 5 min so flag flips propagate fast.

5. **Per-endpoint branching in services.** Replace the simple
   `if (IS_BFF_LOCAL_OVERRIDE_SET)` from Phase A with:
   ```kotlin
   suspend fun trip(...): TripResponse {
       val useBff = !flags.bffKillSwitch && flags.bffUseForTripResults
       return if (useBff) callBff(...) else callNsw(...)
   }
   ```

6. **Cohort rollout.** Per `BFF_ADOPTION_GUIDE.md` "Cohort rollout":
   - Day 1: 10% of users.
   - Day 4 (no regressions): 50%.
   - Day 7 (no regressions): 100%.
   - Watch crash-free %, ANR rate, error reports for the BFF-controlled flow.
   - If anything regresses: flip per-endpoint flag → false. Or worst case, `bff_kill_switch=true`.

7. **Two weeks of grace at 100%** before deleting the NSW path. Keep
   the NSW code as the fallback, gated on the flag.

### Phase B success criteria

- All four services have `bff_use_for_<endpoint>` flag in code, off by default.
- `bff_kill_switch` is wired (kills all four with one flip).
- 10% cohort runs for 3+ days with no regression.
- 50% cohort runs for 3+ days with no regression.
- 100% cohort runs for ≥ 14 days at parity or better than NSW direct.
- Crash-free % and ANR rate from Firebase Crashlytics show no regression
  attributable to the BFF path.

### What stays out of Phase B

- Proto-shaped requests. Phase B uses the same JSON pass-through endpoints
  as Phase A, just behind flags. Proto adoption is Phase C.
- Stop search migration. Stays NSW direct until Phase D.

---

## 6 · Phase C — Adopt the proto contract

**Detailed playbook:** `PROTO_REPO_MIGRATION.md` (BFF side, already
in flight). The KRAIL side mirrors most of it.

### Why proto

`/api/v1/trip/plan-proto` is ~83% smaller on the wire than NSW JSON.
For a transit app — most users on mobile data — that's the biggest
single ergonomic win available. The mapper output is **screen-shaped**
(`JourneyCardInfo` → trip card UI 1-to-1) so KRAIL gets to delete its
post-fetch reshape code too.

### Steps

1. **Add `KRAIL-API-PROTO` as a submodule** at the KRAIL repo root,
   pinned to `v0.1.0`:
   ```bash
   git submodule add https://github.com/ksharma-xyz/KRAIL-API-PROTO.git krail-api-proto
   git -C krail-api-proto checkout v0.1.0
   git add .gitmodules krail-api-proto
   git commit -m "chore: add krail-api-proto submodule pinned to v0.1.0"
   ```
   CI workflows must pass `submodules: true` to `actions/checkout`.

2. **Stand up `:io:bff-api` module.** New KMP module with the Wire
   plugin (KRAIL already pulls Wire 6.2.0 per `gradle/libs.versions.toml`):
   ```kotlin
   // io/bff-api/build.gradle.kts
   plugins {
       alias(libs.plugins.kotlinMultiplatform)
       alias(libs.plugins.wire)
   }
   kotlin {
       androidTarget()
       iosX64()
       iosArm64()
       iosSimulatorArm64()
       sourceSets {
           commonMain.dependencies {
               implementation(libs.wire.runtime)
           }
       }
   }
   wire {
       kotlin {
           targets { commonMain }
           sourcePath { srcDir("$rootDir/krail-api-proto/proto") }
       }
   }
   ```

3. **Validate iOS codegen builds.** Wire's KMP-iOS targets occasionally
   misbehave on older Wire / Kotlin combos. If it doesn't build cleanly,
   the fallback is `kotlinx-serialization-protobuf` with hand-mapped
   messages — same wire format, no codegen plugin. Don't go there
   unless Wire actually breaks.

4. **Add the proto-bump workflow.** Mirror the BFF's `proto-bump.yml`:
   ```yaml
   # .github/workflows/proto-bump.yml in KRAIL
   # Daily check + manual dispatch. Opens a PR if KRAIL-API-PROTO has
   # a newer tag than the currently-pinned submodule SHA.
   # Never auto-merges. Existing fullQualityChecks.sh gates the PR.
   ```
   Copy the structure from `KRAIL-BFF/.github/workflows/proto-bump.yml`.

5. **Migrate `RealTripPlanningService.trip()` to the proto endpoint.**
   ```kotlin
   suspend fun trip(...): TripResultsDomain {
       val baseUrl = if (useBff) BFF_BASE_URL else NSW_BASE_URL
       val path = if (useBff) "/api/v1/trip/plan-proto" else "/v1/tp/trip"

       return if (useBff) {
           // Proto path — ~83% smaller wire, screen-shaped result.
           val bytes: ByteArray = httpClient.get("$baseUrl$path") { /* params */ }.body()
           val proto = JourneyList.ADAPTER.decode(bytes)
           proto.toDomain()  // <-- new mapper, JourneyList → TripResultsDomain
       } else {
           // Existing NSW JSON path stays as fallback.
           val nsw: TripResponse = httpClient.get("$baseUrl$path") { /* params */ }.body()
           nsw.toDomain()  // <-- existing mapper
       }
   }
   ```

6. **Add the `JourneyList` → domain mapper** at the network-layer
   boundary. Per `KRAIL-API-PROTO`'s contract convention (see
   <https://ksharma-xyz.github.io/KRAIL-API-PROTO/contract>):
   - `// contract: required` proto fields → non-null in your domain
     model. If null at runtime, fail the parse (typed error, log,
     fall back to kill-switch path); never crash UI.
   - `// contract: optional` proto fields → nullable in your domain
     model; UI handles absence.

7. **Cohort the proto migration.** Add a separate flag
   `bff_use_proto_for_trip_results` so you can flip JSON↔proto
   independently of the BFF on/off flag. 10/50/100 again. Watch for
   parsing errors in the new mapper.

### What's out of Phase C

- Other endpoints don't have proto shapes yet. Departures / Park & Ride
  / GTFS-RT continue with the JSON pass-through. When the BFF adds
  screen-shaped messages for those, they'll land in
  `krail-api-proto/proto/api/` as a minor version bump and the
  proto-bump workflow opens a PR automatically.

---

## 7 · Phase D — Local stop search

**Why:** the BFF doesn't have a `stop_finder` endpoint, by design. Stop
search is high-frequency (every keystroke) and NSW's `stop_finder` is
slow + rate-limited. The plan: distribute a versioned `StopsDataset`
proto via GitHub Releases; the app downloads it on cold start (and
every 24h), caches it locally, runs all stop search against the cached
file. **No round-trip to a server for stop search.**

### Steps

1. **Pull the manifest at startup:**
   ```kotlin
   val manifest: StopsManifest = httpClient
       .get("$bffBaseUrl/v1/data/stops/manifest")  // 302 → GitHub Releases asset
       .body()
   // manifest = { version: "20260509", file_url: "...", sha256: "...", size_bytes: 4280193 }
   ```

2. **Compare against cached version.** If equal, do nothing. If
   different (or no cache), download `manifest.file_url` to local
   storage and verify SHA-256.

3. **Decode and cache in-memory:**
   ```kotlin
   val bytes = downloadedFile.readBytes()
   require(bytes.sha256() == manifest.sha256) { "stops dataset SHA mismatch" }
   val dataset = StopsDataset.ADAPTER.decode(bytes)
   // dataset.stops is List<DatasetStop>, ~10 000 entries
   ```

4. **Replace `RealTripPlanningService.stopFinder()`** with local search:
   ```kotlin
   suspend fun stopFinder(query: String, type: StopType): List<DatasetStop> =
       withContext(ioDispatcher) {
           localStopsRepository.dataset
               .stops
               .filter { matchesQuery(it, query, type) }
               .take(20)
       }
   ```

   `matchesQuery` is whatever fuzzy-match logic the existing app uses;
   the dataset has `name`, `disassembledName` (via stop_id namespacing),
   and `modes` so type filtering works.

5. **Delete the NSW stop_finder client code.** This is the first place
   you actually delete NSW-direct logic. The dataset rebuilds weekly
   (datasets.yml runs on the BFF repo Sundays 16:00 UTC), so the
   stops list stays current.

### Phase D success criteria

- App starts up cleanly with the dataset cached.
- Stop search latency < 50ms p99 (local CPU instead of network).
- Top results match what NSW returned for the same query (use the
  Compare mode in the BFF dashboard if you want to verify per-query).
- App works offline for stop search (cache hit).
- The same approach applies later for `RoutesDataset` (route number
  search) — it's already built on the BFF side, just unused on the app
  side. Add it as Phase D.5 when convenient.

---

## 8 · Phase E — Delete the NSW API key from the app

**Trivial step, large symbolic value.** Once Phases A–D are complete and
have run at 100% for ≥ 2 weeks with no regressions, **revoke the
in-app NSW Open Data key**. The BFF-only key continues to work.

### Steps

1. Confirm zero NSW direct calls remain in the app code:
   ```bash
   grep -rn "NSW_TRANSPORT_BASE_URL\|api.transport.nsw.gov.au" \
     core/ feature/ composeApp/ --include="*.kt"
   # Expected: no matches.
   ```
2. Confirm the `NSW_API_KEY` BuildKonfig field has no readers:
   ```bash
   grep -rn "NSW_API_KEY\|nswApiKey" core/ feature/ --include="*.kt"
   # Expected: no matches except in test setup, if any.
   ```
3. Remove the BuildKonfig field, the `local.properties` entry, the
   CI secret. Commit.
4. **In the NSW Open Data console**, revoke the in-app key. The BFF's
   server-side key is unaffected.

After this, NSW credentials live in exactly one place: the BFF's
`NSW_API_KEY` env var on DigitalOcean. That's the security goal.

---

## 9 · Endpoint reference (quick form)

For full request/response specs use **`KRAIL_API_REFERENCE.md`** —
that doc has 700+ lines of field-by-field detail with real captured
response bodies. Quick form here:

### Trip planner — `GET /v1/tp/trip`

Same path + same query params as NSW. Drop-in URL swap. Response is
NSW JSON verbatim; existing `TripResponse` parser works unchanged.

### Departures — `GET /v1/stops/{stopId}/departures?date=&time=`

Path-reshaped. `stopId` in path (accepts `200060` or `NSW:200060`).
`date` (YYYYMMDD) and `time` (HHmm) optional. Response is NSW
`departure_mon` JSON shape; existing `DepartureMonitorResponse` parser
works unchanged.

### Park & Ride — `GET /v1/parking/facilities` / `…/{id}/availability`

Pass-through of NSW `/v1/carpark` (with/without `facility=`). Response
shape identical between NSW and BFF.

### GTFS-Realtime — `GET /v[1|2]/gtfs/realtime/{feed}` / `/v2/gtfs/vehiclepos/{feed}`

Same paths as NSW. Returns NSW protobuf bytes verbatim. The KRAIL app's
existing `FeedMessage.ADAPTER.decode(bytes)` parses identically. The HEAD
request optimisation (skip parse if `Last-Modified` unchanged) works
end-to-end against the BFF.

### Stops dataset — `GET /v1/data/stops/manifest` (302)

Redirects to a JSON manifest on GitHub Releases. Manifest body:
```json
{
  "version": "20260509",
  "generated_at": "2026-05-09T03:14:00Z",
  "file_url": "https://github.com/.../stops-2026-05-09.pb",
  "sha256": "abc123...",
  "size_bytes": 4280193
}
```
The `.pb` is a `StopsDataset` protobuf — schema in
`krail-api-proto/proto/data/stops_dataset.proto`. Used in Phase D.

### Trip planner proto — `GET /api/v1/trip/plan-proto` (binary)

Same query params as `/v1/tp/trip`. Returns `JourneyList` protobuf
bytes. Schema in `krail-api-proto/proto/api/trip.proto`. Used in
Phase C.

---

## 10 · Headers and conventions

### Required headers (eventually)

- `X-Krail-Version: <semver>` — your app version. The BFF rejects
  requests below `MIN_APP_VERSION` with 403 `version_too_old`. Local
  default floor is `0.0.0` (gate disabled), so omitting is fine in
  dev.
- `CF-Origin-Token: <shared secret>` — required in production behind
  Cloudflare; rejected with 403 if missing/wrong. Locally the env var
  is unset, so the gate is disabled.

### Optional but recommended headers

- `X-Correlation-Id: <UUID>` — if you set it, the BFF echoes it in
  logs and in error envelopes. If you omit, the BFF generates one and
  returns it in the response `X-Correlation-Id` header. **Always log
  whichever value you end up with** — it's the only way to thread
  client-side errors to BFF-side logs.
- `User-Agent: KRAIL/<version> (<platform>)` — useful for analytics.
  BFF doesn't act on it.

### Mobile-context headers (for analytics)

The BFF strips control characters and length-caps these — safe to send:

- `X-Device-Model`
- `X-OS-Name` (`Android` / `iOS`)
- `X-OS-Version`
- `X-App-Version` (your BuildConfig version name)
- `X-Client-Region` (e.g. `AU-NSW`)
- `X-Network-Type` (`wifi` / `cellular`)

**Do not send `X-Device-Id`** unless you've decided privacy-wise you're
OK with that. The BFF accepts it but never logs it.

### Error envelope

For errors originating in the BFF (4xx/5xx), the body is:
```json
{
  "error": {
    "code": "version_too_old",
    "message": "X-Krail-Version 1.2.0 is below the minimum 2.0.0",
    "details": null
  },
  "correlationId": "942cf2c5-ef3d-42e0-93c9-be120ecd1410"
}
```

`code` is a stable string identifier — use it in app logic, not
`message`. For 200-with-NSW-error responses (NSW returns 200 with
`error: {...}` for "no journeys found" etc.), the body is the NSW
JSON shape; the existing `TripResponse.error` parser handles it.

| Status code list | Meaning |
|---|---|
| 200 | OK (NSW or BFF) |
| 302 | Redirect (manifest endpoints only) |
| 400 `invalid_*` | Bad request — failed BFF input regex (`invalid_stop_id`, `invalid_facility_id`, `invalid_feed`) |
| 403 `version_too_old` | `X-Krail-Version` below `MIN_APP_VERSION` |
| 403 `cf_origin_token_missing` / `…_invalid` | `CF-Origin-Token` failed (production) |
| 429 `rate_limited` | Per-IP rate limit (5 RPS / 10 burst by default) |
| 502 | NSW upstream returned an error |
| 503 `daily_budget_exceeded` | NSW upstream daily quota exhausted |
| 503 `upstream_error` | NSW upstream unreachable / timeout |
| 504 | Upstream timeout |

---

## 11 · Local development workflow

### Start the BFF locally

```bash
cd /Users/ksharma/code/apps/KRAIL-BFF   # BFF repo root
./scripts/dev.sh up      # starts BFF on :8080 + dashboard on :8000
./scripts/dev.sh status  # green check + URLs
./scripts/dev.sh logs    # tail BFF log
./scripts/dev.sh down    # stop both
```

### Point your app at it

| Where you're running the app | URL |
|---|---|
| Android emulator (AVD) | `http://10.0.2.2:8080` |
| iOS Simulator | `http://localhost:8080` |
| Physical device, same Wi-Fi | `http://<Mac LAN IP>:8080` (find via `ipconfig getifaddr en0`) |

The BFF binds to `0.0.0.0:8080` already. macOS firewall may block
incoming on port 8080 for physical-device tests — System Settings →
Network → Firewall → allow Java / IntelliJ.

### Use the dashboard

<http://localhost:8000/api-tester.html> while `dev.sh up` is running.

What it does:
- Fire any BFF endpoint with form-fillable params.
- "Compare with NSW" — fires the same query at NSW direct (via the
  dev-only `/internal/passthrough` endpoint) and shows side-by-side
  status / latency / body-size diff.
- "Highlights" panel surfaces the important fields per response shape
  (journey count, next departure, redirect target for manifests, etc.)
  with copy buttons.
- Saved Postman-imported requests in the sidebar, grouped by KRAIL
  screen.
- History per endpoint with sparkline latency tracking.
- Trace mode for "what mobile calls" multi-step journeys.

When you're stuck on "what does the BFF actually return for input X,"
the dashboard is the fastest answer. Faster than reading source.

---

## 12 · Testing strategies

### Compare-mode tests (recommended for Phase B+)

Per `BFF_ADOPTION_GUIDE.md` "Compare-mode testing." For a sample of
queries — handful of trips you'd debug by hand — fire BFF and NSW
direct in parallel, parse both into your domain model, assert the
parsed output matches.

The BFF dashboard already has a Compare button that does this for a
single query. To productionise it for CI, write a Kotlin test that:
1. Reads a list of (origin, destination, date) tuples.
2. For each, fires both `RealTripPlanningService.trip()` against
   NSW and against the BFF, parsed into your existing domain.
3. Diffs the parsed structures with a tolerance for time fields
   (NSW realtime estimates drift between calls).

Target: 100% parity on journey count, transport modes, leg sequences;
< 1 minute drift on times.

### Snapshot tests for proto deserialization (Phase C)

Capture a real `JourneyList` proto byte payload, commit it as a test
fixture, snapshot-test the mapper output. When the BFF changes its
output (e.g. populates a new field), the snapshot diff makes the
change visible — you decide accept / fix.

### UI smoke per phase

Manual: after every phase ships, go through the four screens (search,
trip results, departures, park-ride, track). Confirm parity.

The KRAIL repo's `./scripts/fullQualityChecks.sh` runs Android compile
+ Detekt + Android unit tests. Run it before every commit; it catches
the easy regressions.

iOS — open `iosApp` in Xcode and build for the iOS Simulator. **Don't
run `xcodebuild` headless from the agent CLI** — KRAIL's iOS build is
not driven that way.

### Contract enforcement (BFF side, for reference)

The BFF runs `JourneyListContractTest` and `DatasetSmokeTest` on every
PR. They assert:
- Every `// contract: required` proto field is non-null in BFF
  response builders.
- `StopsDataset` and `RoutesDataset` round-trip via Wire encode/decode
  to bitwise-identical bytes.
- `TransportMode` enum values match NSW productClass codes (renumbering
  would silently break encoded datasets in the wild).

You don't write these tests in KRAIL — but if you ever see one fail
on a `proto-bump.yml` PR in the BFF, that's the BFF's job to fix
before the bump can merge.

---

## 13 · Common pitfalls

These are real things that have bitten me or are well-known traps:

- **Forgetting `submodules: true` on `actions/checkout`.** CI checkout
  doesn't fetch `krail-api-proto/`, Wire codegen fails with "no
  protos found." Set it on every workflow that compiles.
- **Forgetting `X-Krail-Version` in unit tests.** Your tests pass; the
  request 400s in production once you raise the floor. Add the header
  in the shared HTTP client config, not per-call.
- **Hard-coding the BFF URL.** Always go through `BuildKonfig`. Never
  commit a real production URL to a public repo (use environment
  variables / CI secrets).
- **Logging response bodies.** They contain stop IDs and times that, in
  aggregate, may reveal user patterns. Log status codes, sizes, and
  the correlation ID — not bodies.
- **Calling both endpoints in parallel for production users.** Compare
  mode is dev-only. Production calls one path or the other based on
  the flag.
- **Treating `bff_kill_switch` as a regular flag.** It's a panic
  button. If you flip it more than once a quarter, something's wrong
  with the per-endpoint flags or the BFF itself. Investigate root
  cause; don't normalise the kill switch.
- **Deleting the NSW path too early.** Wait the full 2-week 100% grace
  period. Premature deletion has caused real incidents because there's
  no fallback when the BFF has a bad day.
- **Skipping the cohort steps.** 0 → 100% in one push has caused real
  outages elsewhere. Use 10 / 50 / 100 even when it feels slow.
- **Treating proto3 zero values as "missing."** A `bool` field with
  default `false` looks the same on the wire as "field not set." Use
  `// contract: required` annotations to know which is which (see
  <https://ksharma-xyz.github.io/KRAIL-API-PROTO/contract>).
- **Mocking the BFF in CI tests.** Mocked tests pass but real upstream
  shape drift slips by. For Phase A/B, run integration tests against
  the actual local BFF (CI can do `./scripts/dev.sh up` in a
  background step). For Phase C, snapshot-test the proto fixtures.
- **Cleartext config leaking to release builds.** Put the
  `network_security_config.xml` under `androidApp/src/debug/` (not
  `src/main/`). Same for the `Info.plist` exception — debug-only if
  your iOS scheme supports it.
- **iOS Wire build trouble.** If Wire's KMP-iOS target misbehaves on a
  given Wire/Kotlin combo, the fallback is `kotlinx-serialization-protobuf`
  (same wire format, hand-mapped messages). Don't switch unless Wire
  actually breaks; the migration is one-way painful.

---

## 14 · Production prep (BFF side, for context)

This is **not your job** but you should know where the boundaries are
when reasoning about latency, cost, or downtime.

### Deploy

DigitalOcean App Platform, Sydney region, basic-xxs instance
(~A$8/mo flat). Deploy_on_push from `main`. `/health` is the readiness
probe. Secrets via DO console (`NSW_API_KEY`, `CF_ORIGIN_TOKEN`),
never in `.do/app.yaml`.

### Front

Cloudflare in front. CF Transform Rule sets `CF-Origin-Token` on every
request. DO firewall locked to Cloudflare's published IP ranges so the
bare DO URL is unreachable from the public internet. Free Cloudflare
plan suffices for ~400 MAU.

### Cost cap

**A$15/mo total budget.** The BFF deployment is A$8/mo flat (DO basic
+ Cloudflare free). The NSW upstream is rate-budgeted to 10 000
calls/day to stop a misbehaving client (or a leaked deploy URL) from
running up costs. There is no auto-scaling; a sudden surge that
exceeds capacity gets rate-limited, not paid for.

### Monitoring

Dropwizard metrics under `app.krail.bff.metrics.*`. NSW upstream call
durations, error rates, daily-budget consumption, per-IP rate-limit
hits. DigitalOcean App Platform exposes basic CPU/memory dashboards.

### Disaster recovery

~10 minute RTO. Redeploy from the last green main commit. Database:
none — the BFF is stateless except for in-memory rate-limit + budget
state. Cold start gives you a clean slate, the budget resets at Sydney
midnight regardless.

---

## 15 · Reference — file index

### KRAIL-BFF (server) repo

```
KRAIL-BFF/
├── server/
│   ├── build.gradle.kts                                 ← Wire sourcePath at $rootDir/krail-api-proto/proto
│   └── src/main/kotlin/app/krail/bff/
│       ├── Application.kt                               ← Plugin install order
│       ├── di/DI.kt                                     ← Singleton scoping
│       ├── client/nsw/NswClient.kt                      ← All NSW upstream calls
│       ├── mapper/JourneyListMapper.kt                  ← TripResponse → JourneyList proto
│       ├── routes/
│       │   ├── TripRoutes.kt                            ← /v1/tp/trip + /api/v1/trip/*
│       │   ├── DepartureRoutes.kt                       ← /v1/stops/{id}/departures
│       │   ├── ParkingRoutes.kt                         ← /v1/parking/facilities*
│       │   ├── GtfsRoutes.kt                            ← /v[1|2]/gtfs/*
│       │   ├── DataRoutes.kt                            ← manifest redirects
│       │   ├── InternalRoutes.kt                        ← /internal/passthrough (dev only)
│       │   └── Administration.kt                        ← /health, /ready
│       └── plugins/
│           ├── VersionGate.kt                           ← X-Krail-Version floor
│           ├── OriginTokenGate.kt                       ← CF-Origin-Token (prod only)
│           ├── PerIpRateLimit.kt                        ← Token bucket
│           ├── MobileAnalytics.kt                       ← Header sanitiser + MDC
│           ├── ErrorHandling.kt                         ← Error envelope
│           └── Compression.kt, Serialization.kt, …
├── krail-api-proto/                                     ← submodule pinned to v0.1.0
├── docs/handover/
│   ├── KRAIL_INTEGRATION_MASTER_PLAN.md                 ← THIS DOC
│   ├── KRAIL_APP_INTEGRATION_HANDOVER.md                ← Phase A playbook
│   ├── KRAIL_API_REFERENCE.md                           ← Field-by-field endpoint spec
│   └── PROTO_REPO_MIGRATION.md                          ← Submodule swap notes
├── docs/reference/
│   ├── BFF_ADOPTION_GUIDE.md                            ← Phase B feature-flag playbook
│   ├── API_SCHEMA_DESIGN.md                             ← Long-form proto design
│   ├── DEPLOYMENT.md                                    ← DO + Cloudflare setup
│   ├── CONFIGURATION.md                                 ← Env vars / yaml settings
│   └── TESTING_PLAYBOOK.md                              ← Test runbook
└── docs/tools/
    ├── api-tester.html                                  ← The dashboard
    └── bruno/                                           ← Bruno collection
```

### KRAIL-API-PROTO (proto contract) repo

<https://github.com/ksharma-xyz/KRAIL-API-PROTO>

```
KRAIL-API-PROTO/
├── proto/api/trip.proto                                 ← JourneyList + nested types
├── proto/data/stops_dataset.proto                       ← StopsDataset, TransportMode
├── proto/data/routes_dataset.proto                      ← RoutesDataset
├── docs/                                                ← rendered at https://ksharma-xyz.github.io/KRAIL-API-PROTO/
│   ├── getting-started.md                               ← Submodule + Wire snippets
│   ├── contract.md                                      ← required vs optional convention
│   ├── versioning.md                                    ← SemVer bump rules
│   ├── backward-compatibility.md                        ← Schema evolution rules
│   ├── testing.md                                       ← Three layers of enforcement
│   └── releasing.md                                     ← Release workflow
└── .github/workflows/
    ├── ci.yml                                           ← buf lint + buf breaking + version sanity
    ├── release.yml                                      ← tag-driven + workflow_dispatch
    └── pages.yml                                        ← deploy docs site
```

### KRAIL (app) repo

```
KRAIL/
├── core/network/src/commonMain/kotlin/xyz/ksharma/krail/core/network/
│   ├── BaseUrl.kt                                       ← edit: add KRAIL_BFF_BASE_URL
│   └── HttpClient.kt                                    ← edit: add X-Krail-Version default header
├── core/network/build.gradle.kts                        ← edit: BuildKonfig field
├── feature/trip-planner/network/.../service/RealTripPlanningService.kt    ← edit: trip() branch
├── feature/departures/network/.../service/RealDeparturesService.kt        ← edit: departures() branch
├── feature/park-ride/network/.../service/RealParkRideService.kt           ← edit: both overloads
├── feature/track/network/.../RealGtfsRealtimeService.kt                   ← edit: buildUrl()
├── androidApp/src/debug/AndroidManifest.xml             ← new (debug-only)
├── androidApp/src/debug/res/xml/network_security_config.xml ← new
├── iosApp/iosApp/Info.plist                             ← edit: NSAppTransportSecurity
├── local.properties                                     ← edit: add krail.bffBaseUrl (gitignored)
└── (Phase C) io/bff-api/                                ← new module: Wire + krail-api-proto submodule
└── (Phase C) krail-api-proto/                           ← new submodule
└── (Phase C) .github/workflows/proto-bump.yml           ← new: daily auto-bump
```

---

## 16 · Closing checklist for "Phase A done, ready for Phase B"

- [ ] BFF `/health` returned 200 from your dev machine.
- [ ] BuildKonfig field `KRAIL_BFF_BASE_URL` exists in `core/network/build.gradle.kts`.
- [ ] `local.properties` has `krail.bffBaseUrl=http://10.0.2.2:8080`.
- [ ] `BaseUrl.kt` exposes `KRAIL_BFF_BASE_URL` and `IS_BFF_LOCAL_OVERRIDE_SET`.
- [ ] `RealTripPlanningService.trip()` routes through the BFF when override is set; `stopFinder()` untouched.
- [ ] `RealDeparturesService.departures()` routes through the BFF when override is set.
- [ ] `RealParkRideService.fetchCarParkFacilities()` (both overloads) route through the BFF when override is set.
- [ ] `RealGtfsRealtimeService.buildUrl()` returns the BFF URL when override is set, for all three feed types.
- [ ] `androidApp/src/debug/res/xml/network_security_config.xml` allows cleartext for `10.0.2.2` / `localhost`.
- [ ] `androidApp/src/debug/AndroidManifest.xml` references it (debug-only).
- [ ] `iosApp/iosApp/Info.plist` has `NSAppTransportSecurity` exception.
- [ ] User confirmed `./scripts/fullQualityChecks.sh` passes.
- [ ] User confirmed iOS Simulator build succeeds.
- [ ] Trip search renders journeys (BFF log shows `GET /v1/tp/trip`).
- [ ] Departures list renders (BFF log shows `GET /v1/stops/{id}/departures`).
- [ ] Park & Ride facilities + detail render (BFF log shows the matching `GET`s).
- [ ] Live tracking renders with vehicles (BFF log shows GTFS-RT `GET`s).
- [ ] Stop search still works (NSW direct, untouched — proves Phase A didn't break the unmigrated path).
- [ ] `git status` does not list `local.properties`.

If all 17 boxes are ticked, **Phase A is done**. Stop, commit, report
back to the user. Phase B comes after the BFF is deployed and behind
Cloudflare, which is the BFF maintainer's responsibility, not yours.

---

## 17 · When to come back to this doc

- Before starting any phase: re-read its section and the linked deeper
  doc.
- When proto-bump PRs land in the KRAIL repo: re-read 6 / 13 /
  the testing section in `KRAIL-API-PROTO/docs/testing.md` to remind
  yourself what enforcement looks like.
- When debugging a production issue: 10's status-code list and 11's
  dashboard pointers are the fastest paths to a diagnosis.
- When asked "what's left to do?": 3's phase table.
