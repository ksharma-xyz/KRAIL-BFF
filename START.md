# START HERE

> Single-page entry point for reviewing KRAIL-BFF as a Staff Engineer. Reading time for *this* file: ~10 min. The linked docs are the depth.

---

## What you're looking at

KRAIL-BFF is a backend-for-frontend in Kotlin/Ktor that sits between the KRAIL Sydney transit app (KMP, Android + iOS) and NSW Open Data. The whole point: **get the NSW API key off the app binary and centralise control**, with security and cost predictability as hard constraints (indie scale, A$15/mo target).

As of now there are **7 open PRs** that together implement Phase 0 of the modernization plan: security foundation, abuse protection, deploy spec, stops dataset distribution, and pass-through endpoints for departures / park & ride / GTFS-RT.

The codebase is intentionally small — a single Ktor app, no DB, in-memory state, deployed as one container. Complexity is in the *defence-in-depth layering*, not in the topology.

---

## Read in this order

These four docs in order build the mental model:

1. **[docs/reference/MODERNIZATION_PLAN.md](docs/reference/MODERNIZATION_PLAN.md)** — *The "why"*. Indie-scale plan: security priorities, deploy choice (Cloudflare → DO App Platform), cost cap, NSW key migration rollout, what's deferred and why. **Read first** — everything else lives under this plan.
2. **[docs/reference/SCREEN_DATA_INVENTORY.md](docs/reference/SCREEN_DATA_INVENTORY.md)** — *The "what"*. What each KRAIL screen displays today, mapped field-by-field to the NSW source. This is the input to the API design.
3. **[docs/reference/API_SCHEMA_DESIGN.md](docs/reference/API_SCHEMA_DESIGN.md)** — *The "how (data shape)"*. Proposed proto schema with shared core types (`TransitLine`, `StopRef`, `StopTime`, …) and the KMP sharing strategy. **Has 6 open questions in §6 — answer those before next-phase implementation.**
4. **[docs/reference/BFF_ADOPTION_GUIDE.md](docs/reference/BFF_ADOPTION_GUIDE.md)** — *The "how (rollout)"*. Per-feature migration playbook with cohort steps and rollback procedure.

Then operational docs as needed:

- **[docs/reference/DEPLOYMENT.md](docs/reference/DEPLOYMENT.md)** — DO + Cloudflare provisioning, secret rotation, troubleshooting, pre-deploy checklist.
- **[docs/reference/LOCAL_TESTING.md](docs/reference/LOCAL_TESTING.md)** — running the BFF on macOS, pointing Android/iOS sim at it, smoke tests, edge cases.
- **[docs/reference/KRAIL_INTEGRATION.md](docs/reference/KRAIL_INTEGRATION.md)** — file-by-file changes inside the KRAIL repo to switch from NSW direct to BFF. Code snippets per service.

Existing docs that are still accurate:

