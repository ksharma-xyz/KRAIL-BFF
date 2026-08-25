# KRAIL Integration Guide

> Concrete, file-by-file checklist for migrating the KRAIL app from "calls NSW directly with an API key in the binary" to "calls KRAIL-BFF with no key on device." Audience: whoever is making the changes inside the KRAIL repo (`/Users/ksharma/code/apps/KRAIL/`).

This doc is the operational counterpart to [BFF_ADOPTION_GUIDE.md](BFF_ADOPTION_GUIDE.md) (which is the high-level playbook). Read this when you're sitting down to actually edit KRAIL files.

> **The tour, in one paragraph.** Add a `KRAIL_BFF_BASE_URL` build config alongside the existing NSW URL. Add an `X-Krail-Version` header to a shared HTTP client. Per feature, add a Firebase Remote Config flag that picks the URL: BFF when on, NSW direct when off. Roll out per feature in cohorts (10/50/100). Once everything's at 100% for ~2 weeks, delete the NSW-direct paths and the API keys from `NetworkBuildKonfig`.

---

## 1. Prereqs and constraints

- KRAIL is a Kotlin Multiplatform app (Android + iOS) using Ktor client.
- API keys live today in `NetworkBuildKonfig` (generated from `local.properties` / env vars at build time): `ANDROID_NSW_TRANSPORT_API_KEY`, `IOS_NSW_TRANSPORT_API_KEY`. **These are shipped in the binary and decompilable.** The migration ends with their deletion.
- Firebase Remote Config is already in use (Park & Ride, Discover cards, Info Tiles). We reuse it for migration flags.
- Force-update mechanism is already in the app.
- BFF endpoints expected (post-merge of Phase 0 stack):

  | KRAIL feature | BFF endpoint | Replaces NSW endpoint |
  |---|---|---|
  | Trip planner | `GET /api/v1/trip/plan` | `GET /v1/tp/trip` |
  | Departure board | `GET /v1/stops/{id}/departures` | `GET /v1/tp/departure_mon` |
  | Park & Ride list | `GET /v1/parking/facilities` | `GET /v1/carpark` |
  | Park & Ride availability | `GET /v1/parking/facilities/{id}/availability` | `GET /v1/carpark?facility=` |
  | GTFS-RT trip updates v1 | `GET /v1/gtfs/realtime/{feed}` | same path on NSW |
  | GTFS-RT trip updates v2 | `GET /v2/gtfs/realtime/{feed}` | same path on NSW |
  | GTFS-RT vehicle positions | `GET /v2/gtfs/vehiclepos/{feed}` | same path on NSW |
  | Stops dataset manifest | `GET /v1/data/stops/manifest` | (new — replaces bundled `.pb`) |
  | Stop search | n/a — stays local | n/a — bundled dataset replaces stop_finder calls |

---

## 2. Add the BFF base URL alongside the NSW one

**File:** wherever `NetworkBuildKonfig` is generated. Likely `core/network/build.gradle.kts` or `core/network/network/build.gradle.kts`. Look for the existing string buildConfigField for `ANDROID_NSW_TRANSPORT_API_KEY`.

Add (without removing existing fields):

```kotlin
// Build script — read from local.properties / env, default to NSW direct
val krailBffBaseUrl = (localProperties.getProperty("krail.bff.baseUrl")
    ?: System.getenv("KRAIL_BFF_BASE_URL")
    ?: "https://bff.krail.app")  // production placeholder; override in dev local.properties

buildConfigField("String", "KRAIL_BFF_BASE_URL", "\"$krailBffBaseUrl\"")
```

In `local.properties` for dev:
```
krail.bff.baseUrl=http://10.0.2.2:8080
```
(Android emulator) or
```
krail.bff.baseUrl=http://localhost:8080
```
(iOS simulator).

---

## 3. Add a shared "BFF HTTP client" with `X-Krail-Version`

