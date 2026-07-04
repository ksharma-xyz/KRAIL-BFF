# TODO — live work queue

> The one planning file. Everything predating 2026-07-04 (PLAN / START / STATUS /
> old TODO) is archived under [`docs/archive/`](docs/archive/) — history, not truth.

## 🚀 Deploy day — first production deploy (nothing is deployed yet)

> No DO account exists as of 2026-07-04. `deploy_on_push` in `.do/app.yaml` is
> inert until the app is created, so pushes to main deploy nothing. Detailed
> runbook: [`docs/guides/FIRST_DEPLOY.md`](docs/guides/FIRST_DEPLOY.md) — the
> list below is the order of operations.

- [ ] **NSW keys** — create a fresh BFF-only production key at
      opendata.transport.nsw.gov.au (never reuse the app's key), plus an
      optional second CI-only key for the drift workflow.
- [ ] **DigitalOcean** — create account, set a billing alert (~A$10) first;
      then `doctl apps create --spec .do/app.yaml`; set encrypted secrets
      `NSW_API_KEY` and `CF_ORIGIN_TOKEN` in the app console.
- [ ] **Cloudflare** — move krail.app DNS to Cloudflare (GitHub Pages website
      must keep working — FIRST_DEPLOY.md §2); `bff.krail.app` → DO app
      (proxied); Transform Rule adding `CF-Origin-Token: <same value as the DO
      secret>`. Note: App Platform has NO IP firewall — the token gate IS the
      origin lockdown (direct origin hits get 403).
- [ ] **Verify** — `curl https://bff.krail.app/health` shows
      `{"status":"up",...}`; one trip plan through Cloudflare returns journeys.
- [ ] **Arm monitoring** (disabled 2026-07-04 because prod didn't exist):
      `gh workflow enable post-deploy-smoke.yml && gh workflow enable synthetic.yml`;
      add `NSW_API_KEY_CI` repo secret; enable secret scanning + push
      protection (repo Settings → Code security); enable DO alerts
      (deploy failed / CPU / memory).
- [ ] **App switchover** — point the KRAIL app at the BFF per
      `docs/reference/BFF_ADOPTION_GUIDE.md` (own rollout, own day).

## Now: pipeline automation ("know about stuff without watching")

All alerts land as GitHub issues → GitHub mobile push. Build order:

- [ ] **1. Post-deploy smoke test** — `.github/workflows/post-deploy-smoke.yml`.
      On push to main: poll prod `/health` until `startedAt` is newer than the
      commit, then smoke-call trip plan / departures / GTFS-RT through Cloudflare.
      Fail → auto-open issue. (`/health` already returns `version` + `startedAt`.)
- [ ] **2. Synthetic monitor** — cron every 30 min: prod `/health` + one cheap
      endpoint; two consecutive fails → issue (update the existing issue, don't spam).
      Also scrape `/internal/metrics`, alert at 80% NSW daily budget.
- [ ] **3. NSW contract drift** — nightly workflow, fixed trip/stop_finder/departure
      queries, `jq` assertions on the fields the mappers rely on.
      **Needs:** `NSW_API_KEY_CI` repo secret (create a second, CI-only key).
- [ ] **4. GTFS dataset freshness** — weekly: `track-latest` release in KRAIL-GTFS
      older than 9 days → issue.
- [ ] **5. gitleaks job in pr.yml** (repo settings side: enable secret scanning +
      push protection — manual, Settings → Code security).
- [ ] **6. DO console alerts** — deploy failed / CPU / memory (manual, DO dashboard).

Done already (2026-07-04): `/internal/metrics` (env-gated, on in prod spec),
`/health` version+startedAt, security hardening pass, SECURITY.md, API tester
rebuild (KRAIL Dispatch), docs consolidation.

## Next (unordered backlog)

From the 2026-07 review ([docs/archive/CODE_REVIEW_2026-07.md](docs/archive/CODE_REVIEW_2026-07.md)):

- [ ] Parking fan-out concurrency cap — Semaphore(4) in
      `ParkingRoutes.fetchAllFacilities` (today: up to ~60 parallel NSW calls
      vs their 5 req/s limit)
- [ ] Kill the Slf4jReporter gauge spam in `Monitoring.kt` (logs every JVM
      gauge at INFO every 10s; `/internal/metrics` replaces it)
- [ ] Parking JSON: emit `upstream_malformed` error instead of silently
      dropping facilities whose NSW body fails to parse
- [ ] Cloudflare edge-cache rules on deploy day: `/v*/gtfs/*` 15–30s,
      `/v1/parking/facilities` 60s (biggest NSW-quota lever, zero code)
- [ ] Remove demo route `GET /json/kotlinx-serialization` (Serialization.kt)
- [ ] `/ready` cache vars → AtomicLong/AtomicBoolean (visibility nit)
- [ ] Filter `/health` out of CallLogging noise

- [ ] Exact build SHA in prod `/health` (needs `.git` in Docker context + git in
      builder image; DO strips it today — smoke test uses `startedAt` instead)
- [ ] SHA-pin GitHub Actions; digest-pin Docker base images
- [ ] Server-side GTFS-RT matching (ship matched vehicle only) — see
      `docs/reference/API_SCHEMA_DESIGN.md` §2.5b
- [ ] Multi-city provider boundary — only when city #2 is real
      (`NswClient` behind a `TransitProvider` interface)

## Standing rules

- Cost cap ~A$15/mo total; provider-enforced caps only.
- Deploy shape locked: Cloudflare → DO App Platform Basic, `instance_count: 1`.
  All rate-limit/budget state is in-memory — see SECURITY.md before scaling out.
