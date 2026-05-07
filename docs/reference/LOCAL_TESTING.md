# KRAIL-BFF Local Testing Guide

> Step-by-step setup for running the BFF on your Mac and pointing the KRAIL app (Android emulator / iOS simulator) at it. Audience: anyone preparing for a feature integration test session.

This guide assumes the BFF has merged Phase 0 (security foundation, abuse protection, deploy-mvp, stops-dataset, departures, parking, gtfs-realtime). If you're testing against a partial state, some endpoints may not exist yet.

---

## 0. Prerequisites

- **JDK 17** — `/usr/libexec/java_home -v 17` should resolve. If not, install via `brew install temurin@17` or SDKMAN.
- **NSW Open Data Hub API key** — sign in at <https://opendata.transport.nsw.gov.au/> and create one. The BFF needs this to talk to NSW; the app no longer needs its own copy.
- **Android Studio** (for Android emulator) or **Xcode** (for iOS simulator).
- **Optional but useful**: `gh`, `httpie` or `curl`, `jq`.

---

## 1. Run the BFF locally

### 1.1 Configure the API key

```bash
cd /Users/ksharma/code/apps/KRAIL-BFF
cp local.properties.template local.properties
# Edit local.properties and set:
#   nsw.apiKey=<your NSW Open Data key>
```

`local.properties` is git-ignored. Do not commit it.

For local browser testing (api-tester.html, dev pages), also set CORS:
```
bff.cors.origins=http://localhost:3000,http://localhost:63342
```

### 1.2 Start the server

```bash
./gradlew :server:run
```

Expected log output (last few lines):
```
✅ NSW API Key loaded successfully from: local.properties file
✅ NSW daily call budget: 10000
Origin token gate disabled (CF_ORIGIN_TOKEN unset)
Version gate disabled (MIN_APP_VERSION = 0.0.0)
Application started in 0.3 seconds.
Responding at http://0.0.0.0:8080
```

The version gate and origin-token gate are deliberately disabled for local dev — they only activate when the matching env vars are set.

### 1.3 Verify health

```bash
curl -fsS http://localhost:8080/health
```
Should print a small JSON `{"status": "..."}` and exit 0.

If you see `Connection refused`, the server didn't start. Check the gradle output for an exception (most commonly: missing API key, port 8080 already in use).

---

## 2. Smoke-test every endpoint

Each line below should return either valid data or a clearly-shaped error envelope. Replace stop IDs (`10101100`, `10101328`) with real Sydney stop IDs as needed.

### 2.1 Trip planner (existing, JSON)

```bash
curl -fsS \
  "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328" \
  | jq '.journeys | length'
```
Expected: a number > 0. If 0, NSW returned no journeys for those stops.

### 2.2 Trip planner (existing, protobuf)

```bash
curl -fsS \
  -H 'Accept: application/protobuf' \
  -o /tmp/trip.pb \
  "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101328"
wc -c /tmp/trip.pb
```
Expected: a binary file, typically ~10–25 KB (vs ~80–150 KB for JSON).

### 2.3 Departure board

```bash
curl -fsS "http://localhost:8080/v1/stops/200060/departures" | jq '.stopEvents | length'
```
Expected: a count of upcoming departures (NSW returns ~20).

### 2.4 Park & ride — facilities

```bash
curl -fsS "http://localhost:8080/v1/parking/facilities" | jq '.[]?.facility_name' | head -10
```
Expected: list of facility names from NSW.

### 2.5 Park & ride — single facility availability

```bash
curl -fsS "http://localhost:8080/v1/parking/facilities/1/availability" | jq '.facility_name, .spots'
```
Replace `1` with a real facility ID from the list.

### 2.6 GTFS-Realtime trip updates (binary)

```bash
curl -fsS \
  -o /tmp/sydneytrains-rt.pb \
  "http://localhost:8080/v2/gtfs/realtime/sydneytrains"
wc -c /tmp/sydneytrains-rt.pb
```
Expected: binary protobuf, hundreds of KB.

