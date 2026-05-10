# KRAIL-BFF · Status & next actions

> Where we are, what's blocking, what to do next. Refresh by running
> `gh pr list --state open` + `git log main --oneline -5`.

Last refresh: **2026-05-09**.

---

## TL;DR

The BFF is feature-complete for v1. Nothing is on `main` past PR #45.
**13 PRs are stacked waiting for review/merge** — until those land, you
cannot deploy and you cannot integrate from the app.

You are blocked on yourself: review + merge the stack, then deploy.

---

## What's on `main`

- `#44` Modernization plan, schema design, adoption guide, docs cleanup
- `#45` Security foundation: CORS, Compression, Dockerfile, PR CI

That's it. None of the actual endpoints, no rate limit, no deploy spec.

---

## What's stacked, waiting (in merge order)

```
main
 └─ #46 abuse-protection            per-IP rate limit, daily NSW budget, version gate
     └─ #47 deploy-mvp              .do/app.yaml, OriginTokenGate, DEPLOYMENT.md
         └─ #48 stops-dataset       proto, manifest endpoint, weekly build workflow
             └─ #49 departures-endpoint     /departures + park & ride
                 └─ #50 gtfs-realtime       /gtfs/realtime/* + /gtfs/vehiclepos/*
                     └─ #52 routes-dataset  routes proto + manifest + combined workflow
                         └─ #53 plugin-tests   tests for VersionGate / OriginTokenGate / etc.
                             └─ #54 dev-tooling   CONFIGURATION refresh + CodeQL + Dependabot
                                 └─ #55 api-tester-pro     metrics + repeated runs
                                     └─ #56 bruno-collection
                                         └─ #57 testing-playbook
                                             └─ #61 dashboard          (current branch)
main
 └─ #51 docs/local-testing-and-integration   parallel; not blocked by the security stack
```

The Graphite stack means each PR's diff is small and reviews cleanly on
top of its parent. **Don't squash-merge out of order** — that breaks the
chain. Either click "merge" top-down, or use Graphite's stack-merge.

---

## What you need to action — in order

### 0. Right now: review + merge the stack

- Skim each PR's description (you wrote them, you'll recognise the work).
- Merge `#46` first, then walk down the chain. Graphite handles rebase.
- After each merge, check Actions on `main` — the PR CI workflow runs.
- `#51` (docs) is independent; merge it whenever.

**Action:** `gh pr view 46 --web` → review → merge. Repeat 47, 48, ...

### 1. Deploy to DigitalOcean

Follow `docs/reference/DEPLOYMENT.md` — the exact runbook is there.
Short version:

```bash
# Once the stack is merged:
doctl apps create --spec .do/app.yaml
# In DO console (or via doctl --env):
#   NSW_API_KEY        = <your BFF-only NSW key>
#   CF_ORIGIN_TOKEN    = <random 32+ char shared secret>
#   BFF_DEV_PASSTHROUGH = false   ← MUST stay false in prod
#   MIN_APP_VERSION    = 0.0.0    ← bump once you're shipping versioned clients
#   STOPS_MANIFEST_URL / ROUTES_MANIFEST_URL = GitHub Releases asset URLs
```

Then put Cloudflare in front, configured to send `CF-Origin-Token` as a
Transform Rule. Lock the DO firewall to Cloudflare's published IP ranges
so the bare DO URL is unreachable from the public internet.

Smoke test: `curl https://bff.krail.app/health` → 200.

### 2. Create `krail-api-proto` repo ✅ DONE 2026-05-09