**Place:** `core/network/network/src/commonMain/.../KrailBffHttpClient.kt` (new). Reuses your existing platform engines (OkHttp on Android, Darwin on iOS).

```kotlin
expect fun krailBffHttpClient(json: Json): HttpClient

// commonMain default header / config
fun HttpClientConfig<*>.commonBffConfig(json: Json) {
    install(ContentNegotiation) { json(json) }
    install(DefaultRequest) {
        // Sent on every BFF call; the BFF will eventually enforce a minimum.
        header("X-Krail-Version", appVersion())
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 15_000
    }
}

// Read from BuildKonfig — set per-platform at compile time.
private fun appVersion(): String = NetworkBuildKonfig.APP_VERSION_NAME
```

Make sure `NetworkBuildKonfig` exposes the app's semver version (e.g. `1.5.0`). If not, add it from the gradle script.

**No `Authorization` header.** BFF endpoints accept anonymous calls; rate-limited per IP. The NSW key never goes through this client.

---

## 4. Add Firebase Remote Config flags

**File:** `core/remote-config/.../RemoteConfigKeys.kt` (or wherever existing flags live, e.g. `NSW_PARK_RIDE_BETA`).

Add per-endpoint flags (default `false`) plus a global kill switch:

```kotlin
const val BFF_USE_FOR_TRIP = "bff_use_for_trip"
const val BFF_USE_FOR_DEPARTURES = "bff_use_for_departures"
const val BFF_USE_FOR_PARKING = "bff_use_for_parking"
const val BFF_USE_FOR_GTFS_REALTIME = "bff_use_for_gtfs_realtime"
const val BFF_USE_FOR_STOPS_DATASET = "bff_use_for_stops_dataset"
const val BFF_KILL_SWITCH = "bff_kill_switch"  // when true, ALL of the above ignored
```

Set the **fetch interval short** (≤ 5 min) for these specific flags so rollback is fast.

---

## 5. Per-feature wiring

For each feature, the pattern is the same: split the existing service into "BFF path" and "NSW direct path", pick at runtime based on the flag.

### 5.1 Trip planner

**File:** `feature/trip-planner/network/src/commonMain/kotlin/.../service/RealTripPlanningService.kt`

Existing:
```kotlin
class RealTripPlanningService(
    private val nswHttpClient: HttpClient,
) : TripPlanningService {
    override suspend fun trip(...): TripResponse {
        return nswHttpClient.get("${NetworkBuildKonfig.NSW_TRANSPORT_BASE_URL}/v1/tp/trip") {
            header("Authorization", "apikey ${NetworkBuildKonfig.NSW_TRANSPORT_API_KEY}")
            // … query params
        }.body()
    }
}
```

After:
```kotlin
class RealTripPlanningService(
    private val nswHttpClient: HttpClient,
    private val bffHttpClient: HttpClient,
    private val flags: RemoteConfigFlags,
) : TripPlanningService {
    override suspend fun trip(...): TripResponse {
        val useBff = flags.boolean(BFF_USE_FOR_TRIP) && !flags.boolean(BFF_KILL_SWITCH)
        return if (useBff) {
            bffHttpClient.get("${NetworkBuildKonfig.KRAIL_BFF_BASE_URL}/api/v1/trip/plan") {
                // No Authorization header; X-Krail-Version added by DefaultRequest plugin.
                // Same query params as before.
            }.body()
        } else {
            nswHttpClient.get("${NetworkBuildKonfig.NSW_TRANSPORT_BASE_URL}/v1/tp/trip") {
                header("Authorization", "apikey ${NetworkBuildKonfig.NSW_TRANSPORT_API_KEY}")
                // Same query params.
            }.body()
        }
    }
}
```

The `TripResponse` shape is identical (BFF returns NSW JSON pass-through). Mappers downstream don't change.

### 5.2 Stop finder

