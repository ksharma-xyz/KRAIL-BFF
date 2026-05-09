# Testing Playbook

> The "today I'll actually use the BFF" runbook. Take the four steps in order; each one expects the previous to be green.

This is the operational companion to [START.md](../../START.md) (which covers strategic review). Open both side-by-side: **START.md = "what should I review and decide?"**, this file = **"what should I run, in what order?"**.

---

## Step 0 — One-time setup (5 min)

```bash
cd /Users/ksharma/code/apps/KRAIL-BFF

# 1. NSW API key (one-time)
cp local.properties.template local.properties
# Edit local.properties → set nsw.apiKey=<your NSW Open Data key>

# 2. Verify dependencies
./gradlew --version    # expects Gradle 9.x, JVM 17
```

If you don't have an NSW key: <https://opendata.transport.nsw.gov.au/>. Free; takes ~5 min to get one.

---

## Step 1 — Run unit tests (1 min)

This is the cheapest signal. If this fails, nothing else matters.

```bash
./gradlew :server:test --no-daemon
```

Expect: **49 tests passing** (17 original + 32 new from `plugin-tests`).

What it covers:
- All NSW client paths (success, 5xx, circuit breaker, daily budget)
- All plugins (correlation, error envelope, mobile analytics, version gate, origin token gate, per-IP rate limit)
- All `util/` types (TokenBucket, Version parser, NswDailyBudget)
- Trip request validation (every TripRequestError case)

If a test fails, the `pr.yml` workflow blocks merge, so you'll see it on every PR anyway. Fixing here is cheaper than fixing in the PR review loop.

---

## Step 2 — Start the server locally (10 sec)

```bash
./gradlew :server:run
```

Expect:
```
✅ NSW API Key loaded successfully from: local.properties file
✅ NSW daily call budget: 10000
Origin token gate disabled (CF_ORIGIN_TOKEN unset)
Version gate disabled (MIN_APP_VERSION = 0.0.0)
Application started in 0.3 seconds.
Responding at http://0.0.0.0:8080
```

Sanity check:
```bash
curl -fsS http://localhost:8080/health
```

If this hangs or 500s, check the gradle output for an exception (most commonly: missing API key).

---

## Step 3 — Smoke-test every endpoint (3 min)

Two equivalent options.

### 3a. Browser tester (recommended for the first run)

```bash
open docs/tools/api-tester.html
```

What you get:
- Every endpoint listed; default inputs are real Sydney stop IDs.
- Per-request: status, elapsed ms, body bytes, wire bytes (with gzip savings %).
- "× N times" button next to each Send: runs the same request 10 times, reports latency p50/p95/min/max + size stats. Use to validate p95 isn't pathological.
- Scenarios bar at top: one-click presets ("Wynyard → Town Hall", "Departures: Town Hall", etc.) — fills inputs and scrolls to the endpoint.
- "Run all GETs" smoke runner at the bottom: pass/fail summary across every endpoint in one click. CSV export of results.
- Config bar at the very top: Base URL, optional Authorization header, optional X-Krail-Version. **Stored in sessionStorage, wiped on tab close.**

### 3b. Bruno (if you prefer Postman-style)

```bash
brew install --cask bruno   # one-time
```

Open `docs/tools/bruno/` as a collection, pick the `local` environment, hit Send on any request. Each `.bru` file has inline `tests { ... }` blocks asserting status + Content-Type, so passing/failing is shown automatically.

CLI mode (use later for CI smoke checks):
```bash
npm install -g @usebruno/cli
bru run --env local docs/tools/bruno/Health
```

---

## Step 4 — Point your KRAIL app at the local BFF (10 min)

This is the integration moment. The full step-by-step is [LOCAL_TESTING.md](LOCAL_TESTING.md) §3, summary here:

```
Android emulator: KRAIL_BFF_BASE_URL = http://10.0.2.2:8080
iOS simulator:    KRAIL_BFF_BASE_URL = http://localhost:8080
Physical device:  KRAIL_BFF_BASE_URL = http://<Mac LAN IP>:8080
```

Per-feature checklist (work through each in the app, with the BFF terminal visible so you can correlate logs):

- [ ] **Trip planner** — search, results render, expand a journey, every leg/stop visible.
- [ ] **Saved trips** (no API per card) — cards from local DB.
- [ ] **Departure board** (expand a saved trip card) — 30 s polling, relative-time updates, real-time vs scheduled both shown.
- [ ] **Park & Ride** (if enabled) — spots/percentage match what NSW shows for the same facility.
- [ ] **Live tracking** (if enabled — feature is currently provisional, see [API_SCHEMA_DESIGN.md §2.5](API_SCHEMA_DESIGN.md)) — vehicle marker on map updates ~30 s; per-stop delays update.

Cross-cutting checks for each:
- Cold start works.
- Background → foreground (polling resumes).
- Airplane mode → reconnect (graceful error → retry succeeds).
- Logs in the BFF terminal show `correlationId` matching the response header `X-Request-Id`.

