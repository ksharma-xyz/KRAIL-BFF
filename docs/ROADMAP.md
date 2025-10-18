# KRAIL-BFF Implementation Roadmap

This document tracks the proposed PR-by-PR plan, acceptance criteria, and concrete steps to implement a production-ready Ktor BFF.

Quick links
- Run locally: see Try it locally
- Config keys: see Configuration keys
- Headers: see Mobile analytics headers
- Metrics and logs: see Observability

Status legend
- [ ] Not started
- [~] In progress
- [x] Done

---

## PR 1: Project scaffolding + conventions
Goals
- StatusPages for error handling with a consistent error envelope
- Basic configuration structure (env overrides via system props/env vars)
- Request correlation (X-Request-Id): extract/inject and log
- Optional success envelope for JSON
- Test helpers (Ktor TestHost) + MockEngine for Ktor client

Deliverables
- Kotlin data model for error envelope and optional success envelope
- Ktor StatusPages that map exceptions and 4xx/5xx to error envelope
- Correlation plugin: reads X-Request-Id or generates UUID, stores in MDC and call attributes; adds to response header
- Config loader: load application.yaml, override with system properties and env vars
- Test base class for server tests; MockEngine helper for client tests

Error and success envelope

Error (example)
```
{
  "success": false,
  "error": {
    "code": "bad_request",
    "message": "Invalid input",
    "details": { "field": "lineId", "reason": "must be numeric" }
  },
  "correlationId": "8c9d5f84-0a7e-4a73-8a3b-1b2c3d4e5f6a"
}
```

Success (example)
```
{
  "success": true,
  "data": { "example": true },
  "correlationId": "8c9d5f84-0a7e-4a73-8a3b-1b2c3d4e5f6a"
}
```

Acceptance
- Consistent error responses in JSON
- Correlation ID appears in logs and is returned in header X-Request-Id
- Tests cover at least one error path

---

## PR 2: Mobile analytics headers + structured logging
Goals
- Define headers the app will send and extract them into MDC + call attributes
- Switch to structured logging (JSON) so fields appear per request
- Optional fallback: parse User-Agent with ua-parser if headers missing (prefer explicit headers)

Headers (sent by the app)
- Required: X-Device-Id (hash or id), X-Device-Model, X-OS-Name, X-OS-Version, X-App-Version
- Optional: X-Client-Region, X-Network-Type

Tasks
- Ktor plugin that reads headers above; store into MDC (deviceId, model, osName, osVersion, appVersion, clientRegion?, networkType?)
- Add JSON logging (logback JSON layout, e.g., logstash-logback-encoder) with MDC fields emitted per line
- Document header contract for mobile app team
- Gracefully handle missing headers (null-safe MDC, default values)

Acceptance
- Incoming requests log structured fields (JSON)
- Missing headers handled gracefully (no crashes, fields absent/null)

---

## PR 3: Transport NSW client
Goals
- Typed NSW client wrapper with config (base URL + API key)
- Timeouts, connection pool, retries with backoff (HttpRequestRetry)
- Optional simple breaker on consecutive failures
- Per-call metrics (count, duration, outcome)
- DTOs for minimal fields returned to the app

Tasks
- Create NswClient using Ktor HttpClient with OkHttp engine (no CIO)
- Load config: NSW_BASE_URL, NSW_API_KEY, connect/read timeouts, retry/backoff
- Add HttpRequestRetry and a small consecutive-failure tripwire (disable after N failures for T seconds)
- Expose metrics counters/timers and tag by result (2xx/4xx/5xx/timeout)
- Map upstream errors: 4xx → 400/502 as appropriate, 5xx/timeout → 502/504
- Unit tests with MockEngine: happy path, timeouts, error mapping

Acceptance
- Client callable from tests with MockEngine
- Metrics increment and duration recorded
- Errors mapped to server exceptions consistently

---

## PR 4: First API endpoint (JSON)
Goals
- Add GET endpoint that calls NSW and returns limited data
- Validate inputs; return 400 on bad input
- Measure inbound vs upstream latency separately; log both