**Stays local.** Per the modernization decision, search runs against the bundled / downloaded stops dataset, never via the BFF. No changes here unless you're also implementing dataset auto-update (see §5.5).

### 5.3 Departure board

**File:** `feature/departures/network/src/commonMain/kotlin/.../service/RealDeparturesService.kt`

After:
```kotlin
override suspend fun departures(stopId: String, date: String?, time: String?): DepartureMonitorResponse {
    val useBff = flags.boolean(BFF_USE_FOR_DEPARTURES) && !flags.boolean(BFF_KILL_SWITCH)
    return if (useBff) {
        // BFF path — note: stopId may need namespace prefix stripped if you store as "NSW:200060"
        val plainId = stopId.substringAfter(':', stopId)
        bffHttpClient.get("${NetworkBuildKonfig.KRAIL_BFF_BASE_URL}/v1/stops/$plainId/departures") {
            date?.let { parameter("date", it) }
            time?.let { parameter("time", it) }
        }.body()
    } else {
        nswHttpClient.get("${NetworkBuildKonfig.NSW_TRANSPORT_BASE_URL}/v1/tp/departure_mon") {
            header("Authorization", "apikey ${NetworkBuildKonfig.NSW_TRANSPORT_API_KEY}")
            // existing query params
        }.body()
    }
}
```

### 5.4 Park & Ride

**File:** `feature/park-ride/network/src/commonMain/kotlin/.../service/RealParkRideService.kt`

```kotlin
override suspend fun facility(facilityId: String): CarParkFacilityDetailResponse {
    val useBff = flags.boolean(BFF_USE_FOR_PARKING) && !flags.boolean(BFF_KILL_SWITCH)
    return if (useBff) {
        bffHttpClient.get("${NetworkBuildKonfig.KRAIL_BFF_BASE_URL}/v1/parking/facilities/$facilityId/availability").body()
    } else {
        nswHttpClient.get("${NetworkBuildKonfig.NSW_TRANSPORT_BASE_URL}/v1/carpark") {
            header("Authorization", "apikey ${NetworkBuildKonfig.NSW_TRANSPORT_API_KEY}")
            parameter("facility", facilityId)
        }.body()
    }
}
```

The facility list (currently from Firebase Remote Config `NSW_PARK_RIDE_FACILITIES`) can stay in RC, OR move to `GET /v1/parking/facilities`. Up to you — RC is fine.

### 5.5 GTFS-Realtime (live tracking)

**File:** `feature/track/network/src/commonMain/kotlin/.../RealGtfsRealtimeService.kt`

Two endpoints to migrate:

```kotlin
override suspend fun fetchTripUpdates(version: Int, feed: String): ByteArray {
    val useBff = flags.boolean(BFF_USE_FOR_GTFS_REALTIME) && !flags.boolean(BFF_KILL_SWITCH)
    val url = if (useBff) {
        "${NetworkBuildKonfig.KRAIL_BFF_BASE_URL}/v$version/gtfs/realtime/$feed"
    } else {
        "${NetworkBuildKonfig.NSW_TRANSPORT_BASE_URL}/v$version/gtfs/realtime/$feed"
    }
    val client = if (useBff) bffHttpClient else nswHttpClient
    return client.get(url) {
        if (!useBff) header("Authorization", "apikey ${NetworkBuildKonfig.NSW_TRANSPORT_API_KEY}")
    }.body()
}

override suspend fun fetchVehiclePositions(feed: String): ByteArray {
    val useBff = flags.boolean(BFF_USE_FOR_GTFS_REALTIME) && !flags.boolean(BFF_KILL_SWITCH)
    val url = if (useBff) {
        "${NetworkBuildKonfig.KRAIL_BFF_BASE_URL}/v2/gtfs/vehiclepos/$feed"
    } else {
        "${NetworkBuildKonfig.NSW_TRANSPORT_BASE_URL}/v2/gtfs/vehiclepos/$feed"
    }
    val client = if (useBff) bffHttpClient else nswHttpClient
    return client.get(url) {
        if (!useBff) header("Authorization", "apikey ${NetworkBuildKonfig.NSW_TRANSPORT_API_KEY}")
    }.body()
}
```