- **[docs/reference/CONFIGURATION.md](docs/reference/CONFIGURATION.md)** — env-var reference (will need an update once Phase 0 PRs merge — version gate, daily budget, etc. aren't there yet).
- **[docs/guides/LOCAL_DEVELOPMENT.md](docs/guides/LOCAL_DEVELOPMENT.md)** — original local-dev guide. `LOCAL_TESTING.md` is the more recent / comprehensive version.

---

## Architecture at a glance

```
KRAIL app (KMP)
    │
    │  HTTPS, X-Krail-Version: <semver>
    ▼
Cloudflare (free tier)             ◄── DDoS, edge rate-limit, bot mitigation, cache
    │
    │  CF-Origin-Token: <secret>   (Transform Rule)
    ▼
DigitalOcean App Platform
  ┌────────────────────────────┐
  │ Ktor 3.4 / JDK 17 / Netty  │
  │ ┌──────────────────────┐   │
  │ │ Plugin pipeline      │   │   Order matters; see Application.kt
  │ │  Correlation         │   │
  │ │  MobileAnalytics     │   │
  │ │  ErrorHandling       │   │
  │ │  Monitoring          │   │
  │ │  Compression         │   │
  │ │  Serialization       │   │
  │ │  OriginTokenGate     │   │   opt-in: CF_ORIGIN_TOKEN
  │ │  VersionGate         │   │   opt-in: MIN_APP_VERSION (skip when 0.0.0)
  │ │  PerIpRateLimit      │   │   5 RPS / 10 burst (CF-Connecting-IP)
  │ │  HTTP (CORS+global)  │   │   50 RPS / 100 burst backstop
  │ └──────────────────────┘   │
  │ ┌──────────────────────┐   │
  │ │ Routes               │   │
  │ │  /api/v1/trip/plan*  │   │   trip planner (existing)
  │ │  /v1/stops/.../dep   │   │   departures (NEW PR #49)
  │ │  /v1/parking/...     │   │   park & ride (NEW PR #49)
  │ │  /v[12]/gtfs/...     │   │   GTFS-RT (NEW PR #50)
  │ │  /v1/data/stops/...  │   │   manifest redirect (NEW PR #48)
  │ │  /health, /ready     │   │
  │ └──────────────────────┘   │
  │ ┌──────────────────────┐   │
  │ │ NswClient            │   │   one HTTP client → NSW
  │ │  + circuit breaker   │   │
  │ │  + daily budget cap  │   │   10k/day, Sydney midnight reset
  │ └──────────────────────┘   │
  └────────────────────────────┘
    │
    │  Authorization: apikey <NSW key — env var only, never on device>
    ▼
NSW Open Data Hub
```

---

## Key decisions and why

These are the load-bearing calls. Push back on any of them before further building.

| Decision | Rationale | Alternatives considered |
|---|---|---|
| **Cloudflare → DO App Platform Basic-XXS** (A$8/mo) | Real spending predictability + edge DDoS protection without paying for it | Fly.io (real kill-switch but newer), AWS Fargate (10× cost) |
| **No login / no JWT** — open + rate-limited | Server-side entitlement enforcement is overkill at indie scale; client-side gate via Play/App Store entitlement | JWT with receipt verification (deferred until first abuse) |
| **Per-IP rate limit primary, global as backstop** | One hostile IP can't burn the global; global stops cross-IP floods | Single global limiter (footgun for legit users) |
| **Daily NSW budget capped server-side** (10k/day) | NSW quota is shared; one BFF instance shouldn't be able to burn it all | Token-bucket only (doesn't cap absolute usage) |
| **Origin token + DO firewall to Cloudflare IPs** | Direct hits to origin IP bypass Cloudflare otherwise | mTLS (Authenticated Origin Pulls — stronger, deferred) |
| **Version gate opt-in via env** (default 0.0.0 = disabled) | Tests and dev don't need the header; production sets explicitly | Always-on (would break existing tests on every PR) |
| **Stops search stays local in app** | Per-keystroke search shouldn't be a network call | BFF search endpoint (rejected — too noisy) |
| **Stops dataset via GitHub Releases + 302 redirect** | Free CDN, unlimited bandwidth, versioned, zero BFF compute on data path | Serve from BFF (extra cost), serve from S3 (paid) |
| **Pass-through (raw bytes/JSON) for v1 endpoints** | Proves integration end-to-end fast; screen-shaping is a refactor | Screen-shape from day 1 (slower to ship) |
| **Stop ID namespacing `NSW:200060` from day 1** | Avoids future migration when adding city #2 | Raw IDs, namespace later (rejected — `sandook` migration cost) |
| **NSW key migration via parallel keys + feature flag rollout** | Existing app binaries already leak the key; can't pull it back, can only revoke after migration | Force-update everyone immediately (worse UX) |
| **Public repo** | Free CI minutes, free Releases bandwidth, signals trust; nothing in repo is sensitive | Private (rejected — costs more, gains nothing meaningful) |

---

## PR review queue

7 open PRs. Stack order is deliberate — review top-down so each one's diff makes sense in isolation.

### Phase 0 stack (review in order)