### 2.7 GTFS-Realtime vehicle positions (binary)

```bash
curl -fsS \
  -o /tmp/sydneytrains-vp.pb \
  "http://localhost:8080/v2/gtfs/vehiclepos/sydneytrains"
wc -c /tmp/sydneytrains-vp.pb
```

### 2.8 Stops dataset manifest (redirect)

```bash
curl -fsSI "http://localhost:8080/v1/data/stops/manifest"
```
Expected: `404` until `STOPS_MANIFEST_URL` is set (see [DEPLOYMENT.md](DEPLOYMENT.md)) — the workflow needs to publish a release first.

To test against a published manifest URL, set in `local.properties`:
```
data.stops.manifestUrl=https://github.com/ksharma-xyz/KRAIL-BFF/releases/latest/download/manifest.json
```

### 2.9 Negative cases (verify error handling)

```bash
# Missing required param
curl -i "http://localhost:8080/api/v1/trip/plan?origin=10101100"
# Expected: 400 Bad Request, JSON envelope with code "missing_destination"

# Invalid stop ID format
curl -i "http://localhost:8080/v1/stops/not-a-stop!/departures"
# Expected: 400 Bad Request, JSON envelope with code "invalid_stop_id"

# Hammer per-IP limiter (15 quick requests; default per-IP burst is 10)
for i in {1..15}; do curl -s -o /dev/null -w "%{http_code} " "http://localhost:8080/v1/stops/200060/departures"; done
# Expected: first ~10 are 200, then 429s
```

---

## 3. Point the KRAIL app at local BFF

### 3.1 Android emulator

The Android emulator can't reach `localhost` directly — it's the emulator's own loopback. The host's localhost is reachable at **`10.0.2.2`** from inside the emulator.

In KRAIL's `local.properties` (or wherever the network base URL is configured per the [KRAIL Integration Guide](KRAIL_INTEGRATION.md)):
```
KRAIL_BFF_BASE_URL=http://10.0.2.2:8080
```

You also need to allow cleartext for that host (HTTP, not HTTPS, since local). KRAIL already has a network security config for `10.0.2.2` per its existing setup.

### 3.2 iOS simulator

The iOS simulator shares the host's network stack — `localhost` works directly.

```
KRAIL_BFF_BASE_URL=http://localhost:8080
```

App Transport Security — for HTTP localhost connections in dev builds, add to your dev `Info.plist`:
```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsLocalNetworking</key>
    <true/>
</dict>
```

### 3.3 Physical device

Use your Mac's LAN IP (`ipconfig getifaddr en0`), then `http://<LAN_IP>:8080`. The phone must be on the same Wi-Fi network. Set `BFF_CORS_ORIGINS` to allow that origin if needed.

---

## 4. Per-feature manual checklist

After pointing the app at the local BFF, work through each feature:

| Feature | Triggers BFF endpoint | What to verify |
|---|---|---|
| Trip planner | `/api/v1/trip/plan` | Search → results render → expand a journey → all legs / stops shown correctly |
| Saved trips home | (no API per card) | Cards render from local DB |
| Departure board (saved trip card expand) | `/v1/stops/{id}/departures` | 30s polling → relative time updates → real-time vs scheduled both correct |
| Park & Ride (if enabled) | `/v1/parking/facilities/{id}/availability` | Spots/percentage match what NSW shows |
| Live tracking (if enabled) | `/v2/gtfs/realtime/...`, `/v2/gtfs/vehiclepos/...` | Vehicle marker on map updates ~30s; per-stop delays update |
| Service alerts | (no extra call; uses trip data) | Alerts list renders |

For each, also verify:
- Cold start
- Background → foreground (polling resumes)
- Airplane mode → reconnect (graceful error → retry succeeds)

---

## 5. Compare-mode testing (catch behaviour differences)

