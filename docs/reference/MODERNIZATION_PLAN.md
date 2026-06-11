# KRAIL-BFF Modernization Plan (Indie Edition)

> **📜 HISTORICAL (kept for context).** This was the original project
> plan; the work it describes is built — including the "deferred"
> server-side live tracking, which shipped 2026-06. Current truth:
> [PLAN.md](../../PLAN.md) (sequence), [TODO.md](../../TODO.md)
> (queue), [STATUS.md](../../STATUS.md) (state),
> [ROADMAP.md](ROADMAP.md) (future). Other docs cite sections here
> for rationale — that's why it stays.

> Right-sized for an indie developer: ~400 MAU on KRAIL, BFF open to all users with rate-limited abuse protection (no login, no per-user gating), tiny budget, **public repo**.

This plan deliberately defers everything that would be premature at this scale — Redis, multi-AZ, Aurora, server-side live tracking, GTFS pipeline rewrites — until traffic actually demands them. The current BFF is not stale; it's just unfinished. The job is to *finish it small and safe*, not rewrite it.

See also: [ROADMAP.md](ROADMAP.md) for the original PR breakdown. This plan reorders and de-scopes that work.

---

## Guiding constraints

1. **Security is the only non-negotiable.** Every other axis can wait.
2. **Public repo** — no secrets, no real hostnames, no example values that look real. All config via env vars or `.gitignored` files.
3. **One small container** — single hobby-tier PaaS instance until traffic forces otherwise.
4. **Open access with rate-limited abuse protection.** No login, no per-user identity. The BFF doesn't know or care who's paid. Paid feature gating happens client-side via Play / App Store entitlement checks. Volume is what's controlled, not identity.
5. **No new infra without justification** — in-memory cache before Redis, SQLite before Postgres, polling before SSE.
6. **No user data collected, processed, or stored.** No device IDs server-side, no PII in logs, no persistent state about who used what. Privacy by minimisation.

---

## State of the BFF today

| Area | Today | Plan |
|---|---|---|
| Stack | Kotlin 2.2.20 / Ktor 3.3.1 / JDK 17 | Keep |
| Endpoints | `/v1/tp/trip`, `/api/v1/trip/plan` (JSON + protobuf) | Add 2–3 more, screen-shaped |
| Auth | None | Open + rate-limited (no login) |
| CORS | `anyHost()` (dev only) | Env-driven allowlist |
| TLS | None | Cloudflare edge + DO origin |
| Cache | None | In-process (Caffeine) |
| Deploy | None (no Dockerfile, no CI) | Cloudflare → DO App Platform Basic |
| Tests | 513 LOC | Keep ratio as endpoints land |

NSW API key already lives in env vars, not the repo — keep doing that.

---

## Phase 0 — Security & deploy foundation (do this first)

Each item is bounded; collectively maybe 1–2 weeks of evening work.

### 0.1 Lock CORS
Replace `anyHost()` in `plugins/HTTP.kt` with an allowlist read from env (`BFF_CORS_ORIGINS`, comma-separated). Default empty in prod. Local dev uses `local.properties` with `http://localhost:*` style entries.

### 0.2 Dockerfile + CI
Multi-stage build:
- Stage 1: gradle JDK 21 → `./gradlew :server:installDist`
- Stage 2: `eclipse-temurin:21-jre-alpine` (or distroless), copy install dir, non-root user, `EXPOSE 8080`, healthcheck against `/health`.

GitHub Actions:
- `pr.yml` — build + test on every PR.
- `release.yml` — on push to `main`: build image, push to DO container registry or GHCR.

Final image should be ~150–200 MB. No secrets baked in — everything via env vars at runtime.

### 0.3 Hosting & edge

**Locked architecture:** `KRAIL app ─► Cloudflare (free) ─► DigitalOcean App Platform Basic ─► NSW API`

**Origin: DigitalOcean App Platform Basic** — A$8/mo always-on, fixed-price, Sydney `syd1` region, deploys this repo's Dockerfile. Chosen for mature track record, predictable pricing, and zero ops overhead.

