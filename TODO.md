# TODO — live work queue

> The one planning file. Everything predating 2026-07-04 (PLAN / START / STATUS /
> old TODO) is archived under [`docs/archive/`](docs/archive/) — history, not truth.

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

- [ ] Fix `/ready` upstream probe — `NswClient.healthCheck()` GETs the bare NSW
      root unauthenticated and reports down, so /ready is permanently "degraded".
      (Hidden until 2026-07-04: the KHealth plugin was shadowing /health + /ready
      with empty `{}` responses; now removed.) Probe a cheap authenticated
      endpoint instead.

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