| PR | Branch | What it adds | Time |
|---|---|---|---|
| **[#45](https://github.com/ksharma-xyz/KRAIL-BFF/pull/45)** | `security-foundation` | CORS allowlist, Compression, Dockerfile, PR CI workflow, log hygiene, drive-by fix to NSW error counter | 15 min |
| **[#46](https://github.com/ksharma-xyz/KRAIL-BFF/pull/46)** | `abuse-protection` | Per-IP + global rate limit (TokenBucket extracted to util), version gate, daily NSW budget, typed NSW exceptions, structured input validation | 30 min |
| **[#47](https://github.com/ksharma-xyz/KRAIL-BFF/pull/47)** | `deploy-mvp` | `.do/app.yaml`, `OriginTokenGate` plugin, full deployment runbook | 20 min |
| **[#48](https://github.com/ksharma-xyz/KRAIL-BFF/pull/48)** | `stops-dataset` | Proto schema, `BuildStopsDataset` tool, gradle task, weekly GitHub Actions workflow, manifest redirect endpoint | 25 min |
| **[#49](https://github.com/ksharma-xyz/KRAIL-BFF/pull/49)** | `departures-endpoint` | Pass-through routes for departures + park & ride (list + per-facility availability) | 10 min |
| **[#50](https://github.com/ksharma-xyz/KRAIL-BFF/pull/50)** | `gtfs-realtime-endpoints` | Pass-through routes for GTFS-RT trip updates (v1 + v2) and vehicle positions | 10 min |

### Parallel docs PR

| PR | Branch | What it adds | Time |
|---|---|---|---|
| **[#51](https://github.com/ksharma-xyz/KRAIL-BFF/pull/51)** | `docs/local-testing-and-integration` | `LOCAL_TESTING.md` + `KRAIL_INTEGRATION.md` | 15 min |

Total review time, end-to-end: **~2 hours** for thorough; ~45 min for skim.

### Suggested merge order
`#45 → #46 → #47 → #48 → #49 → #50 → #51` (or merge #51 anytime, it's independent).

---

## Staff-engineer review checklist

Use this as you walk through the PRs. Tick what you've verified.

### Architecture & abstractions

- [ ] **Plugin order in `Application.kt`** — does the registration order match the request lifecycle? (Auth checks before rate limit; rate limit before route handler; CORS at HTTP layer.) — see `server/src/main/kotlin/app/krail/bff/Application.kt`
- [ ] **`TokenBucket` extracted to `util/`** — used by both global limiter and per-IP. Single source of truth or accidental duplication?
- [ ] **`NswException` sealed hierarchy** — `NswUpstreamException` / `NswBudgetExceededException` mapped via `StatusPages` to specific responses. Does this leak any upstream details? (Should not — only `responseBody` is logged server-side, not echoed.)
- [ ] **`DI.kt`** — `NswDailyBudget` registered as a single Koin bean. Does anything else hold a separate counter that could drift?
- [ ] **`Version` semver parser** — handles `1.5.0-beta+build.123`? (Should — strips suffix/meta.) See `server/src/main/kotlin/app/krail/bff/util/Version.kt`

### Safety: defaults

- [ ] **CORS empty by default** — locks down cross-origin in production unless explicitly opened. (`BFF_CORS_ORIGINS` env)
- [ ] **`OriginTokenGate` opt-in** — disabled when `CF_ORIGIN_TOKEN` unset. Production must set it.
- [ ] **`VersionGate` opt-in** — disabled when `MIN_APP_VERSION` is `0.0.0`. Production should set a real floor.
- [ ] **Daily NSW budget enabled at 10k by default** — fails closed before the user notices.
- [ ] **Per-IP limit at 5 RPS / 10 burst** — should accommodate a normal user; will not crush them.
- [ ] **No emoji / decorative log lines on hot paths** that might indicate sloppy production logging — there are some `🚀 / 📋 /` etc in `NswClient.kt` from earlier code; flag if they bother you (not introduced by this stack but kept).

### Failure modes

- [ ] **NSW upstream 4xx/5xx** → 502 `upstream_error` to client (no body leak); per-endpoint counter incremented; circuit breaker may trip on health endpoint. ✅
- [ ] **Daily budget exhausted** → 503 `service_temporarily_limited` with `Retry-After: 3600`. ✅
- [ ] **Per-IP limit hit** → 429 with `Retry-After: 1`. ✅
- [ ] **NSW timeout** (5s default) → propagates to outer catch → 500 `internal_error`. ✅
- [ ] **Version gate header malformed** → 400 `invalid_version` (not 426). ✅
- [ ] **Origin token mismatch** → 403 `forbidden`. ✅
- [ ] **What if Cloudflare's Transform Rule fails** and we never get the `CF-Origin-Token` header — does the BFF break legitimately? Yes, it'd 403 everyone. Is that the right failure mode? (I'd argue yes — fail closed.)

### Operational story

- [ ] **`DEPLOYMENT.md` runbook is complete** for first-time DO + Cloudflare setup, secret rotation, smoke tests, troubleshooting matrix.
- [ ] **`pr.yml` workflow** runs on every PR, blocks merges on test failure (assuming the branch ruleset on `main` requires it as a status check).
- [ ] **Branch ruleset on `main`** — direct push rejected, PRs only. Already verified — that's why we're using Graphite.
- [ ] **Stops dataset workflow** uses `NSW_API_KEY` repo secret, runs weekly + manual dispatch, publishes to GitHub Release. Workflow is testable via `workflow_dispatch` after merge.
- [ ] **Cost cap** A$8/mo flat (DO Basic-XXS) + A$0 (Cloudflare free) = **A$8/mo**. Bandwidth abuse bounded by Cloudflare.

### Public-repo discipline

- [ ] **Secrets**: `NSW_API_KEY`, `CF_ORIGIN_TOKEN`, `JWT_SIGNING_KEY` (future) — only in env vars / DO secrets / GitHub Actions secrets. Verified in `.gitignore` and runbook.
- [ ] **API key prefix logging** — fully redacted in #45 (was leaking 8 chars before). ✅
- [ ] **Test fixtures** — synthetic only, no captured real responses with stop data that could hint at usage patterns.
- [ ] **`.gitignore`** covers `.env`, `*.pem`, `*.p12`, `*.key`, `*.keystore`, `*.jks`, `.DS_Store`. ✅

### Observability

- [ ] **Per-endpoint metrics** — `nsw.trip.*`, `nsw.departures.*`, `nsw.carpark.*`, `nsw.gtfsrt.*`, `nsw.health.*`. Dropwizard registry; emitted to slf4j every 10s. (Future: Micrometer + Prometheus.)
- [ ] **Correlation ID** — propagated as `X-Request-Id`, in MDC, in JSON logs. Useful for trace.
- [ ] **No PII / device IDs in logs.** Verified — `MobileAnalyticsTest.kt` covers this.

### Migration soundness (NSW key)

- [ ] Does the rollout sequence in `MODERNIZATION_PLAN.md` actually work? Two parallel keys → feature-flag rollout → force-update → delete old key. Sound logic; depends on NSW allowing two keys per account.
- [ ] Is there a *server-side* lever to drop traffic from old app versions if needed? Yes — `MIN_APP_VERSION` env var, change without redeploying clients.
- [ ] Can rollout be reverted in <5 min? Yes — Firebase Remote Config flag flip; fetch interval should be ≤ 5 min for the BFF flags.

### Multi-city extensibility (deferred, but seam exists)

- [ ] Stop IDs namespaced `NSW:200060` from day 1 — verified in `BuildStopsDataset.kt` and design docs.
- [ ] No other place has "NSW" baked into field names that I missed? (Field names like `bff.cors.origins` are fine; only the *data* shape needs to be city-agnostic.)

### Tests

- [ ] **17 tests passing** locally; green on PR CI.
- [ ] Test coverage is reasonable for the core paths but **light on the new plugins** (per-IP limiter sweep eviction, version gate, origin token gate, daily budget reset). Tag for follow-up if this matters to you.
- [ ] No flaky tests; all use `MockEngine`.

---

## Open questions worth deciding before next phase

These are in `API_SCHEMA_DESIGN.md` §6 but worth seeing here too.

1. **Stop ID namespacing** — `"NSW:200060"` (chose this) vs raw. **Done — namespaced.**
2. **`krail-api-proto` repo** — separate public repo + git submodule, or keep proto inline in `KRAIL-BFF/server/src/main/proto/` and copy to KRAIL? Recommended: separate repo when a 2nd consumer (KRAIL) starts importing. **Pending.**
3. **Pre-formatted display strings on the wire** — keep both ISO UTC and `display_time`, or trust client to format from UTC? Currently both. **Recommended: keep both for v1; cheap bytes, flexible client.**
4. **`MapBundle` inside `JourneyResponse`** — bundle vs separate fetch on map open. **Pending.**
5. **Timezone field on responses** — always-AEST now; add `timezone` field for multi-city later. **Pending.**
6. **Versioning of the proto package** — SemVer at package level. **Recommended: SemVer at package level for indie scale.**

---

## How to run / smoke-test (5 min)

If you want to actually run it before reviewing code:

```bash
cd /Users/ksharma/code/apps/KRAIL-BFF
git checkout gtfs-realtime-endpoints   # tip of the stack
cp local.properties.template local.properties
# edit local.properties: set nsw.apiKey=<your NSW key>

./gradlew :server:run

# in another terminal
curl -fsS http://localhost:8080/health
curl -fsS "http://localhost:8080/v1/stops/200060/departures" | jq '.stopEvents[0]'
curl -fsS "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328" | jq '.journeys | length'
```

Detailed walkthrough in **[LOCAL_TESTING.md](docs/reference/LOCAL_TESTING.md)** — including pointing the Android emulator / iOS simulator at the local BFF.

---

## TODO — your review

In the order I'd tackle:

1. [ ] Skim this file (you're here).
2. [ ] Read **[MODERNIZATION_PLAN.md](docs/reference/MODERNIZATION_PLAN.md)** — confirm the framing still matches your intent (indie scale, A$8/mo, defer-everything-non-essential).
3. [ ] Review **PR #45 (`security-foundation`)** — Dockerfile, CI, CORS, log hygiene. Easiest entry; sets the pattern.
4. [ ] Review **PR #46 (`abuse-protection`)** — biggest design surface (rate limiter, budget, version gate, exceptions, validation). The architecture review really happens here.
5. [ ] Review **PR #47 (`deploy-mvp`)** — `.do/app.yaml`, origin token gate, runbook. Verify the deploy story matches your ops style.
6. [ ] Review **PR #48 (`stops-dataset`)** — proto, build tool, workflow. Most novel piece; verify the dataset pipeline is sane.
7. [ ] Review **PRs #49 + #50** — pass-through endpoints. Mostly mechanical.
8. [ ] Read **[KRAIL_INTEGRATION.md](docs/reference/KRAIL_INTEGRATION.md)** §5 — walk through the per-feature changes against the actual KRAIL files; flag any wrong path / wrong package.
9. [ ] Read **[LOCAL_TESTING.md](docs/reference/LOCAL_TESTING.md)** — verify the curl commands work on your machine; this is the morning of the integration test.
10. [ ] Walk through the **review checklist** above. Each unchecked item is a follow-up item.
11. [ ] Decide on the **6 open questions** in the API schema design. None are blockers for current PRs but they shape the next phase.
12. [ ] Merge PRs in order. After each, `gt sync` (Graphite) to clean up.
13. [ ] (Optional) Run the BFF locally and point your Android emulator at it. Confirms the smoke story before going further.

---

## Quick reference

| Need to | Look at |
|---|---|
| Understand why we're doing this | [MODERNIZATION_PLAN.md](docs/reference/MODERNIZATION_PLAN.md) |
| See what each KRAIL screen needs | [SCREEN_DATA_INVENTORY.md](docs/reference/SCREEN_DATA_INVENTORY.md) |
| Understand the proto contract | [API_SCHEMA_DESIGN.md](docs/reference/API_SCHEMA_DESIGN.md) |
| Migrate a KRAIL feature | [KRAIL_INTEGRATION.md](docs/reference/KRAIL_INTEGRATION.md) |
| Roll out a feature flag | [BFF_ADOPTION_GUIDE.md](docs/reference/BFF_ADOPTION_GUIDE.md) |
| Provision DO + Cloudflare | [DEPLOYMENT.md](docs/reference/DEPLOYMENT.md) |
| Test locally | [LOCAL_TESTING.md](docs/reference/LOCAL_TESTING.md) |
| Tune env vars | [CONFIGURATION.md](docs/reference/CONFIGURATION.md) |
| Find a specific endpoint's code | `server/src/main/kotlin/app/krail/bff/routes/` |
| Find a defence layer | `server/src/main/kotlin/app/krail/bff/plugins/` |
| Find NSW client logic | `server/src/main/kotlin/app/krail/bff/client/nsw/NswClient.kt` |