---

## Step 5 — Compare BFF response vs NSW direct (optional, 2 min)

This is where the API tester's payload metrics earn their keep. Two scenarios:

### 5a. Confirm the protobuf savings (BFF vs BFF)

In the api-tester:
1. Run scenario **"Trip: Wynyard → Town Hall"** (`/api/v1/trip/plan` — JSON).
2. Run scenario **"Trip (proto): Wynyard → Town Hall"** (`/api/v1/trip/plan-proto`).
3. Compare body bytes — protobuf should be ~80–90 % smaller.

### 5b. Confirm the BFF doesn't lose data vs NSW direct

1. In the api-tester config bar, change **Base URL** to `https://api.transport.nsw.gov.au` and **Authorization** to `apikey <your NSW key>` (the key field is sessionStorage-only).
2. Re-run the **legacy trip** endpoint (it has the same path on BFF and NSW: `/v1/tp/trip`).
3. Eyeball the JSON shape against the BFF response — should match (BFF is pass-through for this path).

### 5c. Latency comparison (10 runs each)

1. With Base URL = `http://localhost:8080`, click "× 10 times" on `tripPlanJson`. Note the p50/p95.
2. Switch Base URL to NSW direct (with key), click "× 10 times" on the same endpoint.
3. Compare p95. BFF latency = NSW latency + ~10–30 ms BFF overhead. If BFF p95 is dramatically worse, something's off.

---

## Step 6 — Plan the KRAIL integration (when ready)

Once the BFF feels solid locally, the integration is itself a multi-step process. Don't try to do it all in one PR.

The full guide: [KRAIL_INTEGRATION.md](KRAIL_INTEGRATION.md). Tl;dr:

1. **Stops + routes datasets first** — public NSW Open Data, no auth, lowest risk. Validates the manifest pattern end-to-end.
2. **Departure board** — small payload, easy comparison, exercises auth-free pass-through.
3. **Park & Ride** — tiny payload, low usage.
4. **Trip planner** — high traffic, biggest leverage.
5. **GTFS-Realtime** — heaviest path; do last.

Each one: 0 % (internal) → 10 % → 50 % → 100 % via Firebase Remote Config flag, with 48–72 h between steps. Watch p95 latency, error rate, "feature loaded" success metric per cohort. If anything regresses, flip the flag back.

The full rollout playbook with rollback procedure: [BFF_ADOPTION_GUIDE.md](BFF_ADOPTION_GUIDE.md).

---

## Common failure modes and what they mean

| Symptom | Likely cause | Fix |
|---|---|---|
| Server won't start: "NSW API Key is missing" | `local.properties` missing or `nsw.apiKey` empty | Copy the template, set the key |
| Server starts but every endpoint returns 500 | NSW upstream is down or your key is invalid | Check NSW status; rotate key if needed |
| Trip endpoint returns 200 with empty `journeys` array | Stop IDs invalid for this route, or no service at this time | Try a known-good pair: `200070 → 200060` (Wynyard → Town Hall) |
| "× 10 times" shows wildly varying latency | Cold JVM (first call ~200 ms, subsequent ~30 ms) | Run a warm-up Send before the multi-run |
| 429 on second smoke run | Per-IP burst exhausted from prior tests | Wait 2 sec or restart server |
| 503 `service_temporarily_limited` | Daily budget hit (default 10k/day) | Restart server (resets in-process counter) or raise `NSW_DAILY_BUDGET` |
| Browser CORS error from `file://` api-tester | Some endpoints reject `Origin: null` | Serve the tester via `python3 -m http.server -d docs/tools 8000` and use `http://localhost:8000/api-tester.html` |
| Android emulator can't reach `localhost:8080` | Emulator's loopback ≠ host's loopback | Use `http://10.0.2.2:8080` (host's loopback from inside the emulator) |

---

## Reference index

| Need | Document |
|---|---|
| Strategic review of the work | [START.md](../../START.md) |
| Why we chose what we chose | [MODERNIZATION_PLAN.md](MODERNIZATION_PLAN.md) |
| What each KRAIL screen displays | [SCREEN_DATA_INVENTORY.md](SCREEN_DATA_INVENTORY.md) |
| The proto contract | [API_SCHEMA_DESIGN.md](API_SCHEMA_DESIGN.md) |
| Local-dev / Android emulator setup | [LOCAL_TESTING.md](LOCAL_TESTING.md) |
| KRAIL integration files + snippets | [KRAIL_INTEGRATION.md](KRAIL_INTEGRATION.md) |
| Rollout / rollback procedure | [BFF_ADOPTION_GUIDE.md](BFF_ADOPTION_GUIDE.md) |
| Provisioning DO + Cloudflare | [DEPLOYMENT.md](DEPLOYMENT.md) |
| Env-var reference | [CONFIGURATION.md](CONFIGURATION.md) |
| Browser API tester | `docs/tools/api-tester.html` |
| Bruno collection | `docs/tools/bruno/` |