Decoding (`GtfsRealtimeMatcher`) doesn't change — bytes are bytes; the protobuf schema is the same on both sides.

The `Last-Modified` HEAD-check optimisation also doesn't change — both BFF (pass-through) and NSW return the same header. Just preserve that logic when switching the URL.

### 5.6 Stops dataset

**File:** `gtfs-static/src/commonMain/kotlin/.../RealNswGtfsService.kt` (or new file alongside).

Today the app bundles a GTFS protobuf at build time. New flow when `BFF_USE_FOR_STOPS_DATASET` is on:

```kotlin
suspend fun maybeUpdateStopsDataset() {
    if (!flags.boolean(BFF_USE_FOR_STOPS_DATASET)) return

    val manifestUrl = "${NetworkBuildKonfig.KRAIL_BFF_BASE_URL}/v1/data/stops/manifest"
    // BFF returns a 302 to the actual manifest JSON on GitHub Releases; HttpClient follows redirects by default.
    val manifest: StopsManifest = bffHttpClient.get(manifestUrl).body()

    val storedVersion = sandook.preferences.getString("stops_dataset_version", null)
    if (manifest.version == storedVersion) return  // up to date

    // Download the .pb at manifest.url, verify sha256, atomically swap in sandook.
    val pbBytes = bffHttpClient.get(manifest.url).body<ByteArray>()
    require(sha256(pbBytes) == manifest.sha256) { "Stops dataset sha256 mismatch" }

    val dataset = StopsDataset.ADAPTER.decode(pbBytes)
    sandook.replaceAllStops(dataset.stops)
    sandook.preferences.putString("stops_dataset_version", manifest.version)
}

@Serializable
data class StopsManifest(
    val version: String,
    val sha256: String,
    val url: String,
    val size_bytes: Int,
)
```

Trigger: on cold start + every 24h. The bundled `.pb` stays for now as a fallback for first launch / offline / flag-off case. Delete it from the build only after the BFF dataset is reliable in production.

### 5.7 Tracking deep link — switch to compact format

**Files:**
- `feature/track/state/.../TripDeepLinkEncoder.kt`
- `feature/track/state/.../TripDeepLink.kt`
- `feature/track/state/.../TripDeepLinkDecoder.kt` (or wherever the decode side lives)

**Today** the deep link is base64url-encoded JSON of the full `TripDeepLink`: from/to IDs + names, departure UTC, every leg's `transportationId` and product class, excluded modes. Real payload is 300–530 chars.

**Going forward**, encode only what the journey-recovery flow actually needs:

```
https://ksharma-xyz.github.io/trip?o=10101100&d=10102099&dep=2025-04-19T22:26:00Z&fn=Seven%20Hills&tn=Wynyard
```

Total ~150–200 chars. See [API_SCHEMA_DESIGN.md §2.5 "Tracking deep link"](API_SCHEMA_DESIGN.md) for the rationale.

**Implementation:**

