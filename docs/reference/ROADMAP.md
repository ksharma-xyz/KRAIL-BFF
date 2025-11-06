---
layout: default
title: Roadmap
parent: Reference
nav_order: 2
---

# KRAIL-BFF Implementation Roadmap
{: .no_toc }

Proposed implementation plan and future features.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

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

## PR 1a: Request correlation ✅ DONE
Goals
- Request correlation (X-Request-Id): extract/inject and log
- Centralized header name constants

Deliverables
- [x] Correlation plugin: reads X-Request-Id or generates UUID, stores in MDC and call attributes; adds to response header
- [x] Headers object for centralizing header name constants
- [x] Updated logback pattern to include correlationId from MDC
- [x] Tests for correlation ID (echo and generation)

Acceptance
- [x] Correlation ID appears in logs and is returned in header X-Request-Id
- [x] Tests verify header echo and UUID generation

---

## PR 1b: Error handling + StatusPages ✅ DONE
Goals
- StatusPages for error handling with a consistent error envelope
- Map common HTTP errors (400, 404, 500) to standard error envelope
- Update rate limiter to return error envelope

Deliverables
- Kotlin data model for error envelope (no success envelope - return data directly)
- Ktor StatusPages that map exceptions and 4xx/5xx to error envelope
- Helper extension to retrieve correlationId from call attributes
- Update existing rate limiter (429) to use error envelope
- Tests for error paths (404, 400, 500)

Error envelope (example)
```json
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

**Note:** Success responses return data directly (no envelope):
```json
{
  "lineId": "T1",
  "status": "operational"
}
```

Acceptance
- Consistent error responses in JSON with correlationId
- Tests cover 400, 404, and 500 error paths
- Rate limiter returns standardized error envelope

---

## PR 2: Mobile analytics headers + structured logging ✅ DONE
Goals
- Define headers the app will send and extract them into MDC + call attributes
- Switch to structured logging (JSON) so fields appear per request
- Optional fallback: parse User-Agent with ua-parser if headers missing (prefer explicit headers)

Headers (sent by the app)
- Required: X-Device-Id (hash or id), X-Device-Model, X-OS-Name, X-OS-Version, X-App-Version
- Optional: X-Client-Region, X-Network-Type

What shipped
- [x] Headers constants in `Headers`
- [x] `configureMobileAnalytics` plugin extracts headers, sanitizes values, stores a `MobileContext` on call attributes, and publishes selected fields to MDC
- [x] JSON logging via `logstash-logback-encoder` in `logback.xml`
- [x] Installed plugin in `Application.module`
- [x] Tests (`MobileAnalyticsTest`) cover: full header capture, missing headers, length capping, and client-side control character rejection

Acceptance
- [x] Incoming requests log structured fields (JSON)
- [x] Missing headers handled gracefully (fields omitted)

Notes
- We intentionally never place deviceId in MDC/logs. It is available on call attributes for business logic if needed.

---

## PR 3: Transport NSW client [~] IN PROGRESS (scaffolded)
Goals
- Typed NSW client wrapper with config (base URL + API key)
- Timeouts, connection pool, retries with backoff (HttpRequestRetry)
- Optional simple breaker on consecutive failures
- Per-call metrics (count, duration, outcome)
- DTOs for minimal fields returned to the app

What shipped in this PR so far
- [x] `NswConfig` extended with retry/backoff and simple breaker knobs
- [x] DI (`configureDI`) wires a shared Ktor `HttpClient` (OkHttp engine) with:
  - ContentNegotiation + JSON
  - HttpTimeout (connect/request/socket)
  - HttpRequestRetry with exponential backoff (retry 5xx and exceptions only)
  - Default `Authorization: apikey <key>` header
- [x] Simple `NswClientImpl.healthCheck()` calling `baseUrl/` for upstream readiness
- [x] Dropwizard `MetricRegistry` injected via Koin; client emits counters and a timer
- [x] Very small consecutive-failures breaker (threshold, reset timeout)
- [x] Unit tests using Ktor MockEngine validate success path, failure path, and breaker skip behavior

Next in PR 3 (kept simple, provider-agnostic friendly)
- [ ] Introduce a slim transport abstraction (future-proofing):
  - Interface: `TransportClient` with `healthCheck()` and future methods (e.g., `getLineStatus`)
  - NSW implementation will back it; Victoria (PTV) can add another
  - Config shape stays similar (baseUrl, apiKey, timeouts, retry, breaker)
- [ ] Map upstream errors into typed exceptions (4xx vs 5xx/timeouts) for the server layer to convert into our error envelope
- [ ] Keep actual TFNSW endpoint wiring in the next PR where we’ll add concrete calls and required fields

Answers to “what’s the NSW base URL and how do we auth?”
- Base host: `https://api.transport.nsw.gov.au` (already the default in config)
- Auth: HTTP header `Authorization: apikey <YOUR_KEY>` (already configured on the client)
- Specific endpoints vary by product (Trip Planner, GTFS, etc.). We’ll wire exact paths and parameters when we implement the first real call in the next PR.