Tasks
- Define route, e.g., GET /v1/transport/lines/{lineId}/status
- Input validation (path/query). On failure, throw BadRequest with error envelope mapping
- Use NswClient; measure and tag upstream timing
- Return JSON (success envelope if adopted)
- Integration tests with Ktor test host (happy path + 400 + upstream failure)

Acceptance
- Endpoint returns expected JSON
- Metrics and logs reflect inbound vs upstream timings

---

## PR 5: Protobuf support
Goals
- Define .proto for limited response and generate classes
- Serve application/x-protobuf via content negotiation (Accept header) or ?format=proto
- Ensure JSON and protobuf render same logical data
- Mobile app can request protobuf explicitly; JSON remains default if Accept is missing

Tasks
- Add protobuf Gradle plugin and wire generation (Java or Kotlin wrappers)
- Add a ContentConverter for protobuf or route-level ByteArray response
- Content negotiation: JSON default, protobuf on Accept or query param
- Ensure 429 (Too Many Requests) error is also serialized to protobuf when requested
- Tests to compare JSON vs protobuf equivalence

Acceptance
- Clients can request protobuf (Accept: application/x-protobuf)
- Schema checked-in under version control
- JSON and protobuf render same logical data; error cases (e.g., 429) also covered

---

## PR 6: Metrics export + dashboards
Recommendation
- Switch to Micrometer + Prometheus registry for tagged metrics (Option B)

Goals
- Expose /metrics for Prometheus scrape
- Add dashboards (Grafana) for core SLOs

Tasks
- Add Micrometer, Prometheus registry; register timers/counters
- Tag metrics by endpoint, status family, device/os/app-version
- Provide dashboard JSON: inbound p95/p99, upstream NSW p95/p99, error rates, traffic by device/os/app-version
- Document SLOs (RED): availability, latency objectives, error budgets

Acceptance
- Metrics visible and tagged in /metrics
- Dashboard JSON committed; SLOs documented

---

## PR 7: Operational hardening
Goals
- CORS, compression, HSTS (if behind TLS), request size limits
- Throttling/rate-limiting per device-id (Bucket4j or lightweight) and a global cap
- Caching for common reads (Caffeine, short TTL)
- Health/readiness checks including NSW reachability

Tasks
- Configure CORS and compression plugins
- Add HSTS when TLS terminates in front (document deployment expectations)
- Add request size limits in engine config
- Rate limiting:
  - Implement a lightweight token bucket or Bucket4j
  - Global limiter default: 3 requests/second (burst 3)
  - Optional per-device limiter keyed by X-Device-Id
  - On limit exceeded: return 429 Too Many Requests; if client requested protobuf, serialize error envelope as protobuf
- Add in-memory cache with TTL for hot endpoints
- Implement /health (liveness) and /ready (readiness with NSW probe)

Acceptance
- Basic protections in place; cache demonstrated
- Readiness reflects upstream reachability
- Rate limiter enforces 3 RPS global (configurable) and responds with 429 consistently (JSON or protobuf per Accept)

---

## PR 8: Security
Goals
- Optional (deferred) for now: app-level API key or signed token for BFF endpoints
- Secrets via env vars or vault; never log secrets
- Privacy hygiene (PII hashing/salting if needed)

Decision
- For now, no API key is required (mobile app has no login). All requests are served.
- Keep a documented plan to add API key or signed tokens later without breaking clients.

Tasks (deferred)
- Auth plugin that validates an app-level API key (constant-time compare)
- Load secrets: BFF_API_KEY (env), vault integration optional later
- Redact secrets in logs; add unit tests for auth paths (401/403)

Acceptance
- Deferred until authentication is introduced

---

## PR 9: CI/CD + Environments (test vs prod)
Goals
- GitHub Actions with build, tests, ktlint/detekt, dependency updates
- Container image build and promotion across environments
- Clearly separated test vs prod with approvals and config isolation
- Code coverage reporting
- Environments (dev/test/prod) with overrides and secrets