Highly recommended before flipping any feature flag for real users. Run both code paths in the dev build and diff the resulting domain models (per [BFF_ADOPTION_GUIDE.md](BFF_ADOPTION_GUIDE.md) §"Compare-mode testing"):

```kotlin
// In KRAIL service implementation, dev builds only
if (BuildConfig.DEBUG) {
    val viaBff = bffClient.departures(stopId).toDomain()
    val viaNsw = nswClient.departureMon(stopId).toDomain()
    if (viaBff != viaNsw) {
        Log.w("CompareMode", "departure mismatch: $viaBff vs $viaNsw")
    }
}
```

Real-time fields (`departureTimeEstimated`, vehicle positions) will jitter — diff only on stable identifiers (stop_id, line, scheduled time).

---

## 6. Edge cases worth poking

| Scenario | How to trigger | Expected |
|---|---|---|
| Per-IP rate limit | Spam an endpoint | 429 with `Retry-After: 1` |
| Daily NSW budget hit | `NSW_DAILY_BUDGET=5 ./gradlew :server:run` then 6 calls | 503 `service_temporarily_limited` with `Retry-After: 3600` |
| Version gate (when enabled) | `MIN_APP_VERSION=2.0.0 ./gradlew :server:run` then call without `X-Krail-Version` | 400 `missing_version` |
| Version gate (old version) | Same env, with `X-Krail-Version: 1.0.0` | 426 `upgrade_required` |
| Origin token gate (when enabled) | `CF_ORIGIN_TOKEN=secret ./gradlew :server:run` then call without `CF-Origin-Token` | 403 `forbidden` |
| NSW upstream down | Set `NSW_BASE_URL=http://localhost:9999` (unreachable) | After ~5s timeout: 500 `internal_error` |
| NSW returns 5xx | NSW actually returning errors | 502 `upstream_error` (typed via `NswUpstreamException`) |

---

## 7. Watching the BFF while testing

```bash
# In another terminal — tail the JSON-structured log
./gradlew :server:run 2>&1 | tee /tmp/bff.log

# Filter by correlationId after grabbing one from a response header:
grep '"correlationId":"<id>"' /tmp/bff.log | jq
```

Each request has a `correlationId` header in the response (`X-Request-Id`); cross-reference logs to that ID to trace one request across plugins / NSW calls.

---

## 8. Tearing down

```bash
# Stop the server (Ctrl-C in the gradle terminal)
# Reset the app to NSW direct by toggling the feature flag in code, not by deleting BFF state.
```

There is no persistent state in the BFF — kill the JVM, all in-memory caches and rate-limit buckets are gone. On restart, daily budget counter resets to 0 (cold-start side effect; production accepts this since instances rarely restart mid-day).

---

## 9. Troubleshooting

| Symptom | Likely cause |
|---|---|
| `BUILD SUCCESSFUL` then nothing happens | The server is now running. The gradle process stays attached. Open another terminal for curl. |
| `Address already in use: bind` | Port 8080 in use. Kill the other process or run with `-PrunPort=8081`. |
| `❌ CONFIGURATION ERROR: NSW Transport API Key is missing!` | `local.properties` not present or `nsw.apiKey` empty. |
| `429 Too Many Requests` instantly on every call | Per-IP burst exhausted from earlier tests. Wait 2 sec or restart server. |
| Android emulator: `Failed to connect to 10.0.2.2/10.0.2.2:8080` | BFF not running, or running on a different port, or firewall. Confirm with `curl 127.0.0.1:8080/health` from the host. |
| iOS simulator: `The resource could not be loaded because the App Transport Security policy requires the use of a secure connection` | Need the `NSAllowsLocalNetworking` dev-only ATS exception. |
| Trip results empty for a known-good route | Probably valid stop IDs but route doesn't exist between them. Try `220020` (Town Hall) → `200060` (Wynyard). |
| GTFS-RT bytes look corrupt | They aren't — the response is binary protobuf. Decode with `gtfs-realtime-tools` or the KRAIL app to inspect. |
