---
layout: default
title: Configuration
parent: Reference
nav_order: 1
---

# Configuration

Every runtime knob is here. Each entry: env var name, what it does, default, where it's set in `application.yaml`, the resolution order.

**Resolution order for every key**: `Env var` → `local.properties` (local dev only) → `application.yaml` (default). The first non-blank wins.

---

## NSW upstream

| Env var | YAML key | Default | Notes |
|---|---|---|---|
| `NSW_API_KEY` | `nsw.apiKey` | (required, no default) | Open Data Hub key. Server-side only — never goes to the app. App startup fails with a clear error if blank. |
| `NSW_BASE_URL` | `nsw.baseUrl` | `https://api.transport.nsw.gov.au` | Override only for testing or hitting a mock. |
| `NSW_CONNECT_TIMEOUT_MS` | `nsw.connectTimeoutMs` | `10000` | Connect timeout for upstream HTTP. |
| `NSW_READ_TIMEOUT_MS` | `nsw.readTimeoutMs` | `10000` | Read/socket timeout for upstream HTTP. |
| `NSW_BREAKER_FAILURE_THRESHOLD` | `nsw.breakerFailureThreshold` | `3` | Consecutive failures before circuit breaker opens (health endpoint). |
| `NSW_BREAKER_RESET_TIMEOUT_MS` | `nsw.breakerResetTimeoutMs` | `60000` | How long the breaker stays open. |
| `NSW_DAILY_BUDGET` | `nsw.dailyBudget` | `10000` | Self-imposed cap on NSW upstream calls per Sydney day. `0` disables. When exceeded, BFF returns 503 `service_temporarily_limited` until midnight Sydney. |

## CORS

| Env var | YAML key | Default | Notes |
|---|---|---|---|
| `BFF_CORS_ORIGINS` | `bff.cors.origins` | `""` (empty) | Comma-separated allowed origins (e.g. `https://krail.app,http://localhost:3000`). Empty = reject all cross-origin (production-safe default). |

## Rate limiting

Two layers: per-IP (primary defence) + global aggregate backstop.

| Env var | YAML key | Default | Notes |
|---|---|---|---|
| `BFF_PER_IP_RPS` | `bff.perIp.rps` | `5` | Sustained req/sec per IP. |
| `BFF_PER_IP_BURST` | `bff.perIp.burst` | `10` | Burst capacity per IP. |
| `BFF_PER_IP_MAX` | `bff.perIp.maxIps` | `10000` | Soft cap on tracked IPs in memory. |
| `BFF_RATE_LIMIT_RPS` | `bff.rateLimit.rps` | `50` | Global aggregate sustained req/sec. |
| `BFF_RATE_LIMIT_BURST` | `bff.rateLimit.burst` | `100` | Global aggregate burst. |

## Auth gates (opt-in)

Both default to disabled so dev and tests don't need extra config.

| Env var | YAML key | Default | Notes |
|---|---|---|---|
| `MIN_APP_VERSION` | `bff.minAppVersion` | `0.0.0` (disabled) | Server-side floor for `X-Krail-Version` semver. Setting any value enables the gate: missing header → 400 `missing_version`; malformed → 400 `invalid_version`; below floor → 426 `upgrade_required`. Health and root paths exempt. |
| `CF_ORIGIN_TOKEN` | `bff.cfOriginToken` | `""` (disabled) | Shared secret between Cloudflare and origin. Required in production. Cloudflare adds `CF-Origin-Token: <value>` via Transform Rule; origin rejects requests without a matching value. Health and root paths exempt. |

## Static data manifests

Both default to empty (returns 404) so the BFF works locally before the dataset workflow has been run.

| Env var | YAML key | Default | Notes |
|---|---|---|---|
| `STOPS_MANIFEST_URL` | `data.stops.manifestUrl` | `""` | URL the `/v1/data/stops/manifest` endpoint 302-redirects to. Production: GitHub Releases asset, e.g. `https://github.com/ksharma-xyz/KRAIL-BFF/releases/latest/download/stops-manifest.json`. |
| `ROUTES_MANIFEST_URL` | `data.routes.manifestUrl` | `""` | URL the `/v1/data/routes/manifest` endpoint 302-redirects to. Same shape as `STOPS_MANIFEST_URL`. |

---

## Local development

Copy the template:

```bash
cp local.properties.template local.properties
# Edit local.properties to set nsw.apiKey
```

`local.properties` is git-ignored. Anything you set there feeds the resolution chain. Common dev setup:

```properties
nsw.apiKey=<your NSW Open Data key>
bff.cors.origins=http://localhost:3000,http://localhost:63342
# Leave gates off for local dev:
# bff.minAppVersion=0.0.0
# bff.cfOriginToken=
```

## Production secrets handling

Set these via DigitalOcean App Platform's encrypted env-var UI (or `doctl apps update --env`). Never in `local.properties` on the server, never in YAML.

- `NSW_API_KEY`
- `CF_ORIGIN_TOKEN`

See [DEPLOYMENT.md](DEPLOYMENT.md) for the rotation procedure.

## Verifying live config

The application logs which source each value came from at startup:

```
✅ NSW API Key loaded successfully from: environment variable
✅ NSW daily call budget: 10000
Origin token gate disabled (CF_ORIGIN_TOKEN unset)
Version gate disabled (MIN_APP_VERSION = 0.0.0)
Application started in 0.3 seconds.
```

For a running server, hit `/health` and check headers — the `X-Request-Id` is generated, confirming the correlation plugin is active.

## Per-environment recommended values

| Setting | Local dev | Production |
|---|---|---|
| `NSW_API_KEY` | from `local.properties` | DO encrypted secret |
| `BFF_CORS_ORIGINS` | `http://localhost:3000,http://localhost:63342` | only your real origins, or empty |
| `MIN_APP_VERSION` | `0.0.0` (off) | the lowest app version you support |
| `CF_ORIGIN_TOKEN` | unset | set; matches Cloudflare Transform Rule |
| `NSW_DAILY_BUDGET` | `1000` (low — surfaces budget bugs early) | `10000` |
| `STOPS_MANIFEST_URL` | unset (or local file) | GitHub Releases URL |
| `ROUTES_MANIFEST_URL` | unset (or local file) | GitHub Releases URL |

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `NSW API Key is missing` at startup | `local.properties` not present or `nsw.apiKey` empty; or `NSW_API_KEY` env var unset on server |
| 401 from NSW upstream | Key invalid / expired. Mint a new one in NSW Open Data Hub |
| 429 `rate_limited` immediately on first call | Per-IP burst exhausted from earlier tests. Wait 2 sec or restart server |
| 503 `service_temporarily_limited` | `NSW_DAILY_BUDGET` exceeded (default 10k). Wait until Sydney midnight or raise limit |
| 400 `missing_version` | `MIN_APP_VERSION` set + missing `X-Krail-Version` header. Disable the gate or set the header |
| 403 `forbidden` | `CF_ORIGIN_TOKEN` set + missing/wrong `CF-Origin-Token` header. Cloudflare Transform Rule must match server env var |
| 404 from `/v1/data/*/manifest` | `*_MANIFEST_URL` not configured (returns "manifest_not_configured"). Set the env var |
