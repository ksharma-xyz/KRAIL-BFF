# KRAIL app ↔ BFF integration — handover for the KRAIL codebase

> Audience: an LLM (or human) working in the **KRAIL app repo** (the
> KMP/Compose Multiplatform Android+iOS app), being asked to migrate
> the app off direct NSW API calls and onto the **KRAIL-BFF** for a
> local-only debug build. This document is the BFF team's handover —
> read it once, then start step 6.
>
> The BFF lives at `/Users/ksharma/code/apps/KRAIL-BFF`. You may be
> given that path; you may not. Either way, **everything you need to
> integrate is in this document.** Code references to BFF files are
> for if you want to dig deeper, not because you need to.

---

## 1 · TL;DR for the KRAIL agent

You are going to:

1. Add a build-time switch — `krail.bffBaseUrl` from `local.properties`
   into BuildKonfig — that overrides the existing `NSW_TRANSPORT_BASE_URL`
   in **debug builds only**, when set.
2. For **every NSW-direct call site that has a BFF equivalent**, route
   through the BFF when the override is set. Four screens:
   trip planner, departures (Saved Trips departures), park & ride, and
   GTFS-Realtime live tracking. Stop search stays on NSW (BFF has no
   `stop_finder` endpoint — long-term plan is local search against a
   stops dataset, but that's out of scope here).
3. Allow cleartext HTTP for the local BFF (`http://10.0.2.2:8080` on
   Android emulator, `http://localhost:8080` on iOS sim, `http://<LAN_IP>:8080`
   on a physical device).
4. **Ask the user to build the app and run it.** If the build succeeds
   and the four migrated screens render real data, you're done with
   the local proof-of-life.

**Out of scope right now:** stop search migration, stops dataset
download, routes (.pb) dataset, Firebase Remote Config flags, proto
wire format for trip plan, deletion of the NSW path, release builds,
dependency injection restructuring. Those happen later. This task is
purely "does the wire reach end-to-end on a local debug build for
every endpoint that has a BFF equivalent today."

**You should not:**
- Modify any release-build behavior.
- Delete the NSW API key from the app.
- Refactor service interfaces or rename modules.
- Add new modules or DI graphs.
- Touch the existing rate limiter, mappers, or response models.

If the existing NSW path still works in release and the new BFF path
works in debug, this task is done.

---

## 2 · What the BFF is

The KRAIL-BFF is a Kotlin/Ktor backend-for-frontend that sits between
the KRAIL app and NSW Open Data Transport APIs. Its responsibilities:

- Hold the NSW API key server-side (so the app stops shipping a key).
- Reshape responses (smaller payloads via protobuf where it helps).
- Cap upstream usage (per-IP rate limit + daily NSW request budget).
- Distribute static datasets (stops, routes) via versioned manifest
  + signed `.pb` downloads.

For this local-debug integration, you only care about the **endpoint
contracts**. The rest is the BFF's problem.

---

## 3 · Endpoint catalogue (BFF v1)

> **Field-by-field request/response specs live in [`KRAIL_API_REFERENCE.md`](KRAIL_API_REFERENCE.md).**
> That companion doc has real captured response bodies, full param
> tables (type / required / format / example), error envelopes, and a
> mapping from each BFF endpoint to KRAIL's existing parser class.
> This section is a one-paragraph-per-endpoint summary so the agent
> can decide what to migrate without flipping between docs; reach for
> the API reference whenever you need the exact wire shape.

All BFF endpoints accept GET. All return JSON unless marked `[proto]`
or `[binary GTFS-RT proto]`. None require Authorization from the
client (the BFF adds NSW's API key server-side). Local dev defaults
allow any `X-Krail-Version` (the gate is disabled at floor `0.0.0`).

### Trip planner

| BFF endpoint | Mirrors NSW | Notes |
|---|---|---|
| `GET /v1/tp/trip` | `/v1/tp/trip` | **Same path, same query params, same JSON response.** Drop-in replacement: change base URL only. |
| `GET /api/v1/trip/plan` | (BFF-shaped JSON) | Future home for screen-shaped JSON. Not used in this handover. |
| `GET /api/v1/trip/plan-proto` `[proto]` | (BFF protobuf) | 83% smaller than JSON. Requires the `JourneyList` proto. Not used in this handover. |

**Use this:** `GET /v1/tp/trip` — every existing query param the KRAIL
app already builds (name_origin, name_destination, depArrMacro,
itdDate, itdTime, calcNumberOfTrips, TfNSWTR, version, coordOutputFormat,
itOptionsActive, computeMonomodalTripBicycle, cycleSpeed, useElevationData,
outputFormat, excludedMeans, exclMOT1..exclMOT11) passes through unchanged.

### Stop finder

The BFF does not have a stop_finder endpoint. **Stop search stays
local in the app** — the app downloads a versioned stops dataset and
searches against it (see "Stops dataset" below). For now, leave the
existing NSW `/v1/tp/stop_finder` call alone.

### Departures (departure board)

| BFF endpoint | Replaces NSW | Notes |
|---|---|---|
| `GET /v1/stops/{stopId}/departures?date=&time=` | `/v1/tp/departure_mon` | **Different shape.** stopId is a path segment; date/time are query params; everything else is implied. Returns the NSW response body verbatim (same JSON the app already parses). |

The BFF strips the `NSW:` namespace prefix automatically, so the app
can pass either `200060` or `NSW:200060`.

### Park & Ride

| BFF endpoint | Replaces NSW | Notes |
|---|---|---|
| `GET /v1/parking/facilities` | `/v1/carpark` (no facility=) | Returns the full NSW facility list as JSON. |
| `GET /v1/parking/facilities/{facilityId}/availability` | `/v1/carpark?facility={id}` | Single facility's occupancy. |

Both pass-through — same response body the app already parses.

### GTFS-Realtime

| BFF endpoint | Mirrors NSW | Notes |
|---|---|---|
| `GET /v1/gtfs/realtime/{feed}` `[binary]` | `/v1/gtfs/realtime/{feed}` | **Same path.** Returns NSW protobuf bytes verbatim. The KRAIL `GtfsRealtimeMatcher` already decodes and matches client-side. |
| `GET /v2/gtfs/realtime/{feed}` `[binary]` | `/v2/gtfs/realtime/{feed}` | Same. v2 feeds: sydneytrains, metro. |
| `GET /v2/gtfs/vehiclepos/{feed}` `[binary]` | `/v2/gtfs/vehiclepos/{feed}` | Same. Used by the live-tracking / journey-map screens. |

`{feed}` accepts nested paths like `lightrail/cbdandsoutheast`.

### Stops + routes datasets (manifest pattern)

| BFF endpoint | Behavior |
|---|---|
| `GET /v1/data/stops/manifest` | 302 redirect to a JSON manifest hosted on GitHub Releases. The manifest has `{ version, generated_at, file_url, sha256, size_bytes }`. The app fetches the manifest, compares version, downloads the linked `.pb` (a `StopsDataset` protobuf) only if newer, caches it locally, runs stop search against it. |
| `GET /v1/data/routes/manifest` | Same, for `RoutesDataset`. |

Not used in this handover — but worth knowing: stop search is meant to
stay local in the app, fed by these datasets. The BFF only distributes;
it never serves a stop_finder response.

### Health / readiness

| BFF endpoint | Notes |
|---|---|
| `GET /health` | Returns `{"status":"UP"}`. Use for the smoke test in 6. |
| `GET /ready` | Returns `{"status":"READY","upstream":"ok|error"}`. Includes an NSW upstream probe. |

---

## 4 · BFF base URL — what to use locally

The local BFF binds to `0.0.0.0:8080`. Pick the URL that matches where
your app is running:

| App target | BFF base URL |
|---|---|
| Android emulator (AVD) | `http://10.0.2.2:8080` |
| iOS Simulator | `http://localhost:8080` |
| Physical Android, same Wi-Fi | `http://<HOST_LAN_IP>:8080` |
| Physical iOS, same Wi-Fi | `http://<HOST_LAN_IP>:8080` |

To find `<HOST_LAN_IP>` (developer's Mac): `ipconfig getifaddr en0`.

Cleartext HTTP is required because the local BFF doesn't have TLS.
**This must NOT be enabled for release builds** — see 6.7.

---

## 5 · Files in the KRAIL app you will touch

Verified paths as of 2026-05-09. If a path has moved, find the
equivalent file by name; the responsibility is unchanged.

| File | Why you're touching it |
|---|---|
| `core/network/src/commonMain/kotlin/xyz/ksharma/krail/core/network/BaseUrl.kt` | Currently exposes `const val NSW_TRANSPORT_BASE_URL`. Add a sibling `KRAIL_BFF_BASE_URL` driven from BuildKonfig. |
| `core/network/build.gradle.kts` (BuildKonfig source) | Wire `local.properties` → BuildKonfig field for the BFF URL. |
| `local.properties` (gitignored) | New entry: `krail.bffBaseUrl=http://10.0.2.2:8080`. Empty / missing → BFF disabled, app uses NSW direct (existing behavior). |
| `feature/trip-planner/network/src/commonMain/kotlin/xyz/ksharma/krail/trip/planner/network/api/service/RealTripPlanningService.kt` | The `trip()` function. Change the URL to the BFF when override is set. **Don't touch `stopFinder()`** in the same file — it stays on NSW. |
| `feature/departures/network/src/commonMain/kotlin/xyz/ksharma/krail/departures/network/api/service/RealDeparturesService.kt` | Currently calls `$NSW_TRANSPORT_BASE_URL/v1/tp/departure_mon`. New shape: `$KRAIL_BFF_BASE_URL/v1/stops/{stopId}/departures`. |
| `feature/park-ride/network/src/commonMain/kotlin/xyz/ksharma/krail/park/ride/network/service/RealParkRideService.kt` | Both `fetchCarParkFacilities()` overloads. NSW: `/v1/carpark` (with/without `facility=`). BFF: `/v1/parking/facilities` and `/v1/parking/facilities/{facilityId}/availability`. |
| `feature/track/network/src/commonMain/kotlin/xyz/ksharma/krail/feature/track/network/RealGtfsRealtimeService.kt` | The private `buildUrl()` helper. NSW and BFF use **identical paths** for all three GTFS-RT routes — change the base only. |
| `androidApp/src/debug/AndroidManifest.xml` (create if absent) | Debug-only manifest that references the cleartext config below. |
| `androidApp/src/debug/res/xml/network_security_config.xml` (create) | Cleartext exception for `10.0.2.2` / `localhost`. Lives under `src/debug/` so release builds never see it. |
| `iosApp/iosApp/Info.plist` | `NSAppTransportSecurity` exception for `localhost` (and your LAN IP if testing on device). |

> **Module choice is fixed:** `:androidApp` is the APK module, `:composeApp` is
> the shared KMP library. All Android-manifest / cleartext changes go in
> `:androidApp` only. Don't touch `composeApp/src/androidMain/AndroidManifest.xml`.

The other BFF endpoints (parking, GTFS-RT) follow the same playbook —
do them after the trip planner works, or skip and come back. **Do not
do all four at once.** One endpoint, build, verify, commit. Then the
next.

---

## 6 · Step-by-step: do the integration

### 6.0 · Confirm the BFF is running

The BFF developer should have started it before handing this work off.
You can check yourself:

```bash
curl -s http://localhost:8080/health
# expect: {"status":"UP"}
```

If `Connection refused`, the BFF is not running. **Stop and tell the
user** — don't try to start it yourself unless you have access to the
BFF repo. The developer starts it with `./scripts/dev.sh up` from the
BFF repo root.

### 6.1 · Add the BFF base URL via BuildKonfig

KRAIL already uses BuildKonfig (see `core/network/build/buildkonfig/...`).
Wire one new field.

In `core/network/build.gradle.kts`, find the BuildKonfig block and add
a string field for the BFF base URL, sourcing from `local.properties`:

```kotlin
// Pseudocode — match the existing BuildKonfig syntax in this file.
buildkonfig {
    packageName = "xyz.ksharma.krail.core.network"
    defaultConfigs {
        // ...existing fields...
        buildConfigField(
            STRING,
            "KRAIL_BFF_BASE_URL",
            localPropertiesGetOrEmpty("krail.bffBaseUrl"), // helper that reads local.properties
        )
    }
}
```

If a `localPropertiesGetOrEmpty` helper doesn't exist, copy the same
pattern this module already uses to read NSW's API key from `local.properties`.

Add to `local.properties` (gitignored):

```properties
krail.bffBaseUrl=http://10.0.2.2:8080
```

Empty / missing → BFF disabled (app uses NSW direct, existing behavior).
This is a deliberate kill-switch.

### 6.2 · Expose the BFF URL in commonMain

Edit `core/network/src/commonMain/kotlin/xyz/ksharma/krail/core/network/BaseUrl.kt`:

```kotlin
package xyz.ksharma.krail.core.network

import xyz.ksharma.krail.core.network.NetworkBuildKonfig

const val NSW_TRANSPORT_BASE_URL = "https://api.transport.nsw.gov.au"

/**
 * Local KRAIL-BFF base URL, sourced from `local.properties` `krail.bffBaseUrl`.
 * Empty when the developer has not opted in to local-BFF testing —
 * services should fall back to NSW direct in that case.
 *
 * Intentionally not used in release builds (yet). When release routing
 * lands it will be flagged via Firebase Remote Config, not this constant.
 */
val KRAIL_BFF_BASE_URL: String = NetworkBuildKonfig.KRAIL_BFF_BASE_URL

/** True when the developer has set `krail.bffBaseUrl` in local.properties. */
val IS_BFF_LOCAL_OVERRIDE_SET: Boolean = KRAIL_BFF_BASE_URL.isNotBlank()
```

### 6.3 · Wire endpoint #1 — trip planner (one-line swap)

In `feature/trip-planner/network/src/commonMain/kotlin/xyz/ksharma/krail/trip/planner/network/api/service/RealTripPlanningService.kt`, the existing call is:

```kotlin
httpClient.get("$NSW_TRANSPORT_BASE_URL/v1/tp/trip") { ... }
```

Replace with:

```kotlin
import xyz.ksharma.krail.core.network.IS_BFF_LOCAL_OVERRIDE_SET
import xyz.ksharma.krail.core.network.KRAIL_BFF_BASE_URL
// existing NSW import stays

private val tripBaseUrl: String =
    if (IS_BFF_LOCAL_OVERRIDE_SET) KRAIL_BFF_BASE_URL else NSW_TRANSPORT_BASE_URL

// ...inside trip(...):
httpClient.get("$tripBaseUrl/v1/tp/trip") { ... }
```

The path and query params are identical between NSW and BFF for this
endpoint, so this is genuinely a one-line URL swap. Don't touch the
`stopFinder` function in the same file — it stays on NSW (BFF has no
stop_finder).

### 6.4 · Wire endpoint #2 — departures (path reshape)

This one's trickier — different URL shape between NSW and BFF.

In `feature/departures/network/src/commonMain/kotlin/xyz/ksharma/krail/departures/network/api/service/RealDeparturesService.kt`:

The existing call (NSW):
```kotlin
httpClient.get("$NSW_TRANSPORT_BASE_URL/v1/tp/departure_mon") {
    url {
        parameters.append("name_dm", stopId)
        parameters.append("type_dm", "stop")
        // ...other params
    }
}
```

Replace with a branched version:
```kotlin
val response = if (IS_BFF_LOCAL_OVERRIDE_SET) {
    // BFF shape: stopId in path, date/time as query (everything else implied).
    httpClient.get("$KRAIL_BFF_BASE_URL/v1/stops/$stopId/departures") {
        url {
            date?.let { parameters.append("date", it) }   // YYYYMMDD
            time?.let { parameters.append("time", it) }   // HHmm
        }
    }
} else {
    httpClient.get("$NSW_TRANSPORT_BASE_URL/v1/tp/departure_mon") {
        url {
            parameters.append("name_dm", stopId)
            // ...existing NSW params unchanged
        }
    }
}
```

The response body is the same NSW JSON shape in both branches, so the
existing parser works unchanged.

### 6.5 · Wire endpoint #3 — park & ride

In `feature/park-ride/network/src/commonMain/kotlin/xyz/ksharma/krail/park/ride/network/service/RealParkRideService.kt`:

There are two overloads, both currently calling `$NSW_TRANSPORT_BASE_URL/v1/carpark`.
The BFF splits them into two distinct paths.

**Overload A — facility list (no facility ID):**

```kotlin
// Existing NSW call:
httpClient.get("$NSW_TRANSPORT_BASE_URL/v1/carpark") {}.body()

// Replace with branched version:
val response: Map<String, String> = if (IS_BFF_LOCAL_OVERRIDE_SET) {
    httpClient.get("$KRAIL_BFF_BASE_URL/v1/parking/facilities") {}.body()
} else {
    httpClient.get("$NSW_TRANSPORT_BASE_URL/v1/carpark") {}.body()
}
```

**Overload B — single facility availability:**

```kotlin
// Existing NSW call:
httpClient.get("$NSW_TRANSPORT_BASE_URL/v1/carpark") {
    url { parameters.append("facility", facilityId) }
}.body()

// Replace with branched version:
val response: CarParkFacilityDetailResponse = if (IS_BFF_LOCAL_OVERRIDE_SET) {
    httpClient.get("$KRAIL_BFF_BASE_URL/v1/parking/facilities/$facilityId/availability") {}.body()
} else {
    httpClient.get("$NSW_TRANSPORT_BASE_URL/v1/carpark") {
        url { parameters.append("facility", facilityId) }
    }.body()
}
```

The response JSON shape is identical between NSW and BFF in both
overloads — same parsers, no model changes.

Add the imports at the top of the file:
```kotlin
import xyz.ksharma.krail.core.network.IS_BFF_LOCAL_OVERRIDE_SET
import xyz.ksharma.krail.core.network.KRAIL_BFF_BASE_URL
```

Park & Ride is gated behind the existing `NSW_PARK_RIDE_BETA` Firebase
RC flag in the app — make sure that's flipped on for your debug
device, otherwise the screen is hidden and you can't smoke-test it.

### 6.6 · Wire endpoint #4 — GTFS-Realtime (live tracking)

In `feature/track/network/src/commonMain/kotlin/xyz/ksharma/krail/feature/track/network/RealGtfsRealtimeService.kt`:

The cleanest swap point is the private `buildUrl()` function — it's
the only place `NSW_TRANSPORT_BASE_URL` appears in this file, and
the BFF uses **identical paths** for all three feed types.

```kotlin
// Add imports at the top:
import xyz.ksharma.krail.core.network.IS_BFF_LOCAL_OVERRIDE_SET
import xyz.ksharma.krail.core.network.KRAIL_BFF_BASE_URL

// Existing function:
private fun buildUrl(feedName: String, feedType: GtfsFeedType): String {
    return when (feedType) {
        GtfsFeedType.VEHICLE_POSITIONS ->
            "$NSW_TRANSPORT_BASE_URL/v2/gtfs/vehiclepos/$feedName"
        GtfsFeedType.TRIP_UPDATES -> {
            val version = if (feedName in V2_FEEDS) "v2" else "v1"
            "$NSW_TRANSPORT_BASE_URL/$version/gtfs/realtime/$feedName"
        }
    }
}

// Replace with:
private val gtfsBaseUrl: String =
    if (IS_BFF_LOCAL_OVERRIDE_SET) KRAIL_BFF_BASE_URL else NSW_TRANSPORT_BASE_URL

private fun buildUrl(feedName: String, feedType: GtfsFeedType): String {
    return when (feedType) {
        GtfsFeedType.VEHICLE_POSITIONS ->
            "$gtfsBaseUrl/v2/gtfs/vehiclepos/$feedName"
        GtfsFeedType.TRIP_UPDATES -> {
            val version = if (feedName in V2_FEEDS) "v2" else "v1"
            "$gtfsBaseUrl/$version/gtfs/realtime/$feedName"
        }
    }
}
```

Notes:
- The response is binary GTFS-RT protobuf bytes. The BFF returns the
  exact same bytes NSW returns, so the existing `FeedMessage.ADAPTER.decode(bytes)`
  call works unchanged.
- The HEAD-request optimisation (skip parse when `Last-Modified` is
  unchanged) also works against the BFF — the BFF passes through NSW's
  `Last-Modified` header.
- Live tracking is **built but hidden** in the app per project
  context. To smoke-test it, you may need to enable whichever feature
  flag exposes the `TrackTripScreen` / `JourneyMapScreen`. If it's
  not flag-gated and just hidden by navigation, ask the user how to
  reach those screens.

### 6.7 · Cleartext HTTP — debug only

#### Android

All changes go in the **`:androidApp`** module (the APK). `:composeApp`
is the shared KMP library — leave its manifest alone.

Create `androidApp/src/debug/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Production: HTTPS only, no exceptions. -->
    <base-config cleartextTrafficPermitted="false" />

    <!-- Local KRAIL-BFF dev — debug builds only. -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">localhost</domain>
        <!-- Add your LAN IP if testing on a physical device. -->
    </domain-config>
</network-security-config>
```

Create `androidApp/src/debug/AndroidManifest.xml` (or extend it if it
already exists). The Android build system merges this with the main
manifest for debug builds only:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:networkSecurityConfig="@xml/network_security_config"
        tools:replace="android:networkSecurityConfig" />
</manifest>
```

The `tools:replace` is there in case the main manifest already declares
`networkSecurityConfig` — if it doesn't, you can remove the attribute.
This config is invisible to release builds because it lives under
`src/debug/`.

#### iOS

In `iosApp/iosApp/Info.plist`, add inside `<dict>`:

```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSExceptionDomains</key>
    <dict>
        <key>localhost</key>
        <dict>
            <key>NSExceptionAllowsInsecureHTTPLoads</key>
            <true/>
            <key>NSIncludesSubdomains</key>
            <false/>
        </dict>
    </dict>
</dict>
```

This file is not flavor-aware out of the box. If KRAIL has a separate
debug Info.plist or scheme-driven config, prefer putting the exception
there. Otherwise leave it — `localhost` is harmless to expose.

### 6.8 · Build and run

**Do not run Gradle / Xcode build commands yourself.** Per KRAIL repo
convention, builds and CLI iOS work are done by the user, not the
agent. Instead:

> **Ask the user to run** `./scripts/fullQualityChecks.sh` from the
> KRAIL repo root and paste the tail of the output back to you.
> That script runs the canonical Android build + checks for this repo.

For iOS:

> **Ask the user to open `iosApp` in Xcode** and build for the iOS
> Simulator. Don't try `xcodebuild` headless from CLI — KRAIL's iOS
> build is not driven that way.

Common breakages to call out if the build fails:

- BuildKonfig field not picked up — Gradle sync didn't pick up the
  new `local.properties` line, or there's a typo in the key name
  (`krail.bffBaseUrl`, exact case).
- Import statement missing — Kotlin doesn't auto-import the new
  `IS_BFF_LOCAL_OVERRIDE_SET` / `KRAIL_BFF_BASE_URL` constants;
  add them explicitly.
- Manifest merge conflict on `android:networkSecurityConfig` — the main
  manifest already declares it. Use `tools:replace="android:networkSecurityConfig"`
  in the `src/debug/AndroidManifest.xml` (already in 6.7).
- BuildKonfig task not part of the KRAIL build graph for `:core:network`
  — check `core/network/build.gradle.kts` for the existing
  BuildKonfig configuration and add the new field next to whatever
  fields are already there. Don't add a fresh BuildKonfig block.

### 6.9 · Smoke-test the integration

With the app running, exercise each migrated surface:

1. **Trip search** — search Town Hall → Central. Trip results should
   render with real journeys.
2. **Departures** (Saved Trips → tap a stop) — departures list should
   show next services.
3. **Park & Ride** — open the Park & Ride screen (gated behind
   `NSW_PARK_RIDE_BETA` Firebase RC flag — enable it on your debug
   device first). Facility list + per-facility availability should both
   render.
4. **Live tracking / journey map** (currently hidden — coordinate with
   the user on how to reach those screens). Vehicle markers should
   appear on the map with positions updating roughly every 30s.

Ask the user to watch the BFF log while exercising the screens:

```bash
# From the BFF repo root:
./scripts/dev.sh logs
# Or directly: tail -f build/dev/bff.log
```

The user should see `GET` log lines that map 1:1 to the screens you
exercised:

| Screen | Expected BFF log line |
|---|---|
| Trip search | `GET /v1/tp/trip` |
| Departures | `GET /v1/stops/{stopId}/departures` |
| Park & Ride list | `GET /v1/parking/facilities` |
| Park & Ride detail | `GET /v1/parking/facilities/{facilityId}/availability` |
| Live tracking (trip updates) | `GET /v1/gtfs/realtime/{feed}` or `GET /v2/gtfs/realtime/{feed}` |
| Live tracking (vehicle positions) | `GET /v2/gtfs/vehiclepos/{feed}` |

If a screen renders but the matching log line never appears, the
override didn't activate for that service — re-check the
`if (IS_BFF_LOCAL_OVERRIDE_SET)` branch in that service file.

If a screen doesn't render at all but used to, you broke the existing
NSW path — revert and check the `else` branch.

### 6.10 · Verify nothing else regressed

- **Stop search** still works → confirms the NSW direct path is still
  alive for `RealTripPlanningService.stopFinder()` (which the
  handover explicitly does not touch).
- **App launch / cold start** → no startup crash from the BuildKonfig
  change.
- **Release behavior** (don't actually build it, but eyeball the diff)
  → `IS_BFF_LOCAL_OVERRIDE_SET` is false when the local.properties key
  is empty, which is the production state. The cleartext config sits
  under `androidApp/src/debug/` so it never lands in release.

If anything that wasn't supposed to change broke, revert that file
before commit.

### 6.11 · Commit shape

A clean commit message for this change:

```
feat(network): debug-only KRAIL-BFF override for all NSW-covered endpoints

Behind the new local.properties key `krail.bffBaseUrl` (empty by default,
debug only). When set, the four NSW-direct services that have BFF
equivalents route through the BFF instead:

- RealTripPlanningService.trip()         → BFF /v1/tp/trip (same shape)
- RealDeparturesService.departures()     → BFF /v1/stops/{id}/departures
- RealParkRideService.fetchCarParkFacilities()
                                         → BFF /v1/parking/facilities[/{id}/availability]
- RealGtfsRealtimeService (all 3 feeds)  → BFF /v[1|2]/gtfs/{realtime|vehiclepos}/{feed}

stopFinder stays on NSW direct (BFF has no stop_finder; long-term plan
is local search against a stops dataset, separate work).

Release builds + non-overridden debug builds are unchanged. Cleartext
exception scoped to androidApp/src/debug/ so release stays HTTPS-only.
```

Do **not** commit `local.properties`. Verify `git status` shows it as
untracked / modified-but-ignored before you push.

---

## 7 · BFF error responses (reference)

If the BFF is misbehaving, you'll see one of these in the response.
KRAIL's existing services expect NSW's error shapes, not the BFF's
envelope below — for this handover, **don't change the existing error
parsing**. The table is reference for when you (or the user) are
debugging at the network layer; surfacing BFF-specific error messages
in the UI is out of scope here.

| BFF status | Meaning | What to do |
|---|---|---|
| 400 `invalid_stop_id` | stopId failed regex `^[A-Za-z0-9:]{1,40}$` | Check what the app passed. NSW stop IDs are numeric; with `NSW:` prefix they're up to ~10 chars. |
| 400 `invalid_feed` | GTFS feed name failed regex | Confirm feed string matches NSW's documented feed names (`sydneytrains`, `lightrail/cbdandsoutheast`, etc.). |
| 403 `version_too_old` | `X-Krail-Version` header below `MIN_APP_VERSION` floor | Local default is 0.0.0 (disabled). If you see this locally, someone set the floor — check BFF's `local.properties`. |
| 429 `rate_limited` | Per-IP rate limit (5 RPS / 10 burst) | You're hammering. Slow down or restart the BFF. |
| 503 `daily_budget_exceeded` | NSW upstream daily quota exhausted | Local default cap is 10000/day. You'd have to be running a load test to hit this. |
| 502 / 504 | NSW upstream failed or timed out | The BFF couldn't reach NSW. Check connectivity from the BFF host, not the app. |

The error envelope shape is consistent:

```json
{
  "error": {
    "code": "version_too_old",
    "message": "X-Krail-Version 1.2.0 is below the minimum 2.0.0",
    "detail": null
  },
  "correlationId": "abc123-..."
}
```

Always log the `correlationId` if you log anything — it threads
through BFF logs and makes debugging tractable.

---

## 8 · BFF source code references

You don't need these to integrate — but if you hit a surprising
response, here's where the contract lives. Paths are inside the BFF
repo (`/Users/ksharma/code/apps/KRAIL-BFF`):

| Topic | File |
|---|---|
| **Field-by-field API reference** | `docs/handover/KRAIL_API_REFERENCE.md` (companion to this doc) |
| Endpoint routing (top-level) | `server/src/main/kotlin/app/krail/bff/Application.kt` |
| Trip planner routes | `server/src/main/kotlin/app/krail/bff/routes/TripRoutes.kt` |
| Departures route | `server/src/main/kotlin/app/krail/bff/routes/DepartureRoutes.kt` |
| Parking routes | `server/src/main/kotlin/app/krail/bff/routes/ParkingRoutes.kt` |
| GTFS-RT routes | `server/src/main/kotlin/app/krail/bff/routes/GtfsRoutes.kt` |
| Manifest routes | `server/src/main/kotlin/app/krail/bff/routes/DataRoutes.kt` |
| Trip plan-proto schema | `krail-api-proto/proto/api/trip.proto` (submodule) |
| Stops dataset schema | `krail-api-proto/proto/data/stops_dataset.proto` (submodule) |
| Routes dataset schema | `krail-api-proto/proto/data/routes_dataset.proto` (submodule) |
| Version gate (X-Krail-Version) | `server/src/main/kotlin/app/krail/bff/plugins/VersionGate.kt` |
| Per-IP rate limit | `server/src/main/kotlin/app/krail/bff/plugins/PerIpRateLimit.kt` |
| Origin token gate (prod only) | `server/src/main/kotlin/app/krail/bff/plugins/OriginTokenGate.kt` |
| Application config (defaults) | `server/src/main/resources/application.yaml` |
| Long-form integration playbook | `docs/reference/BFF_ADOPTION_GUIDE.md` |
| Endpoint contracts (formal) | `docs/reference/API_SCHEMA_DESIGN.md` |
| Local-test runbook (BFF side) | `START.md` |
| Project status / roadmap | `STATUS.md` |

---

## 9 · After this handover succeeds

This handover ends at "trip + departures + park & ride + GTFS-RT all
work locally on debug, release behavior unchanged." The next pieces
are done in subsequent handovers, not this one:

1. **Stop search → local dataset** — replace
   `RealTripPlanningService.stopFinder()` with local search against
   the BFF-distributed `StopsDataset` proto. Out of scope here per
   user's instruction.
2. **Routes dataset** — same manifest pattern as stops; fed to KRAIL's
   GTFS-static replacement. Out of scope here.
3. **`X-Krail-Version` header** — added once in the shared Ktor client
   config (`core/network/`) when the BFF tightens its version gate.
4. **`krail-api-proto` repo + Wire codegen** — required only when you
   move from `/v1/tp/trip` (raw NSW JSON) to `/api/v1/trip/plan-proto`
   (BFF protobuf, ~83% smaller). Skip until you want the wire-size win.
5. **Firebase Remote Config flags** — `bff_kill_switch`,
   `bff_use_for_<endpoint>`. Required only for staged rollout in
   release builds. Skip for local-debug.
6. **Compare-mode tests** — fire BFF and NSW in parallel, diff the
   parsed output. Useful in CI; not needed for the manual smoke test.
7. **Delete the in-app NSW API key** — only after every endpoint is
   100% on the BFF in production for 2+ weeks.

---

## 10 · Checklist for the KRAIL agent

Hand this back filled in:

- [ ] BFF `/health` returned 200 (you confirmed by running the curl in 6.0).
- [ ] BuildKonfig field `KRAIL_BFF_BASE_URL` added in `core/network/build.gradle.kts`.
- [ ] `local.properties` updated with `krail.bffBaseUrl=<host:port>`.
- [ ] `BaseUrl.kt` exposes `KRAIL_BFF_BASE_URL` and `IS_BFF_LOCAL_OVERRIDE_SET`.
- [ ] `RealTripPlanningService.trip()` routes through the BFF when the override is set. `stopFinder()` untouched.
- [ ] `RealDeparturesService.departures()` routes through the BFF when the override is set.
- [ ] `RealParkRideService.fetchCarParkFacilities()` (both overloads) route through the BFF when the override is set.
- [ ] `RealGtfsRealtimeService.buildUrl()` uses the BFF base URL when the override is set, for all three feed types.
- [ ] `androidApp/src/debug/res/xml/network_security_config.xml` created with cleartext exception for `10.0.2.2` / `localhost`.
- [ ] `androidApp/src/debug/AndroidManifest.xml` references `@xml/network_security_config` (debug-only).
- [ ] `iosApp/iosApp/Info.plist` has `NSAppTransportSecurity` exception for `localhost`.
- [ ] Asked the user to run `./scripts/fullQualityChecks.sh` (KRAIL repo) — output paste-back shows it succeeded.
- [ ] Asked the user to build & run on iOS Simulator from Xcode — succeeded.
- [ ] Trip search renders journeys (BFF log shows `GET /v1/tp/trip`).
- [ ] Departures list renders (BFF log shows `GET /v1/stops/{id}/departures`).
- [ ] Park & Ride facilities + detail render (BFF log shows `GET /v1/parking/facilities` and `…/availability`).
- [ ] Live tracking / map screens render with vehicles (BFF log shows `GET /v[1|2]/gtfs/realtime/...` and `/v2/gtfs/vehiclepos/...`).
- [ ] Stop search still works (NSW direct, untouched).
- [ ] `git status` does not list `local.properties`.
- [ ] Release behavior unchanged — `src/debug/` scoping verified by inspection, not built.

If all boxes are ticked, the handover is complete. Report back to
the user with a summary of what changed, plus any surprises.