1. **Add a `decodeCompact()`** alongside the existing `decodeLegacy()` (which is today's base64-JSON decoder). Detect by parameter presence:
   ```kotlin
   fun TripDeepLinkDecoder.decode(url: Url): TripDeepLink? = when {
       url.parameters["d"] != null -> decodeLegacy(url.parameters["d"]!!)
       url.parameters["o"] != null -> decodeCompact(url.parameters)
       else -> null
   }

   private fun decodeCompact(params: Parameters): TripDeepLink = TripDeepLink(
       fromStopId = params["o"]!!,
       toStopId = params["d"]!!,
       fromStopName = params["fn"].orEmpty(),
       toStopName = params["tn"].orEmpty(),
       departureUtcDateTime = params["dep"]!!,
       legs = params["legs"]?.split(',')?.mapNotNull { /* parse "tid:cls" pairs */ } ?: emptyList(),
       excludedProductClasses = params["excl"]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
   )
   ```

2. **Add a `encodeCompact()`** alongside the existing `encode()`. Behind `BFF_USE_FOR_TRIP` (or a dedicated `BFF_USE_FOR_TRACKING_DEEPLINK`), pick the encoder:
   ```kotlin
   fun TripDeepLinkEncoder.encode(deepLink: TripDeepLink): String =
       if (flags.boolean(BFF_USE_FOR_TRACKING_DEEPLINK)) encodeCompact(deepLink) else encodeLegacy(deepLink)
   ```

3. **Parse both formats forever.** Old deep links shared on social or saved by users will keep arriving even years after the flag is at 100%. Decoder must support both.

4. **Generate only the new format once flag is on.** After 2 weeks at 100% rollout, you can delete `encodeLegacy()` (but keep `decodeLegacy()`).

**What happens if the BFF is down?** Either format degrades the same way — the app falls back to its current "call NSW directly with the minimum identifiers and recover via `findMatchingJourney`" path. The compact format carries the same minimum (origin, destination, departure_utc) the fallback needs.

**Tests** to add in `TripDeepLinkDecoderTest`:
- Decode legacy URL (existing test) — keep passing.
- Decode compact URL with all params.
- Decode compact URL without optional `fn`/`tn`/`legs`/`excl`.
- Decode compact URL with malformed `dep` → returns null.
- Round-trip `encodeCompact()` → `decodeCompact()`.

---

## 6. Compare-mode testing (do this before flipping any flag)

For each migrated service, in dev builds only:

```kotlin
override suspend fun departures(stopId: String): DepartureMonitorResponse {
    if (BuildConfig.DEBUG && flags.boolean("compare_bff_with_nsw")) {
        val (bff, nsw) = coroutineScope {
            awaitAll(
                async { runCatching { bffPath(stopId) } },
                async { runCatching { nswPath(stopId) } },
            )
        }
        if (bff.isSuccess && nsw.isSuccess) {
            diffAndLog(bff.getOrThrow(), nsw.getOrThrow())
        }
    }
    return if (useBff) bffPath(stopId) else nswPath(stopId)
}
```

`diffAndLog` should ignore real-time-jittering fields (`departureTimeEstimated`, anything timestamp-based) and only assert stable identifiers match. Otherwise you'll get noise.

Strip / guard before merging compare-mode to release builds.

---

## 7. Rollout sequence

Per [BFF_ADOPTION_GUIDE.md](BFF_ADOPTION_GUIDE.md), one feature at a time:

1. **Stops dataset** first — public NSW data, no auth, lowest risk. Validates the HTTP path end-to-end.
2. **Departure board** — small payload, easy comparison, exercises auth-free pass-through.
3. **Park & Ride** — tiny payload, low usage.
4. **Trip planner** — high traffic, biggest leverage.
5. **GTFS-Realtime** — last; this is the heaviest path (binary, frequent polling) so we do it after the simpler ones land.

For each: 0% (internal devices) → 10% → 50% → 100%, with 48–72h between steps. Watch:
- App-side: feature loads success rate, error rate.
- BFF-side: per-endpoint 5xx, 429, 503 counts; p95 latency.

If any regression, flip the per-endpoint flag back to `false`.

---

## 8. Cleanup (after all features at 100% + 2 weeks)

This is where the security win actually lands. Do this in a single PR.

1. Delete the NSW-direct call branches from each `Real*Service` (the `else` paths above).
2. Delete `NetworkBuildKonfig.NSW_TRANSPORT_API_KEY` (Android + iOS variants) and the gradle code that generates them.
3. Remove `NSW_TRANSPORT_API_KEY` from `local.properties.template` and any CI secret config.
4. Coordinate with NSW: mint a fresh API key for the BFF, get the old one (the one that was in app binaries) **deleted in NSW's portal**. The leaked-binary-key risk is gone after this.
5. Delete the NSW HttpClient instance entirely if no service still uses it.
6. Delete the per-endpoint feature flags (the kill switch stays — useful indefinitely).
7. (Optional) Force-update minimum app version to the version that has BFF support, so old versions stop talking to NSW under your account.

Do **not** rush this step. The 2-week grace is to make sure no edge case sends users back to the NSW path.

---

## 9. Security checklist

After integration is complete, the security posture should be:

- [ ] No NSW API key in any released APK/IPA (verify with `apkanalyzer dex packages` / `strings`).
- [ ] BFF base URL in the binary points at production (`https://bff.krail.app` or whatever you provisioned).
- [ ] All BFF calls send `X-Krail-Version`. Old app versions without this are rejected by the BFF (`MIN_APP_VERSION` enforces).
- [ ] Force-update mechanism hits anyone below `MIN_APP_VERSION`.
- [ ] No persistent user-identifying data sent to BFF (no device IDs, no IPs in app-side logs).
- [ ] Compare-mode + cohort flags are removed from release builds.
- [ ] BFF kill switch is documented in your team's incident response.

---

## 10. Open questions to resolve as you go

These come up during implementation. Decisions are yours to make; the BFF supports either:

- **Stop ID namespacing** — `"NSW:200060"` in the new dataset (per BFF design). Strip the prefix before forwarding to NSW endpoints (the code samples above already do this). Consistency with the design helps when you add a 2nd city later.
- **Where to put `KRAIL_BFF_BASE_URL`** — alongside `NSW_TRANSPORT_BASE_URL` is the simplest. If you have separate Android/iOS BuildKonfigs, mirror the pattern.
- **Migration of `NSW_PARK_RIDE_FACILITIES` from Firebase RC to BFF** — optional. RC works fine. Move only when you have multi-city in mind.
- **Compare-mode flag name** — pick anything; just make sure it's gated by `BuildConfig.DEBUG` so it can't accidentally activate in production.

---

## 11. Quick reference

| Path in KRAIL | Change |
|---|---|
| `core/network/.../build.gradle.kts` (or wherever BuildKonfig is) | Add `KRAIL_BFF_BASE_URL` field |
| `core/network/.../KrailBffHttpClient.kt` | New file — Ktor client with `X-Krail-Version` default header |
| `core/remote-config/.../RemoteConfigKeys.kt` | Add `BFF_USE_FOR_*` + `BFF_KILL_SWITCH` |
| `feature/trip-planner/network/.../RealTripPlanningService.kt` | Branch on `BFF_USE_FOR_TRIP` |
| `feature/departures/network/.../RealDeparturesService.kt` | Branch on `BFF_USE_FOR_DEPARTURES` |
| `feature/park-ride/network/.../RealParkRideService.kt` | Branch on `BFF_USE_FOR_PARKING` |
| `feature/track/network/.../RealGtfsRealtimeService.kt` | Branch on `BFF_USE_FOR_GTFS_REALTIME` |
| `feature/track/state/.../TripDeepLinkEncoder.kt` | Add `encodeCompact()`; pick via `BFF_USE_FOR_TRACKING_DEEPLINK` |
| `feature/track/state/.../TripDeepLinkDecoder.kt` | Add `decodeCompact()`; parse both legacy + compact forever |
| `gtfs-static/.../RealNswGtfsService.kt` | Add `maybeUpdateStopsDataset()` triggered on cold start |
| `local.properties` (your dev workstation) | Add `krail.bff.baseUrl=http://10.0.2.2:8080` |
| `app/src/main/res/xml/network_security_config.xml` | Already allows 10.0.2.2 cleartext for emulator dev |
