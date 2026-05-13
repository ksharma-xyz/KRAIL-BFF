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

## Step 2 — Start the server + tester (one command)

```bash
./scripts/dev.sh up
```

This starts the BFF on `:8080`, serves the api-tester on `:8000` (so CORS works — `file://` origins get rejected by the BFF allowlist), polls `/health` until ready, and prints the URL to open. First run takes ~30–60 s for gradle to compile; later runs are fast.

To stop everything: `./scripts/dev.sh down`. To see what's running: `./scripts/dev.sh status`. To tail the BFF log: `./scripts/dev.sh logs`.

If the script complains about missing `nsw.apiKey`, do Step 0.

### What if I want to run it manually?

```bash
./gradlew :server:run                       # terminal 1
python3 -m http.server -d docs/tools 8000   # terminal 2
curl -fsS http://localhost:8080/health      # terminal 3 — sanity
```

Make sure `local.properties` includes `bff.cors.origins=http://localhost:8000` or the browser tester will fail with "Failed to fetch" on every request.

---

## Step 3 — Smoke-test every endpoint (3 min)

Two equivalent options.

### 3a. Browser tester (recommended for the first run)

After `./scripts/dev.sh up`, open the URL it printed: **`http://localhost:8000/api-tester.html`** (not the `file://` path — that fails CORS).

What you get:
- Every endpoint listed; default inputs are real Sydney stop IDs.
- **Inspector panel** per request: big colour-coded status badge, full URL, elapsed ms, body size + wire size + gzip savings %. Three open-by-default `<details>` sections: Request headers, Response headers, Response body. Three buttons per response: "Copy URL", "Copy as curl", "Copy body".
- **Network-error diagnostics**: when a request fails with "Failed to fetch", the inspector shows what that probably means (server down / CORS / mixed content / external origin) with a concrete fix per case. Plus the actual JS error message and a reminder that DevTools → Network has the real underlying error.
- **"× N times" button** next to each Send: runs the same request 10 times, reports latency p50/p95/min/max + size stats. Use to validate p95 isn't pathological.
- **Scenarios bar** at top: one-click presets ("Wynyard → Town Hall", "Departures: Town Hall", etc.) — fills inputs and scrolls to the endpoint.
- **"Run all GETs"** smoke runner at the bottom: pass/fail summary across every endpoint in one click. CSV export of results.
- **Config bar** at the very top: Base URL, optional Authorization header, optional X-Krail-Version. **Stored in sessionStorage, wiped on tab close.**

Leave Authorization blank when Base URL is `http://localhost:8080` — the BFF holds the NSW key server-side. Only fill it if you're pointing Base URL at NSW directly (and even then the browser will block the request because NSW doesn't send CORS headers; use Bruno or curl for that case).

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

## Why the script does what it does

You may wonder why `./scripts/dev.sh up` runs *two* servers. Short answer:

- **BFF on `:8080`** — the actual application.
- **Static server on `:8000`** — serves `api-tester.html` over `http://localhost:8000`. Browsers send `Origin: null` for `file://` pages; the BFF's CORS allowlist (deliberately strict for production safety) rejects `null`. Loading the tester from a real HTTP origin (`localhost:8000`) gives the browser an `Origin` header the BFF can authorise.

If you saw "Failed to fetch" on every endpoint before this script existed, that was almost certainly the CORS-vs-`file://` issue. The script makes it impossible to forget.

## Common failure modes and what they mean

| Symptom | Likely cause | Fix |
|---|---|---|
| Server won't start: "NSW API Key is missing" | `local.properties` missing or `nsw.apiKey` empty | Copy the template, set the key |
| Server starts but every endpoint returns 500 | NSW upstream is down or your key is invalid | Check NSW status; rotate key if needed |
| Trip endpoint returns 200 with empty `journeys` array | Stop IDs invalid for this route, or no service at this time | Try a known-good pair: `200070 → 200060` (Wynyard → Town Hall) |
| "× 10 times" shows wildly varying latency | Cold JVM (first call ~200 ms, subsequent ~30 ms) | Run a warm-up Send before the multi-run |
| 429 on second smoke run | Per-IP burst exhausted from prior tests | Wait 2 sec or restart server |
| 503 `service_temporarily_limited` | Daily budget hit (default 10k/day) | Restart server (resets in-process counter) or raise `NSW_DAILY_BUDGET` |
| Browser shows "Failed to fetch" on every request | Loading api-tester via `file://` (Origin: null is not in the BFF allowlist) | Use `./scripts/dev.sh up` instead — it serves the tester on `http://localhost:8000` and adds that to the BFF allowlist automatically |
| BFF won't start: port 8080 already in use | Another process holds the port (often a previous dev session) | `./scripts/dev.sh down` (kills strays); or `lsof -tiTCP:8080 -sTCP:LISTEN \| xargs kill -9` |
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
