# Security & Safety Audit — June 2026

> Point-in-time review of KRAIL-BFF (`main` @ `29a3702`, PR #72) plus the
> KRAIL app's server-integration surface. Written for the project owner
> ahead of first deploy. Re-run the worst of these checks before any
> major release; the pre-deploy gate at the bottom is the short version.

**Scope:** BFF source, build, CI, deploy spec; KRAIL app code that talks
to NSW / the BFF (API keys, analytics, bundled data).
**Out of scope:** NSW Open Data portal account hygiene, DigitalOcean /
Cloudflare / Google account security (use strong passwords + 2FA — that
is the whole recommendation), app-store supply chain.

**Verdict: the BFF is in good shape to deploy.** The protections below
already exist and are tested. There is one blocking action (F1) and a
handful of documented, accepted risks.

---

## 1. What's already good (verified controls)

| Control | Where | Notes |
|---|---|---|
| Secrets via env vars only | `server/src/main/kotlin/app/krail/bff/di/DI.kt` (~line 51) | `NSW_API_KEY` read from env first, `local.properties` fallback for local dev, **no hardcoded default** — app refuses to start without it. |
| API key never sent to clients | `server/.../client/nsw/NswClient.kt` (~line 220) | Key attached server-side as `Authorization: apikey …` on upstream calls only. |
| API key redacted in logs | `NswClient.kt` (~lines 260–268) | `Authorization` header masked before logging. |
| Per-IP rate limit | `server/.../plugins/PerIpRateLimit.kt` | Token bucket, 5 rps / 10 burst per IP, memory-bounded (10k IPs), 429 + `Retry-After`. |
| Global rate limit backstop | `server/.../plugins/HTTP.kt` (~lines 74–102) | 50 rps / 100 burst across all traffic. |
| NSW daily budget | `server/.../client/nsw/NswDailyBudget.kt` | Hard stop at 10,000 upstream calls/day (Sydney midnight reset) → 503. This is the cost cap that NSW/DO don't give you. |
| Circuit breaker | `NswClient.kt` (~lines 151–184) | Opens after 3 consecutive upstream failures, 60 s cool-off. |
| App version gate | `server/.../plugins/VersionGate.kt` | `X-Krail-Version` required (semver); 426 below `MIN_APP_VERSION`. Disabled at `0.0.0` for launch. |
| Origin token gate | `server/.../plugins/OriginTokenGate.kt` | `CF-Origin-Token` shared secret pins traffic through Cloudflare; 403 otherwise. Health endpoints exempt. Tested in `plugins/OriginTokenGateTest.kt`. |
| Input validation (allowlists) | `model/TripRequest.kt` (~113–131), `routes/DepartureRoutes.kt`, `routes/ParkingRoutes.kt`, `routes/GtfsRoutes.kt` | Regex allowlists on every path/query param: stop IDs, dates, times, modes, feed names, facility IDs. Nothing user-controlled reaches NSW unvalidated. |
| No internal leakage in errors | `server/.../plugins/ErrorHandling.kt` | Generic error envelopes; upstream bodies logged server-side only, never relayed. |
| Log-injection defence | `server/.../plugins/MobileAnalytics.kt` (~15–24) | Control chars stripped, 256-char cap on client headers before they reach MDC. |
| CORS deny-by-default | `plugins/HTTP.kt` (~20–59) | Empty `BFF_CORS_ORIGINS` ⇒ all cross-origin rejected. Correct for a native-app-only API. |
| Non-root container | `Dockerfile` (line ~24) | Runs as `krail:krail` on a JRE-only Alpine image. |
| Stateless, no database | — | No SQL injection surface, no data store to breach, ~10-min disaster recovery. |
| CI security scanning | `.github/workflows/codeql.yml`, `.github/dependabot.yml` | CodeQL (security-extended) weekly + on PR; Dependabot CVE alerts. |
| Dependencies current | `gradle/libs.versions.toml` | Kotlin 2.3.20, Ktor 3.4.1 — nothing notable outdated at audit time. |

---

## 2. Findings

### F1 · No separate BFF-only NSW API key — **blocking, do before deploy**

Three places currently know an NSW key:

1. **Shipped app binaries** — key injected at build time via BuildKonfig
   (KRAIL `core/network/build.gradle.kts`), sourced from a GitHub Actions
   secret. **Anyone can unzip a public APK and extract this key.** This
   is the original sin the BFF exists to fix; until migration completes
   it is an accepted, time-boxed risk (see ROADMAP.md, "key endgame").
2. **GitHub Actions secret** in the app repo — feeds (1).
3. **`local.properties` on this machine** — local dev key for the BFF.
   Verified **not** git-tracked and never committed (checked
   `git ls-files` and full history). Fine where it is; never commit it.

**Action:** mint a **fourth, BFF-production-only key** at
<https://opendata.transport.nsw.gov.au/> (free, minutes) and use it for
the DO `NSW_API_KEY` secret. Keys must be independently rotatable: when
the app key is eventually deleted (endgame) or leaks, the BFF must not
go down with it.

### F2 · `/internal/passthrough` must stay disabled in production — high if misconfigured

`server/.../routes/InternalRoutes.kt` proxies arbitrary NSW-base-URL
requests **with the server's API key attached** — by design, for the
local dev dashboard. It is off unless `BFF_DEV_PASSTHROUGH=true`; the
default and `.do/app.yaml` keep it off.

**Action:** the pre-deploy gate below includes an explicit negative
test (`GET /internal/passthrough?... → 404`). Never set
`BFF_DEV_PASSTHROUGH=true` on the DO app, even "temporarily".

### F3 · `X-Device-Id` — kept, dormant, with a written policy — info/privacy

The app sends `X-Device-Id`; the BFF stores it in request context
(`model/MobileContext.kt`) and deliberately **never logs it**
(`plugins/MobileAnalytics.kt`). Decision (owner, 2026-06-11): keep it.

Legitimate future uses:
- **Per-device abuse control** — block one misbehaving device rather
  than a whole IP (mobile carriers NAT thousands of users behind one IP,
  so per-IP limits alone either over-block or under-block).
- **Support debugging** — correlate a user's bug report with server-side
  errors without collecting identity.

**Policy (binding for future code):** never log or persist the raw ID.
If it must ever appear in logs or counters, use a salted hash. It is an
opaque install identifier, not a user identifier — keep it that way.

### F4 · Stale operational docs — fixed in this branch

`STATUS.md` described a merged PR stack as blocking;
`DEPLOY_CHECKLIST.template.md` §1–2 walked through merging it. Both
misdirect a future deploy. Refreshed alongside this audit.

### F5 · Wrong origin-lockdown instructions — fixed in this branch

`DEPLOYMENT.md` §3 said to attach a DO Cloud Firewall to the app, and
the checklist referenced an App Platform "Trusted Sources" setting.
**Neither exists for App Platform** (Cloud Firewalls attach to
Droplets; Trusted Sources is a managed-database feature). The actual —
and sufficient — origin lockdown is the `CF-Origin-Token` gate: the
`*.ondigitalocean.app` URL stays reachable but answers **403** to
anyone without the secret header that only Cloudflare injects.
Docs and smoke tests corrected to expect 403, not connection-refused.

---

## 3. PII assessment

Short version: **the BFF handles no PII worth worrying about**, and the
app sends none to it.

- No accounts, logins, cookies, or sessions. No database.
- Requests carry transit **stop IDs**, not GPS coordinates. The app's
  analytics explicitly capture only a `hadUserLocation` boolean — never
  coordinates (KRAIL `AnalyticsEvent.kt`).
- Device metadata headers (`X-Device-Model`, `X-OS-Version`, …) are
  sanitized and logged for debugging — non-identifying.
- `X-Device-Id` is received but never logged or stored (F3).
- App-side analytics/crash reporting go to Firebase directly, not
  through the BFF — that's governed by the app's privacy policy and
  Play/App Store data-safety declarations, not this server.

Keep it this way: any future endpoint that would accept user content,
location, or identifiers should trigger a fresh look at this section.

---

## 4. Accepted residual risks

| Risk | Why accepted | Mitigation in place |
|---|---|---|
| DigitalOcean has **no hard billing cap** | No provider-enforced cap exists on App Platform | Single fixed `basic-xxs` instance, **no autoscaling** in `.do/app.yaml` (the bill cannot grow by itself); `NSW_DAILY_BUDGET` caps upstream usage; per-IP + global + Cloudflare rate limits cap traffic; billing alerts at 50%/100%; panic move = `doctl apps delete` (recreate ≈ 10 min). |
| In-app NSW key extractable from APK until migration completes | Removing it requires the full BFF rollout | Time-boxed via the adoption plan; NSW keys are free-tier transit keys (blast radius = quota abuse, not data). Endgame deletes it. |
| `CF_ORIGIN_TOKEN` rotation has a ~30 s mismatch window | Dual-token support is future work | Rotate during low traffic; documented in `DEPLOYMENT.md`. |
| `/health`, `/ready` unauthenticated | Load balancers need them | They return status strings only; exempt paths are hardcoded and minimal. |
| Direct origin URL discoverable | App Platform can't hide it | Token gate returns 403; nothing is served without the header. |

---

## 5. Pre-deploy security gate

Run top to bottom; every box must be true before pointing the app at
production. (FIRST_DEPLOY.md walks through producing these states.)

- [ ] BFF-only NSW key minted; it is **not** the app's key and **not**
      the local-dev key (F1).
- [ ] `git ls-files | grep local.properties` returns only the
      `.template` file.
- [ ] `NSW_API_KEY` and `CF_ORIGIN_TOKEN` set as **encrypted** env vars
      in DO — never in `.do/app.yaml`, never committed.
- [ ] `BFF_DEV_PASSTHROUGH` unset or `false` in DO (F2).
- [ ] `curl https://<direct-DO-URL>/internal/passthrough?url=…` → 404.
- [ ] `curl https://<direct-DO-URL>/v1/tp/trip…` (no token header) → 403.
- [ ] Same request via `https://bff.krail.app` → 200 (Cloudflare injects
      the token).
- [ ] Cloudflare record is **Proxied** (orange cloud) and rate-limit
      rules are active.
- [ ] DO billing alerts set (50% / 100%); CPU + memory alerts set.
- [ ] Hammering one endpoint from one machine produces 429s.
- [ ] Logs flowing (`doctl apps logs … --follow`) and contain **no**
      `Authorization` values and **no** raw device IDs.

---

*Next review: after first deploy + first app-rollout cohort, or any time
a new endpoint accepts user-generated content.*