**Edge: Cloudflare (free tier)** — non-negotiable. Without it the origin is exposed to bandwidth-overage abuse that DO won't auto-stop. With it, the DDoS / cost-spike attack surface basically disappears.

What Cloudflare in front buys (free tier):
- DDoS protection (L3/L4/L7) absorbed at Cloudflare's edge
- Per-IP rate limiting rules at the edge
- Origin IP hidden — attackers see Cloudflare's IPs, not the DO server
- Edge caching for the stops manifest + dataset endpoints (origin barely sees this traffic)
- Free TLS at the edge (origin still serves TLS too)

**Origin lockdown** — both must be done, otherwise Cloudflare is decorative:

1. **DO firewall — IP allowlist.** Allow inbound HTTPS only from Cloudflare's published IP ranges (`https://www.cloudflare.com/ips/`). Direct hits to the origin IP are dropped at the firewall. Add a monthly cron to refresh the IP list.
2. **`CF-Origin-Token` shared secret.** Generate once: `openssl rand -hex 32`. Set on Cloudflare as a Transform Rule that adds header `CF-Origin-Token: <value>` to every request forwarded to origin. Set the same value on DO as env var `CF_ORIGIN_TOKEN`. Ktor middleware checks for equality; rejects requests without it. Even if Cloudflare's IP range is somehow spoofed, the secret is needed.

**Deferred to docs only:** Authenticated Origin Pulls (mTLS). Stronger than the shared secret, more setup. Document the procedure but don't implement for v1.

**Secrets** — set via DO App Platform encrypted env vars (UI or `doctl`), never in repo:
```
NSW_API_KEY                 NSW Open Data API key
CF_ORIGIN_TOKEN             Random 32-byte hex; matches Cloudflare's Transform Rule value
BFF_CORS_ORIGINS            Comma-separated allowed origins
NSW_DAILY_BUDGET            Max NSW upstream calls per Sydney-day; e.g. 10000
BFF_PER_IP_RPS              Per-IP rate limit (req/sec); e.g. 5
BFF_PER_IP_BURST            Per-IP burst capacity; e.g. 10
MIN_APP_VERSION             Minimum supported KRAIL app version (semver), e.g. 1.5.0
```

**Cost ceiling:** A$8/mo (DO Basic) + A$0 (Cloudflare free) = **A$8/mo flat**. Bandwidth abuse is bounded by Cloudflare. Set DO billing alerts at 50% / 100% as a second-line check.

### 0.4 Auth — open + rate-limited (no login)

The BFF has no login, no JWT, no per-user identity. Anyone with the BFF URL can call it. Rate limits and budgets cap the damage.

Why this is the right call at indie scale:
- Server-side entitlement enforcement is overkill until user count justifies the complexity.
- Paid feature gating already happens client-side via Play / App Store entitlement checks. The server doesn't need to know who's paid.
- An attacker who decompiles the APK and finds the BFF URL can hit it; rate limits + daily budget bound the damage.
- No identity = no PII = no liability around user data.

Defence layers (cheapest to most aggressive):

| Layer | What it does |
|---|---|
| Cloudflare Rate Limiting (free tier) | 10 free rules; e.g. "max 60 req/min per IP per path." Edge-enforced — attacker never reaches origin. |
| BFF per-IP token bucket | Second line. `BFF_PER_IP_RPS=5`, `BFF_PER_IP_BURST=10`. Safety net if a Cloudflare rule is misconfigured. |
| BFF daily NSW-quota budget | `NSW_DAILY_BUDGET=10000`. When exceeded, return 503 `service_temporarily_limited` until midnight Sydney. |
| `X-Krail-Version` header gate | BFF rejects requests missing the header (likely not from the real app). Server-side version floor via `MIN_APP_VERSION` env var — even if force-update flag in remote config fails, the BFF rejects too-old clients. |