Acceptance (for this scaffold step)
- [x] Client compiles, can be unit-tested without real network
- [x] Retries/backoff and breaker behave as expected in tests
- [x] Metrics increment and capture duration

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

## PR 8a: Security Hardening - Critical Issues ⚠️ HIGH PRIORITY
**Status:** [ ] Not started  
**Security Assessment Date:** October 22, 2025

### Critical Security Issues Identified

#### 1. HTTPS/TLS Enforcement
**Risk Level:** Critical  
**Issue:** Application runs on HTTP without TLS; sensitive data transmitted unencrypted
- No HTTPS configuration
- No HSTS (HTTP Strict Transport Security) headers
- API keys and location data exposed in transit

**Tasks:**
- [ ] Configure TLS termination (reverse proxy or Ktor SSL)
- [ ] Add HSTS header with appropriate max-age
- [ ] Document TLS certificate management
- [ ] Redirect HTTP to HTTPS in production
- [ ] Update deployment docs with TLS requirements

**Acceptance:**
- All production traffic uses HTTPS
- HSTS header present on all responses
- HTTP requests redirect to HTTPS

#### 2. CORS Configuration
**Risk Level:** Critical  
**Issue:** No CORS policy implemented; any origin can make requests

**Tasks:**
- [ ] Install and configure Ktor CORS plugin
- [ ] Define allowed origins (whitelist production domains)
- [ ] Configure allowed methods (GET, POST, etc.)
- [ ] Set allowed headers and expose headers
- [ ] Add environment-specific CORS config (strict prod, relaxed dev)
- [ ] Document CORS policy in configuration guide

**Acceptance:**
- CORS policy enforces allowed origins
- Unauthorized origins receive proper CORS errors
- Tests verify CORS behavior

#### 3. Authentication & Authorization
**Risk Level:** Critical  
**Issue:** No authentication mechanism; endpoints completely open

**Tasks:**
- [ ] Design authentication strategy (API keys, JWT, or OAuth2)
- [ ] Implement authentication plugin/middleware
- [ ] Add per-request auth validation
- [ ] Return 401 Unauthorized for missing/invalid credentials
- [ ] Add rate limiting per authenticated client
- [ ] Document authentication in API docs
- [ ] Create client credential management system

**Acceptance:**
- All API endpoints require authentication
- Invalid credentials return 401 with error envelope
- Tests cover auth success and failure paths

#### 4. API Key Security
**Risk Level:** Critical  
**Issue:** API keys logged and exposed; no rotation mechanism

**Tasks:**
- [ ] Remove all API key logging (including partial keys)
- [ ] Implement secrets management (AWS Secrets Manager, HashiCorp Vault, or K8s secrets)
- [ ] Add API key rotation capability
- [ ] Audit all logging for sensitive data
- [ ] Add automated secret scanning in CI/CD
- [ ] Document secret management practices

**Acceptance:**
- No secrets appear in logs
- API keys loaded from secure secret store
- Rotation process documented and tested

#### 5. Input Validation & Sanitization
**Risk Level:** High  
**Issue:** Insufficient validation of query parameters and inputs

**Tasks:**
- [ ] Add comprehensive validation for all endpoints:
  - Date format validation (YYYYMMDD)
  - Time format validation (HHmm)
  - Stop ID format validation (alphanumeric, length limits)
  - Enum validation for depArr parameter
  - Transport mode ID validation
- [ ] Implement input sanitization for all user-provided data
- [ ] Add request body size validation
- [ ] Use allow-lists instead of block-lists
- [ ] Return 400 Bad Request with specific validation errors
- [ ] Add validation unit tests for edge cases

**Acceptance:**
- All inputs validated before processing
- Malformed inputs return 400 with clear error messages
- Tests cover validation edge cases and attack vectors

#### 6. Information Disclosure Prevention
**Risk Level:** High  
**Issue:** Error messages expose internal implementation details

**Tasks:**
- [ ] Audit all error responses for information leakage
- [ ] Remove stack traces from client responses
- [ ] Sanitize exception messages before returning to clients
- [ ] Log detailed errors server-side only
- [ ] Standardize generic error messages for clients
- [ ] Remove internal service names from error responses
- [ ] Add error message security tests

**Acceptance:**
- Client errors contain no implementation details
- Stack traces never exposed to clients
- Server logs capture full debug information

---

## PR 8b: Security Hardening - Medium Priority Issues
**Status:** [ ] Not started

#### 7. Enhanced Rate Limiting
**Risk Level:** Medium  
**Issue:** Weak global rate limiting; no per-client enforcement