Branching and release model
- Trunk-based development
  - feature/* branches -> PR -> main (protected)
  - main auto-deploys to test
  - production deploy triggered by Git tag vX.Y.Z with required approval (GitHub Environment)
- Alternative (if preferred): release/* branches for long-lived release stabilization; tags still gate prod

Pipelines (GitHub Actions)
- ci.yml (on PR + push)
  - gradle build/test
  - static checks: ktlint, detekt
  - test report + codecov upload
- docker.yml (on push to main and tags)
  - build multi-arch Docker image
  - tag as ghcr.io/<org>/<repo>:<sha>, :edge (main), and :vX.Y.Z on tag
  - push to GHCR
- deploy-test.yml (on push to main)
  - fetch image :sha or :edge
  - deploy to test environment (GitHub Environment: test) using one of:
    - Kubernetes: Helm chart in /deploy/helm; kubectl/helm with KUBECONFIG from env
    - ECS Fargate: aws-actions/amazon-ecs-deploy-task-definition
    - Render/Fly/Cloud Run: respective actions
  - smoke test: call /health and /ready
- deploy-prod.yml (on release tag v*)
  - requires approval on Environment: production
  - deploy same artifact (image digest) promoted from test
  - post-deploy smoke + canary/rollback hooks

Configuration and secrets
- Use environment secrets/vars in GitHub Environments (test, production)
  - NSW_BASE_URL, NSW_API_KEY, BFF_RATE_LIMIT_*, KTOR_PORT, LOG_LEVEL, etc.
- Never store secrets in repo or logs
- Externalize runtime config via env vars only; same image runs everywhere

Artifacts and versioning
- Gradle version inferred from tag for prod; SNAPSHOT for main if desired
- SBOM and image digest recorded in release notes

Acceptance
- Pipeline green for PRs and main
- Pushing to main deploys to test automatically
- Tagging v* deploys to production after manual approval
- One image promoted across envs; config isolated via environment variables

---

## PR 10: API docs (optional)
Goals
- OpenAPI docs available at /docs or stored under docs/openapi

Best practice options
- Source of truth: keep OpenAPI YAML under docs/openapi/*.yaml checked into git
- Runtime docs (optional): serve Swagger UI or Redoc at /docs
  - Option A: Use ktor-server-openapi to generate/serve
  - Option B: Serve static Swagger UI assets and point them to your YAML
- You do not need a custom index.html unless you serve static docs; prefer maintaining a YAML spec and using Swagger UI/Redoc to render it

Tasks
- Add ktor-server-openapi or serve static docs/openapi/*.yaml with Swagger UI
- Integrate with route DSL or maintain manual spec
- Add CI check to validate OpenAPI schema

Acceptance
- Docs available in repo (YAML) and optionally served by server at /docs

---

## Dependency Injection (DI) strategy
Options
- Koin (recommended for Ktor): simple, idiomatic for Kotlin server apps
- Alternatives: Kodein, manual wiring via singletons/factories (ok for very small apps)

Plan
- Start with Koin in the server module to manage wiring for:
  - Configuration objects
  - HttpClient (OkHttp engine) and NswClient
  - Metrics registry (Micrometer in PR 6)
- Keep a single Gradle module (server) for now to reduce complexity; consider splitting later if needed:
  - :nsw-client (HTTP and DTOs)
  - :domain (business logic)
  - :server (Ktor + DI)

Acceptance
- Koin module(s) define and inject NswClient and config; tests can override modules easily

---

## Configuration keys
Load precedence
1) System properties (-Dkey=value)
2) Env vars
3) application.yaml defaults

Keys
- KTOR_PORT or -PrunPort for local runs
- NSW_BASE_URL, NSW_API_KEY
- NSW_CONNECT_TIMEOUT_MS, NSW_READ_TIMEOUT_MS
- NSW_RETRY_MAX_ATTEMPTS, NSW_RETRY_BACKOFF_MS
- BFF_RATE_LIMIT_RPS (default 3)
- BFF_RATE_LIMIT_BURST (default 3)
- BFF_API_KEY (for PR 8)

Example (zsh)
```zsh
export NSW_BASE_URL="https://api.transport.nsw.gov.au"
export NSW_API_KEY="<redacted>"
export BFF_RATE_LIMIT_RPS=3
export BFF_RATE_LIMIT_BURST=3
./gradlew :server:run -PrunPort=8080
```

---

## Error mapping (standard)
- 400 bad_request: validation errors, missing params
- 401 unauthorized: missing/invalid BFF API key
- 403 forbidden: auth present but not permitted
- 404 not_found: resource not found
- 502 upstream_error: NSW 5xx mapped to 502
- 504 upstream_timeout: NSW timeout mapped to 504
- 500 internal_error: unhandled exceptions

Each error returns error envelope with correlationId. For throttling, return 429 Too Many Requests and optionally set `Retry-After`.

---

## Observability
Structured logs (JSON)
- Fields: timestamp, level, message, correlationId, http.method, http.path, status, duration_ms, upstream.duration_ms, upstream.status, deviceId, deviceModel, osName, osVersion, appVersion, clientRegion?, networkType?, error.code?

Metrics (Micrometer recommendation)
- inbound_request_duration_ms{endpoint,method,status}
- upstream_nsw_request_duration_ms{route,status}
- upstream_nsw_request_total{result=success|timeout|error}
- errors_total{type,endpoint}
- traffic_total{deviceModel,osName,appVersion}

---

## Mobile analytics headers
Contract
- X-Device-Id: string (opaque; may be hashed)
- X-Device-Model: string
- X-OS-Name: string (e.g., iOS, Android)
- X-OS-Version: string (e.g., 17.1)
- X-App-Version: string (e.g., 1.3.0)
- X-Client-Region: optional string (e.g., AU-NSW)
- X-Network-Type: optional string (e.g., wifi, 5g)

Behavior
- Extract to MDC + call attributes
- If absent, log fields omitted; do not fail requests
- Optional fallback: parse User-Agent with ua-parser

---

## Try it locally
Build
```zsh
./gradlew clean build
```

Run server
```zsh
./gradlew :server:run -PrunPort=8080
```

Run tests
```zsh
./gradlew :server:test
```

Ping
```zsh
curl -i http://localhost:8080/health || true
```

---

## Housekeeping and decisions
- Removing core/ and client/ folders (build ignores them) — decide whether to delete now to reduce confusion
- Engines: standardize on Netty for the server and OkHttp for the Ktor client (no CIO)
- Metrics stack: keep Ktor metrics or switch to Micrometer (recommended in PR 6)
- Prefer explicit mobile headers vs User-Agent parsing (recommended)
- NSW API key/URL provided via env vars — confirm values and secrets handling
- DI with Koin in single module (server) to start; multi-module later if codebase grows
- No API key required for now; rely on rate limiting for basic protection

---

## Appendix
Standard log fields (example output)
```
{
  "ts": "2025-01-01T12:34:56.789Z",
  "lvl": "INFO",
  "msg": "GET /v1/transport/lines/123/status 200",
  "correlationId": "8c9d5f84-0a7e-4a73-8a3b-1b2c3d4e5f6a",
  "http.method": "GET",
  "http.path": "/v1/transport/lines/123/status",
  "status": 200,
  "duration_ms": 42,
  "upstream.duration_ms": 31,
  "upstream.status": 200,
  "deviceId": "abc123",
  "deviceModel": "iPhone15,2",
  "osName": "iOS",
  "osVersion": "17.1",
  "appVersion": "1.3.0"
}
```

Sample NSW client metrics tags
- route: e.g., nsw.lines.status
- result: success|timeout|error|upstream_4xx|upstream_5xx

Notes
- Never log secrets (API keys, tokens)
- Use correlationId in all logs; return it in X-Request-Id header
- Favor small, targeted PRs; keep acceptance criteria green per PR
