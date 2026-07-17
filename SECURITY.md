# Security Policy

## Reporting a Vulnerability

If you believe you have found a security vulnerability in KRAIL-BFF, please report it
privately. **Do not open a public issue, pull request, or discussion for security
problems.**

- **Preferred:** Use GitHub's [private vulnerability reporting](https://github.com/ksharma-xyz/KRAIL-BFF/security/advisories/new)
  ("Report a vulnerability" under the repository's **Security** tab).
- **Email:** [hey@krail.app](mailto:hey@krail.app) if you cannot use GitHub's reporting flow.

Please include a description of the issue and its impact, steps to reproduce, and the
affected route or component. We aim to acknowledge new reports within 5 business days and
support coordinated disclosure: please give us reasonable time to ship a fix before
disclosing publicly.

The rest of this document is internal engineering guidance for contributors.

---

# KRAIL-BFF Security Guidelines

Rules to follow when adding or modifying code. Grounded in the July 2026 security
review; each rule exists because its violation was either found here or is one
edit away. Read this before touching routes, plugins, or the NSW client.

## Defense layers (don't skip one, don't duplicate one)

```
Cloudflare (proxy, edge rules)
  → DO firewall (allow only Cloudflare IP ranges)
    → OriginTokenGate   (CF-Origin-Token shared secret; constant-time compare)
      → VersionGate     (X-Krail-Version floor)
        → PerIpRateLimit (token bucket per client IP)
          → global rate limit (cross-IP backstop)
            → per-route input validation (allowlist regexes)
              → NswClient (daily budget + circuit breaker + timeouts)
```

A request must pass **all** layers. New middleware goes in `Application.module()`
in the right order — gates before limiters before routes.

## Adding or changing an endpoint

- **Validate with allowlist regexes, reject don't sanitize.** Reuse the existing
  patterns: `STOP_ID_REGEX` (TripRequest.kt / ParkingRoutes.kt), `FEED_REGEX`
  (GtfsRoutes.kt), `TRIP_ID_REGEX` (TrackRoutes.kt). Bound every length. A new
  parameter without a regex is a review blocker.
- **Never reflect upstream bodies or exception messages to clients.** Respond with
  `ErrorEnvelope` (stable `code`, generic `message`, `correlationId`). StatusPages
  in `ErrorHandling.kt` already maps `NswUpstreamException` → generic 502; don't
  add catch blocks that leak `e.message` into response bodies.
- **`EXEMPT_PATHS` is for cheap, static probes only.** Anything exempt from the
  gates/limiters must not call NSW, hit disk, or do heavy compute. `/ready` once
  fired a live NSW call per anonymous hit (unauthenticated upstream amplifier) —
  that's why its result is now cached 30 s in `Administration.kt`. If a new
  endpoint needs exemption, cache whatever it computes.
- **Every NSW call goes through `NswClient`** — it owns `dailyBudget.tryAcquire()`,
  the circuit breaker, timeouts, and metrics. Never build a side-channel HTTP call
  carrying `config.apiKey`.
- **Client IP = `call.clientIp()` only.** Never read `X-Forwarded-For` /
  `CF-Connecting-IP` directly — the helper only trusts proxy headers when
  `CF_ORIGIN_TOKEN` is set (i.e. we're really behind Cloudflare), otherwise
  they're attacker-controlled.
- **Secret comparisons use `MessageDigest.isEqual`**, never `==`/`!=` (timing
  side-channel). See OriginTokenGate.kt.

## Logging rules

- INFO = one line per operation, IDs and counts only.
- Full URLs, headers, response bodies → DEBUG, wrapped in `if (logger.isDebugEnabled)`.
- `Authorization` always `[REDACTED]` — public repo, public CI logs.
- Upstream error bodies truncated (`take(500)`).
- Never log `deviceId` (see MobileAnalytics.kt — MDC gets model/OS only).
- Header values that reach logs go through `sanitizeHeader()` (strips control
  chars, caps length) — log-injection guard.

## Secrets

- Live in env vars (prod: DO console) or `local.properties` (dev, gitignored). Never
  in `application.yaml`, code, docs, or the tester HTML.
- The NSW API key must never reach a browser except via `/internal/passthrough`,
  which is gated by `BFF_DEV_PASSTHROUGH` (default off, **never set in prod**) and
  prefix-locked to the NSW base URL (SSRF guard). Any new dev-only route follows
  the same pattern: env-flag default-off, `logger.warn` on enable, strict target
  allowlist.
- Response captures from the API tester go to `api-exports/` (gitignored) —
  Postman/Bruno export formats are gitignored too because they embed keys.

## CORS & headers

- Origins: explicit allowlist via `BFF_CORS_ORIGINS`; empty = deny cross-origin
  (prod default).
- `allowCredentials` stays `false` — the BFF is cookie-less; do not flip it for
  a dashboard convenience.
- Keep `X-Content-Type-Options: nosniff` global (HTTP.kt).

## Scaling assumptions (security-relevant)

All protection state is **in-memory and per-instance**: rate-limit buckets, NSW
daily budget, circuit breaker, `/ready` cache. Correct at `instance_count: 1`
(the locked deploy shape). If that ever changes, these must move to shared state
first — otherwise budget double-spends and limits silently halve.

## CI / dependencies

- PRs must pass build + tests (pr.yml) and CodeQL (`security-extended`). Never
  merge with a dismissed CodeQL alert without a written reason in the PR.
- Dependency bumps come from Renovate (versions) + Dependabot (security). Don't
  hand-edit dependency versions to work around a CVE alert without noting the CVE.
- New workflows: least-privilege `permissions:` block, prefer SHA-pinned actions.
- Never `git add -f` anything under a gitignored path (local.properties,
  api-exports/).

## Pre-merge checklist (copy into PR description for non-trivial changes)

- [ ] New/changed params validated with allowlist regex + length bound
- [ ] Error paths return `ErrorEnvelope`, no upstream/exception text leaks
- [ ] No new `EXEMPT_PATHS` entry (or exempted work is cached/static)
- [ ] NSW calls only via `NswClient` (budget + breaker + metrics)
- [ ] INFO logs: one line, no URLs/bodies/secrets; verbose stuff behind DEBUG
- [ ] No secrets in code/config/docs; dev-only routes env-gated default-off
- [ ] Tests cover the rejection paths, not just happy path
