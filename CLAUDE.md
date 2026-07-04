# KRAIL-BFF

Ktor server (Kotlin/JVM) deployed on DigitalOcean App Platform behind Cloudflare.
Acts as a Backend-For-Frontend: authenticates NSW Open Data API calls, shapes raw
responses into proto messages the KMP client consumes, and runs GTFS-Realtime tracking.

## Architecture

```
Cloudflare (DNS + proxy)
  → DigitalOcean App Platform Basic (~A$8/mo)
    → Ktor server (server/)
      → NSW Open Data API (trip planner, GTFS-RT feeds)
      → DigitalOcean Spaces (GTFS dataset blobs)
```

Never propose alternative hosting. Cost cap: ~A$15/mo total.

## Proto contract (CRITICAL — read before touching mappers or proto types)

The BFF's wire contract with the KMP client is defined in
`github.com/ksharma-xyz/KRAIL-API-PROTO`. The BFF consumes it as a **Maven
artifact from GitHub Packages** (`xyz.ksharma.krail:api-proto`), not a git
submodule. The pinned version is a single string in
`gradle/libs.versions.toml` under `krail-api-proto`; the GitHub Packages repo +
auth are wired in `settings.gradle.kts`.

**Both KRAIL-BFF and KRAIL must be pinned to the same proto version at all times.**

### Proto release flow

```
1. Make proto changes in KRAIL-API-PROTO on a branch
2. Update version.txt in that branch (e.g. echo "0.4.3" > version.txt)
3. Open PR → CI runs buf lint + breaking-change check → merge
4. auto-tag.yml fires → creates + pushes tag v0.4.3 automatically
5. release.yml fires → creates GitHub Release
6. proto-bump.yml fires here (KRAIL-BFF) → opens bump PR
7. proto-bump.yml fires in KRAIL → opens bump PR
8. Review + merge both bump PRs → both repos on v0.4.3
```

### What proto-bump.yml does

Runs daily at 14:00 UTC. Compares the latest `v*.*.*` tag in KRAIL-API-PROTO
against the version pinned in `gradle/libs.versions.toml`. If a newer tag exists
→ `sed`s the bare `X.Y.Z` into the catalog, commits, and opens a PR. No clone,
no submodule — it reads tags via `git ls-remote`.

**Never auto-merges.** Proto changes may need matching changes in response builders
(mappers under `server/src/main/kotlin/app/krail/bff/mapper/`).

### Version pinning rules

- The pinned version lives **only** in `gradle/libs.versions.toml`
  (`krail-api-proto = "X.Y.Z"`) — the bare version, no `v` prefix.
- Upstream tags are `vX.Y.Z`; proto-bump strips the `v`. Bump = edit that one line.
- **Always update version.txt** in KRAIL-API-PROTO when making proto changes —
  auto-tag keys off it, and the published GitHub Packages artifact version must
  match the tag.
- The artifact for a version must be published to GitHub Packages before the BFF
  can resolve it — a tag with no published artifact will fail the Gradle build.

### Mapper ↔ proto coupling

Files under `server/src/main/kotlin/app/krail/bff/mapper/` build proto messages
from NSW API responses. When a proto field is added:
- If `contract: required` → mapper must populate it or contract tests fail.
- If optional → mapper can add it incrementally; client handles absence gracefully.

## Module layout

```
server/
  src/main/kotlin/app/krail/bff/
    client/nsw/       # NSW Open Data API client
    mapper/           # TripResponse → proto message builders
    track/            # GTFS-Realtime tracking (TrackService, FeedCache, etc.)
    trackdata/        # Dataset stores (shapes, stop directory)
    route/            # Ktor routing
```

Proto types come from the `xyz.ksharma.krail:api-proto` Maven artifact (GitHub
Packages) — there is no in-repo proto directory.

## Key invariants

- `TrackService` expiry check uses the **injected clock** (`clock` param), not
  `LocalDate.now()`. Tests pin the clock to a fixture timestamp. Using wall clock
  causes fixture-dated tests to resolve as EXPIRED days after capture.
- Tracking never re-plans. All tracking state derives from GTFS-RT feeds keyed
  by the locked `realtime_trip_id`. See `TRACKING_DESIGN.md`.
- Each leg resolves independently — one upstream failure never fails the whole request.

## Security invariants (full rules: SECURITY.md — read it before touching routes/plugins/NswClient)

- Every request param validated with an **allowlist regex + length bound**; reuse
  the existing regexes. Reject, don't sanitize.
- Errors respond with `ErrorEnvelope` only — never reflect upstream bodies or
  exception messages to clients.
- `EXEMPT_PATHS` (gates/rate-limit bypass) is for cheap static probes only —
  nothing exempt may call NSW or do heavy work uncached.
- All NSW calls go through `NswClient` (daily budget, breaker, metrics). The NSW
  key never reaches a browser except the dev-only, env-gated `/internal/passthrough`.
- Client IP via `call.clientIp()` only; secret comparisons via `MessageDigest.isEqual`.
- INFO logs: one line, IDs only. URLs/headers/bodies → DEBUG behind `isDebugEnabled`;
  `Authorization` always redacted.

## Running locally

```sh
./gradlew :server:run
```

Requires `local.properties` with NSW API key and other secrets.
See `local.properties.template`.

## Tests

```sh
./gradlew :server:test
```

TrackServiceTest uses captured GTFS-RT fixtures (under `server/src/test/resources/gtfsrt/`).
The test clock is pinned to the fixture's feed header timestamp — never use wall clock
in TrackService or tests will break days after fixture capture.