What's deliberately **not** here:
- No JWT, no auth tokens, no shared static API token in the app
- No login flow (Google / App Store / Play receipt / email)
- No per-user state on the server
- No client attestation (Play Integrity / App Attest) — defer until first abuse incident

### 0.5 Input validation & error tightening
- Validate stop IDs (digits only, length bounds), date format (YYYYMMDD regex), mode IDs (whitelist) at the route layer. Reject early with the structured error envelope.
- Stop logging the API key prefix in `NswConfig` — even 8 chars leaks entropy in a public repo with public CI logs.
- Audit error envelopes: never reflect upstream NSW error bodies to clients (could leak operator details).

### 0.6 Compression
Install Ktor's `Compression` plugin (gzip + deflate). On JSON responses ~70% additional reduction; on protobuf ~25% additional. Free win, no downsides.

### 0.7 Secret-scanning hygiene (public repo)
- Enable GitHub **secret scanning push protection** in repo settings.
- Verify `.gitignore` covers `local.properties`, `.env`, `*.pem`, `*.p12`, `*.key`.
- Keep `local.properties.template` with placeholder names only — comments must not hint at format/length of real values.
- One-time history audit: `gitleaks detect` or `trufflehog`. If anything historical leaked, rotate immediately.

### 0.8 NSW Open Data attribution & NOTICE files
Per Hub Terms Clause 4(b), datasets are licensed under CC BY 4.0. Required for compliant redistribution:

- **App** — attribution to Transport for NSW visible (already in place; verify after any UI work).
- **BFF README** — short attribution paragraph + link to NSW Open Data Hub + statement that the BFF is unofficial.
- **Bundled `.pb` data in the KRAIL repo** — add `gtfs-static/data/NOTICE.md` with: attribution to TfNSW, statement that the data was modified (GTFS → protobuf), and a link to `https://creativecommons.org/licenses/by/4.0/`.
- **Server-distributed stops dataset** (when Phase 1.1 ships) — bundle a `NOTICE` file inside the published artifact with the same items.
- **Acceptable Use Policy** — fetch from `developer.transport.nsw.gov.au` and confirm rate-limit / fair-use settings align with what it specifies. `NSW_DAILY_BUDGET` should respect any documented daily quota.

---

## Phase 1 — First migrations

### 1.1 Stops dataset (local search, server-distributed)

Search stays **local** in the app. The BFF's job is to ship a fresh stops dataset on a cadence (weekly/monthly) without requiring an app update.

**Endpoints:**
```
GET  /v1/data/stops/manifest
  → { version, sha256, url, size_bytes, compression: "gzip" }

GET  /v1/data/stops/{version}.pb
  → binary protobuf, Cache-Control: public, max-age=31536000, immutable
```

**App behaviour:** on cold start (and again every 24h), GET manifest, compare `version` to locally-stored value, download + verify hash + atomic swap if newer. Otherwise no-op.

**How the dataset is built:**
- Scheduled job (GitHub Actions cron, weekly) downloads NSW GTFS stop data, normalises into KRAIL `Stop` proto, writes a versioned `.pb` file with embedded `NOTICE` (per 0.8).
- Storage: GitHub Releases (free CDN, unlimited public bandwidth, versioned by tag).
- BFF manifest endpoint returns the URL pointing at the latest published artifact.

**Why this is right at indie scale:**
- Per-keystroke search has zero BFF traffic.
- App works offline for search.
- Server compute is negligible — manifest endpoint is essentially static, cached aggressively at Cloudflare's edge.
- Generalises for park & ride facility list, routes, lines: same pattern.

### 1.2 Departure board (`/v1/stops/{id}/departures`)
Real BFF endpoint, in-memory cache TTL ~15s (env-tunable). Subject to the same rate-limit / budget defences as everything else.

### 1.3 Trip results (`/v1/screens/trip-results`)
Screen-shaped response (drop NSW pass-through models). Replaces the existing `/v1/tp/trip` and `/api/v1/trip/plan` endpoints, which were local-dev scaffolding and never deployed publicly — retired in the same PR.