Public repo at <https://github.com/ksharma-xyz/KRAIL-API-PROTO>, Apache 2.0.
Tag `v0.1.0` cut. The BFF now consumes the protos via git submodule at
`krail-api-proto/` (this repo's root), Wire's `sourcePath` points at it.
KRAIL app does the same submodule on its side per
[`docs/handover/MIGRATION_GUIDE.md`](docs/handover/MIGRATION_GUIDE.md) section 4 (Phase C).

CI: `proto-bump.yml` workflow runs daily and opens a PR when the proto
repo cuts a new tag. See
[`docs/handover/archive/PROTO_REPO_MIGRATION.md`](docs/handover/archive/PROTO_REPO_MIGRATION.md)
for the full migration writeup.

### 3. KRAIL app side — base URL + headers

In KRAIL's HTTP client config:

- Add `BFF_BASE_URL` to `BuildConfig`, sourced from `local.properties`.
  Debug points at your local BFF (`http://10.0.2.2:8080` for emulator,
  `http://<your-ip>:8080` for device); release points at
  `https://bff.krail.app`.
- Send `X-Krail-Version: <BuildConfig.VERSION_NAME>` on every BFF request.
  Add it once in the Ktor client config — not per-call. **The BFF
  rejects requests missing this header.**
- Optional, if you want the same origin lockdown for clients: add
  `CF-Origin-Token` header. Otherwise skip it for now (the BFF only
  enforces it when the env var is non-empty, so dev still works).

### 4. Firebase Remote Config flags

Add these (default `false` for the per-endpoint ones):

- `bff_kill_switch` — panic button, forces every endpoint to NSW direct.
- `bff_use_for_stops` — manifest dataset
- `bff_use_for_departures`
- `bff_use_for_park_ride`
- `bff_use_for_trip_results`
- `bff_use_for_track` — GTFS-RT for live tracking

RC fetch interval: ≤ 5 min (matches the park-ride cooldown pattern KRAIL
already uses).

### 5. First migration: stops dataset (easiest)

- Per memory: stops search **stays local** in the app — the BFF only
  distributes the versioned `.pb` via `/v1/data/stops/manifest`.
- App startup checks the manifest, downloads if version differs, caches.
- No mid-screen toggle, no compare-mode needed. This is a confidence-builder
  before touching live screens.
- Estimate: 1–2 evenings, mostly plumbing.

### 6. Migration order after that

| Endpoint | KRAIL screen | Notes |
|---|---|---|
| Stops dataset | Search | Done first, no UI risk |
| Departures | Home (SavedTripsScreen) | Visible — use compare-mode in dev |
| Park & Ride | Home (gated `NSW_PARK_RIDE_BETA`) | Already gated, easy to flag-flip |
| Trip results | TimeTableScreen | Has proto endpoint — measure wire-size win |
| GTFS-RT (track) | TrackTripScreen + JourneyMapScreen | Built-but-hidden in app; release with BFF in place |

Per `BFF_ADOPTION_GUIDE.md`: 0 → 10% → 50% → 100% → 2-week grace → delete
NSW path. Don't skip the cohort steps.

### 7. Rotate the embedded NSW API key

After the last endpoint is at 100% for 2+ weeks: **delete the in-app NSW
API key** from NSW Open Data, leaving only the BFF's server-side key.
This is the security goal of the whole project — the app shouldn't carry
NSW credentials at all.

---

## What's NOT pending (already handled)

- BFF endpoint code — all 8 endpoints are written and tested.
- Local dev tooling — `./scripts/dev.sh up` brings up BFF + dashboard.
- API tester / dashboard — http://localhost:8000/api-tester.html with
  Postman entries, highlights, JSON tree, BFF↔NSW compare mode.
- Bruno collection — `docs/tools/bruno/` for those who prefer it over the dashboard.
- CI — PR workflow runs tests + Detekt on every PR, CodeQL on schedule.
- Deploy spec + runbook — `.do/app.yaml` and `DEPLOYMENT.md` are ready
  to use, just not executed yet.

---

## How to refresh this doc

```bash
gh pr list --state open --json number,title,headRefName | \
  python3 -c 'import sys,json; [print(f"#{p[\"number\"]} {p[\"title\"]}") for p in json.load(sys.stdin)]'
```

Update the "Last refresh" date and the stack diagram. If the stack is
empty, this whole doc can shrink to "deployed, integrating endpoints".
