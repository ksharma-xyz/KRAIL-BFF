# KRAIL-BFF — Staff/Principal Code Review · 2026-07-04

Point-in-time review of the full codebase (routes, plugins, NSW client, track
subsystem, CI, deploy spec, docs) ahead of the first production deploy.
Bugs marked **FIXED** were fixed and verified the same day.

## Verdict

**Deploy-ready.** The codebase is unusually disciplined for a solo project:
clean layering, defense-in-depth, injected clocks, contract tests against real
fixtures, and cost-awareness designed in rather than bolted on. The bugs found
were all in the "invisible plumbing" category — health probes, response
headers, shadowed routes — exactly the things that never show up in feature
testing and always show up in production debugging.

Grades: architecture **A−** · correctness **B+ → A−** (after today's fixes) ·
security **A−** (post-hardening) · operability **B → A−** (metrics + monitors
added) · docs **A** (post-consolidation).

## Bugs found in this review

| # | Severity | Bug | Status |
|---|---|---|---|
| 1 | High | **KHealth plugin shadowed `/health` + `/ready`** with an empty `{}` response since it was installed. Administration's handlers (incl. the NSW readiness check) never executed. Consequence: `/health` carried no fields, `/ready` never actually probed NSW. | **FIXED** — plugin + dependency removed |
| 2 | High | **`X-Request-Id` never reached clients.** `Correlation.kt` appended the header in `finally` after `proceed()` — the response is committed by then and the append is silently dropped. Every client-side correlation attempt to date had nothing to correlate with. | **FIXED** — append moved before `proceed()`; verified via curl |
| 3 | Medium | **`/ready` upstream probe was meaningless.** `healthCheck()` GET the NSW root unauthenticated; NSW answers **500** to that, so once bug #1 was fixed, `/ready` reported permanently degraded. | **FIXED** — authenticated probe of `/v1/carpark` (checks reachability + key validity); 30s cache in Administration |
| 4 | Medium | **`mapOf()` responses serialize as `{}`** under kotlinx ContentNegotiation (`/health`, `/ready`, and the leftover `/json/kotlinx-serialization` demo route). | **FIXED** for health/ready (explicit JSON); demo route: remove (below) |
| 5 | Low | `PerIpRateLimit` sweep had identical if/else branches — `maxIps` was dead config, map could grow unbounded under spoofed-IP flood. | **FIXED** (hard cap + clear) |
| 6 | Low | `dependabot.yml` duplicate `open-pull-requests-limit` key. | **FIXED** |

## Open findings (not yet fixed — ranked)

1. **Parking fan-out has no concurrency cap** — `ParkingRoutes.fetchAllFacilities`
   launches one `async` per unique facility: 20 stops × ~3 facilities → up to
   ~60 simultaneous NSW calls against a 5 req/s upstream limit. Real risk of
   429 bursts on large batches. Fix: `Semaphore(4)` around `fetchOne`, ~6 lines.
2. **Metrics gauge spam in logs** — `Monitoring.kt` starts a `Slf4jReporter`
   that logs *every* JVM gauge at INFO **every 10 seconds**. On DO that's
   thousands of junk lines/hour drowning real signal (and log volume costs
   attention if not money). Now that `/internal/metrics` exists, delete the
   reporter or raise it to a 10-minute DEBUG.
3. **Parking JSON silently drops unparseable facility bodies** — an `Ok` result
   whose body fails JSON parsing lands in neither `facilities` nor `errors`;
   the facility just vanishes from the response. Should emit an
   `upstream_malformed` error entry.
4. **GTFS-RT pass-through buffers whole feeds in memory** — `response.body<ByteArray>()`
   per request; the buses feed runs to several MB. Fine at current traffic on
   basic-xxs, but the cheap win is a 15–30s Cloudflare edge-cache rule on
   `/v*/gtfs/*` — collapses N clients to ~1 origin hit and shrinks the NSW
   budget draw (ROADMAP §2a already points this way).
5. **`/ready` cache fields lack memory-visibility guarantees** — `cachedOk`/
   `cachedAtMs` are plain vars read outside the mutex across Netty threads.
   Worst case is a redundant probe or a marginally stale answer — benign, but
   `AtomicLong`/`AtomicBoolean` would make it correct on paper.
6. **Leftover demo route** — `Serialization.kt` registers
   `GET /json/kotlinx-serialization` in production routing (and it returns `{}`
   per bug #4). Delete the route; keep the plugin install.
7. **OkHttp client defaults** — no retry policy, 10s request timeout shared by
   all endpoint classes. Trip planning occasionally runs long at NSW; consider
   per-call timeout for `/v1/tp/trip` (15s) once real latency data arrives via
   `/internal/metrics` p95s.

## Efficiency opportunities (principal-level, in leverage order)

1. **Cloudflare edge caching** is the single biggest lever in the whole system:
   GTFS-RT (15–30s TTL), carpark list (60s), stops manifest redirect (already
   cached). It multiplies the NSW quota by the user count, costs $0, and needs
   no BFF code — just CF page rules on deploy day.
2. **Short-TTL departures caching** (~15s per stop, in-process) — departure
   boards are the classic thundering-herd endpoint once saved-trips polling
   ships. `FeedCache`'s single-flight pattern is already the right template.
3. **Log budget** — finding #2 above, plus `CallLogging` at INFO logs every
   probe hit; consider filtering `/health` out. Logs are the only thing that
   scales linearly with traffic on this box.
4. Trip proto path does JSON→typed→proto per request (~55KB parse). Fine now;
   if it ever shows in p95, the fix is caching by (origin,dest,minute-bucket),
   not parser tuning.

## What's genuinely good (keep doing these)

- **Pass-through over typed models** for JSON endpoints — robust by
  construction against NSW schema growth; the 200-field lesson was learned once
  and encoded as principle.
- **Single-flight + serve-stale FeedCache** — the quota math
  (`feeds × 86400/ttl`, traffic-independent) is exactly the right invariant for
  a hard-capped upstream.
- **TripStopMemory** — bounded LRU + TTL, `@Synchronized` honesty at this
  scale, and the "enhancement, never correctness dependency" restart principle
  documented in-code.
- **Injected clocks everywhere** time matters; fixture-pinned tests that won't
  rot.
- **Defense-in-depth request path** (CF → token gate → version gate → per-IP →
  global → validation → budget/breaker) with each layer independently testable
  — and now documented in SECURITY.md with a pre-merge checklist.
- **Cost ceiling as an architectural constraint** — in-memory state matched to
  `instance_count: 1`, with the scale-out tripwire written down.

## Process notes

- Main ruleset allows direct pushes with only linear-history required —
  fine for solo velocity **if** the local `./gradlew :server:test` habit holds;
  adding pr.yml as a required status check would make the safety automatic.
- CI is now: build+test, CodeQL (security-extended), gitleaks, Dependabot
  security, Renovate, SHA-pinned actions, digest-pinned base images. That is a
  stronger supply chain than most funded teams run.
- Monitoring (smoke/synthetic/drift/freshness) exists but is parked until the
  DO app is created — arm it on deploy day (TODO.md has the switch-on list).

## Fixed-today changelog (for context)

Security hardening (7 items incl. `/ready` amplifier, IP-trust gating,
constant-time token compare), KHealth removal, real `/health` payload,
`/internal/metrics`, X-Request-Id delivery, config cascade helper, NSW query
dedupe, docs consolidation (37 → ~21 files), API tester rebuild, CI monitoring
suite, action/image pinning.