That's it for Phase 1. Two real BFF endpoints + a static manifest pattern.

---

## Phase 2 — Defer

These are good ideas but **wrong for current scale**. Revisit when usage actually demands them.

| Item | Why deferred | Trigger to revisit |
|---|---|---|
| Server-side GTFS-RT poller + live tracking migration | High value but complex | Live tracking GA + abuse / quota signal from current per-client polling |
| GTFS static pipeline (full) | Current bundled approach works at this scale | App size complaint or 2nd city |
| Park & Ride server polling | Low usage, tight Firebase RC cooldowns already | Park & Ride leaves beta + active usage |
| Redis | In-memory cache suffices for one container | Scaling to 2+ containers |
| Postgres | No persistent server state needed yet | First feature requiring user/server state |
| AWS migration | DO + Cloudflare is dramatically cheaper and simpler | Outgrowing DO, or AWS credits available |
| App attestation (Play Integrity / App Attest) | Friction outweighs benefit at current abuse risk | First abuse incident |
| SSE for live updates | Polling is fine at this scale | Live tracking GA |
| Authenticated Origin Pulls (mTLS) | IP allowlist + `CF-Origin-Token` is enough | Concrete bypass attempt observed |
| Multi-city abstraction | YAGNI until a 2nd city is real | Concrete plan for city #2 |

When live tracking does move server-side eventually, the right pattern is **shared server-side polling**: BFF polls GTFS-RT on its own cadence (e.g. every 15s per feed) regardless of client count, caches the parsed feed in-memory, and all client requests read from cache. One BFF poll feeds N clients, so NSW quota stays bounded as user count grows.

Capture design seams (provider interface per capability, KRAIL domain models in their own package) when convenient — but don't build empty abstractions for hypothetical futures.

---

## NSW API key migration (rollout)

Goal: get the NSW key off the app binary entirely. The leaked-binary-key problem is real because the key is already in the wild via shipped APKs.

**Steps:**
1. Mint a **second NSW API key** for the BFF; old key stays live for the existing app binary.
2. BFF runs in production for ~1–2 months with both keys live in parallel.
3. App version N is shipped with BFF support behind a Firebase Remote Config feature flag; rollout 10% → 50% → 100%.
4. Watch error rate per cohort, BFF p95 latency, NSW upstream success rate, "trip results shown" success metric. Have a kill-switch flag (`use_bff = false`) for fast rollback.
5. Use the existing in-app force-update mechanism + `MIN_APP_VERSION` server-side gate to push stragglers off old versions that don't have BFF support.
6. After all reasonable migration is done + ~2 weeks grace, **delete the old NSW key**. The leaked binary key is now dead.

Rotation procedure for the BFF key (post-migration): swap env var `NSW_API_KEY`, redeploy. No client changes needed.

---

## Public-repo discipline (always-on)