**Tasks:**
- [ ] Implement per-IP rate limiting
- [ ] Implement per-device-ID rate limiting (X-Device-Id)
- [ ] Add configurable rate limit tiers
- [ ] Implement distributed rate limiting for multi-instance deployments
- [ ] Add rate limit headers (X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset)
- [ ] Consider using Bucket4j for sophisticated rate limiting
- [ ] Add rate limit bypass for internal/admin endpoints with auth
- [ ] Monitor and alert on rate limit violations

**Acceptance:**
- Rate limits enforced per-client/per-IP
- Rate limit headers inform clients
- DDoS attack simulation shows protection works

#### 8. Security Headers
**Risk Level:** Medium  
**Issue:** Missing critical security headers

**Tasks:**
- [ ] Add `X-Content-Type-Options: nosniff`
- [ ] Add `X-Frame-Options: DENY` or `SAMEORIGIN`
- [ ] Add `Content-Security-Policy` header
- [ ] Add `X-XSS-Protection: 1; mode=block`
- [ ] Add `Referrer-Policy: strict-origin-when-cross-origin`
- [ ] Add `Permissions-Policy` header
- [ ] Create security headers plugin/middleware
- [ ] Test headers with security scanning tools

**Acceptance:**
- All security headers present on responses
- Security scanner (e.g., Mozilla Observatory) gives A rating

#### 9. Request Size Limits
**Risk Level:** Medium  
**Issue:** No explicit content length restrictions

**Tasks:**
- [ ] Configure max request body size in Ktor
- [ ] Add max URL length validation
- [ ] Add max header size limits
- [ ] Return 413 Payload Too Large for oversized requests
- [ ] Document size limits in API documentation
- [ ] Test with large payload attacks

**Acceptance:**
- Oversized requests rejected with 413
- Server remains stable under large payload attacks

#### 10. Dependency Security
**Risk Level:** Medium  
**Issue:** No automated vulnerability scanning

**Tasks:**
- [ ] Add Dependabot or Renovate for automated dependency updates
- [ ] Integrate OWASP Dependency-Check in CI/CD
- [ ] Add Snyk or similar security scanning
- [ ] Set up automated security alerts
- [ ] Create dependency update policy
- [ ] Regular security audit schedule (quarterly)
- [ ] Document process for handling security vulnerabilities

**Acceptance:**
- Automated dependency scanning in CI/CD
- Security alerts configured and monitored
- Vulnerability remediation process documented

---

## PR 8c: Security Hardening - Additional Improvements
**Status:** [ ] Not started

#### 11. Sensitive Data Protection
**Risk Level:** Medium  
**Issue:** Device IDs and PII handling needs improvement

**Tasks:**
- [ ] Implement data retention policy for device IDs
- [ ] Add PII hashing/pseudonymization where appropriate
- [ ] Audit all MDC logging for PII leaks
- [ ] Document data privacy policy
- [ ] Add opt-out mechanism for analytics if required
- [ ] Ensure GDPR/privacy law compliance
- [ ] Add data export/deletion capabilities if storing user data

**Acceptance:**
- PII handling documented and compliant
- No unnecessary PII in logs or long-term storage

#### 12. Endpoint Security Disclosure
**Risk Level:** Low  
**Issue:** Health endpoints reveal system internals

**Tasks:**
- [ ] Consider authentication for /ready endpoint
- [ ] Limit information in health check responses
- [ ] Add separate internal vs external health endpoints
- [ ] Remove detailed upstream service status from public endpoints
- [ ] Document security considerations for health checks

**Acceptance:**
- Minimal information disclosure from health endpoints
- Internal health endpoints secured appropriately

#### 13. Additional Security Measures
**Tasks:**
- [ ] Implement request ID validation (prevent header injection)
- [ ] Add SQL injection protection (if database added later)
- [ ] Implement CSRF protection if adding state/sessions
- [ ] Add security.txt file (RFC 9116)
- [ ] Create incident response plan
- [ ] Set up security logging and SIEM integration
- [ ] Perform penetration testing
- [ ] Create security documentation for mobile app team

**Acceptance:**
- Comprehensive security posture
- Regular security reviews scheduled

---

## Security Testing Checklist
**To be completed before production:**
- [ ] OWASP ZAP or Burp Suite scan completed
- [ ] Dependency vulnerability scan clean
- [ ] Manual penetration testing completed
- [ ] Security code review completed
- [ ] Secrets scanning (no credentials in git history)
- [ ] TLS configuration validated (SSL Labs A+ rating)
- [ ] Rate limiting stress tested
- [ ] Input validation fuzzing completed
- [ ] Error handling reviewed (no info disclosure)
- [ ] Authentication/authorization tested
- [ ] Security headers validated
- [ ] CORS policy tested
- [ ] Logging audited (no sensitive data)
- [ ] Incident response plan documented

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