- Never commit: real API keys, production hostnames, real device IDs in tests, captured upstream responses with PII.
- Use placeholder names in committed `*.template` and `*.example` files; comments must not hint at format/length of real values.
- Logging: never log full or partial API keys (no prefixes, no length); never log full request URLs that contain user query data; never log IP addresses (Cloudflare logs those at the edge — origin doesn't need them too).
- Tests use synthetic fixtures only — no captured real responses.
- CI logs are public for public repos — assume anything `println`'d in tests is world-readable.

---

## Realistic effort & cost

| | Hours | Monthly cost |
|---|---|---|
| Phase 0 (security + deploy + version gate) | ~25–40h | ~A$8/mo (DO + Cloudflare free) |
| Phase 1 (manifest + 2 endpoints + cache) | ~20–30h | Same |
| Phase 2 | Deferred | — |

NSW API quota at this user count will not be a concern. Cloudflare's edge caching + BFF in-memory cache + `NSW_DAILY_BUDGET` cap mean abuse cannot meaningfully burn NSW quota.

---

## Hard cost cap (target: ≤ A$15/month)

### Provider-level cost shape
- **DO App Platform Basic** is fixed-price — A$8/mo regardless of traffic. Compute side cannot surprise.
- Bandwidth abuse risk on origin is neutralised by Cloudflare in front. Cloudflare absorbs floods at the edge.
- DO billing alerts: set at 50% and 100% under Settings → Billing. Alerts only — DO doesn't natively kill services, which is *why* Cloudflare is required.
- Misc anomaly (accidentally provisioning a Droplet, etc.) → email alert → manual intervention.

### App-level cost defenders (defence in depth)
Failing closed early is cheaper than failing at the bill ceiling:

1. **Daily upstream NSW budget** — `NSW_DAILY_BUDGET=10000`. Counter resets at Sydney midnight. When exceeded, return 503 and stop calling NSW until reset. Counter lives in-process.
2. **Per-IP rate limit at BFF** — `BFF_PER_IP_RPS=5`, `BFF_PER_IP_BURST=10`. Catches a single runaway client without taking everyone down.
3. **Cloudflare edge rate limit rules** — first line, blocks abusive IPs before they reach origin.
4. **Cache TTLs as cost levers** — bumping departures TTL from 15s → 30s halves upstream calls. Make TTLs env-tunable so you can tighten without redeploy.
5. **Server-side `MIN_APP_VERSION` gate** — drop traffic from very old app versions if they ever start misbehaving.

### Scaling up (when it's actually time)
- Move to DO App Platform Pro tier if Basic's resource limits are hit (~A$15/mo).
- Promote in-memory cache to Upstash Redis (free tier, then ~A$5/mo) when a 2nd container is needed.
- AWS migration only if DO outgrows or AWS credits are available.

Don't pre-scale. Watch the metrics, scale on signal.

---

## First four PRs

1. **`security-foundation`** — Dockerfile, GitHub Actions PR build, lock CORS to env allowlist, Compression plugin, secret-scanning push protection, `local.properties.template` audit, branch ruleset on `main` (see below), strip API key prefix logging from `NswConfig`.
2. **`abuse-protection`** — per-IP rate limit, daily NSW budget counter with 503 fallback, `X-Krail-Version` header check + `MIN_APP_VERSION` gate, structured input validation, error envelope hardening.
3. **`deploy-mvp`** — DO App Platform spec (`.do/app.yaml`), GitHub Actions deploy workflow, Cloudflare in front (DNS proxied, origin firewall locked to Cloudflare IPs, `CF-Origin-Token` shared secret check), DO billing alerts at 50% / 100%, document rotation procedure for `NSW_API_KEY` and `CF_ORIGIN_TOKEN`.
4. **`stops-dataset-v1`** — scheduled job (GitHub Actions weekly) that builds the stops `.pb` with embedded `NOTICE`, publishes to GitHub Release; `GET /v1/data/stops/manifest` endpoint returning the latest release URL.

Anything past PR 4 is open for re-prioritisation based on what hurts most after launch.

---

## Branch ruleset for `main` (public repo, solo)

Configure under repo Settings → Rules → Rulesets. Target: `main` (Include default branch).

**Enable:**
- Require a pull request before merging
  - Required approvals: **0** (solo dev — change to 1 if collaborators join)
  - Require conversation resolution before merging: **on**
  - Allowed merge methods: **squash** + **rebase** only (no merge commits)
- Require status checks to pass: add the `pr.yml` build/test workflow once it exists
- Require linear history: **on**
- Block force pushes: **on**
- Restrict deletions: **on**

**Skip (not worth the friction solo):**
- Required approvals > 0, dismiss stale approvals, most-recent-push approval
- Require review from Code Owners / specific teams
- Require signed commits (nice-to-have; turn on if GPG/SSH signing is already set up)
- Require deployments to succeed
- Code scanning / code quality gates (revisit when CodeQL is configured)
- Automatic Copilot review (cost)

**Bypass list:** add yourself as repo admin so you can hotfix in emergencies without disabling the ruleset.
