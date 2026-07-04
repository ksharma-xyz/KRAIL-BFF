# KRAIL-BFF Adoption Guide

> Operational playbook for migrating a KRAIL feature from "calls NSW directly" to "calls KRAIL-BFF." Audience: future
> contributors (human or LLM) starting work on a specific BFF migration. Pairs
> with [MODERNIZATION_PLAN.md](../archive/MODERNIZATION_PLAN.md), [SCREEN_DATA_INVENTORY.md](SCREEN_DATA_INVENTORY.md),
> and [API_SCHEMA_DESIGN.md](API_SCHEMA_DESIGN.md).

This doc tells you *how* to do a migration. The other docs tell you *what* to migrate and *what shape* it takes.

---

## When this guide applies

You're about to wire one of these into KRAIL:

- Stops dataset (manifest + versioned `.pb` download)
- Departure board endpoint
- Park & ride endpoints
- Trip results
- Journey detail + live overlay (combined Track + Map screen)

Each follows the same playbook. Read this once, then apply per-endpoint.

---

## Prerequisites checklist

Before you start an endpoint migration in KRAIL, verify:

- [x] **`krail-api-proto` repo exists and is pinned** — done 2026-05-09. Public at <https://github.com/ksharma-xyz/KRAIL-API-PROTO>, Apache 2.0, currently at `v0.2.0` (polyline fields). KRAIL-BFF consumes via submodule at `krail-api-proto/`. KRAIL app picks the same submodule per `docs/handover/MIGRATION_GUIDE.md` section 4.
- [ ] **The endpoint exists in KRAIL-BFF**, deployed, reachable from a development device or emulator. Hit the health
  endpoint first; do not assume.
- [ ] **Cloudflare + DO origin lockdown is live**
  `CF-Origin-Token` shared secret is set on both Cloudflare and the BFF; DO firewall is restricted to Cloudflare's
  published IP ranges. The endpoint must not be reachable from the public internet directly.
- [ ] **`X-Krail-Version` header is sent by the app on every BFF call**
  The BFF rejects requests missing this header. Confirm KRAIL's HTTP client adds it (see `core/network/`).
- [ ] **`MIN_APP_VERSION` env var on the BFF allows your app's version**
  If you bump the floor mid-migration, the app is rejected. Coordinate version floors with rollouts.
- [ ] **Feature flag exists in Firebase Remote Config**
  One flag per endpoint, e.g. `bff_use_for_departures`. Flag default is `false`. Fetch interval is short enough for fast
  rollback (≤ 5 min — same pattern park-ride cooldowns already use).
- [ ] **Kill-switch flag exists**
  A single `bff_kill_switch` boolean that, when true, forces *every* endpoint back to direct-NSW regardless of
  per-endpoint flags. Audit the call site before relying on it.
- [ ] **Both code paths exist in the app simultaneously**
  Old NSW-direct path and new BFF path live in parallel under the feature flag. Don't delete the NSW path until rollout
  is at 100% + 2 weeks of grace.

If any item above is unchecked, stop and address it. Do not migrate on a half-prepared stack.

---

## Migration playbook (per endpoint)

### Step 1 — Pull the contract

```
git submodule update --remote krail-api-proto
./gradlew :feature:<name>:network:generateCommonProto
```

Verify: the generated Kotlin classes (e.g. `TripResultsResponse`) are visible from `commonMain`. If Wire codegen fails,
fix the schema before doing anything else.

### Step 2 — Add the BFF call site behind the flag

Inside the existing service interface, add a parallel method that hits the BFF:

```kotlin
class RealDeparturesService(
    private val nsw: NswDeparturesClient,        // existing
    private val bff: BffDeparturesClient,        // new
    private val flags: RemoteConfigFlags,        // existing
) : DeparturesService {

    override suspend fun departures(stopId: String): DeparturesResult {
        return if (flags.useBffForDepartures && !flags.bffKillSwitch) {
            bff.departures(stopId).toDomain()    // new path: proto → domain
        } else {
            nsw.departureMon(stopId).toDomain()  // existing path: NSW → mapper → domain
        }
    }
}
```

Both branches must produce the **same domain model**. The BFF path's `.toDomain()` is mostly passthrough; the NSW path
uses the existing mapper. The UI doesn't care which one ran.

### Step 3 — Mapper consolidation

Once `BffDeparturesResponse → DomainDeparture` is in place, the existing `DepartureMonitorMapper` (NSW → domain) keeps
living for the duration of the rollout, then deletes when you hit "100% rolled out + grace period passed."
See [API_SCHEMA_DESIGN.md 5](API_SCHEMA_DESIGN.md) for the full deletion plan per mapper.

### Step 4 — Compare-mode testing (the most useful step)

Before flipping the flag for any user, log both response shapes side-by-side in dev builds only:

```kotlin
override suspend fun departures(stopId: String): DeparturesResult {
    val bffResult = runCatching { bff.departures(stopId).toDomain() }
    val nswResult = runCatching { nsw.departureMon(stopId).toDomain() }

    if (BuildConfig.DEBUG && bffResult.isSuccess && nswResult.isSuccess) {
        compareAndLog(bffResult.getOrThrow(), nswResult.getOrThrow())  // diff into Logcat
    }

    // serve whichever the flag says
    return if (flags.useBffForDepartures && !flags.bffKillSwitch) bffResult.getOrThrow()
    else nswResult.getOrThrow()
}
```

The diff log catches schema misunderstandings before users see them. Remove the dual-call code (or guard it tightly)
before shipping; you don't want production devices double-calling.

### Step 5 — Cohort rollout

Per Modernization Plan "NSW API key migration":

- 0% → internal devices only (you, manual testing)
- 10% → real users; watch error rate and "trip results shown" success metric for 48–72h
- 50% → if 10% is clean, advance after another 48–72h
- 100% → if 50% is clean, after another 72h

At each step, check:

- BFF error rate per endpoint (5xx, 429, 503)
- BFF p95 latency
- App-side "feature didn't load" UX metric (per cohort)
- NSW upstream error rate (BFF should isolate this from users)

If any metric regresses on the BFF cohort, **flip the per-endpoint flag back to false**. Don't try to debug while users
are degraded.

### Step 6 — Cleanup (after 100% + 2 weeks of grace)

- Delete the NSW-direct call site for this endpoint
- Delete the corresponding NSW response models if no other endpoint uses them
- Delete the corresponding mapper(s) per the inventory in [API_SCHEMA_DESIGN.md 5](API_SCHEMA_DESIGN.md)
- Remove the per-endpoint feature flag (the kill switch stays)
- Update the version floor (`MIN_APP_VERSION`) only if you've also done a force-update pass

---

## Testing strategy

### Local development loop

1. Run BFF locally: `./gradlew :server:run` (binds `localhost:8080`).
2. Point the dev build of KRAIL at it: `BFF_BASE_URL=http://10.0.2.2:8080` (Android emulator) or
   `http://localhost:8080` (iOS simulator). Defined in `local.properties` of the KRAIL repo.
3. Set CORS to allow your dev origin in BFF `local.properties`: `BFF_CORS_ORIGINS=http://localhost:*`.
4. Skip Cloudflare in dev. The `CF-Origin-Token` check should be **disabled when `KTOR_ENV=dev`** (this is a deliberate
   carve-out — production runs always require it).

### Compare-mode tests (recommended)

Add a Kotest/JUnit test that hits the BFF and the NSW direct endpoint with the same input and asserts the resulting
domain model is equal:

```kotlin
@Test
fun `departures: BFF result matches NSW direct result`() = runTest {
        val stopId = "200060"
        val viaBff = bffService.departures(stopId).toDomain()
        val viaNsw = nswService.departureMon(stopId).toDomain()
        assertEquals(viaNsw, viaBff)
    }
```

These tests are noisy (rely on live data, real-time fields differ) — consider running them only in a dedicated
`:integration` source set, not on every PR.

### Snapshot tests for proto deserialization

Capture a real BFF response into a fixture file and assert the decoded message looks right. Lives in the app's test
resources; never include user data.

### UI smoke test post-flag-flip

On every cohort advance, manually run through the affected screen on both Android and iOS. Type checking and unit tests
verify code; only running the app verifies the feature.

---

## What "adoption complete" looks like

For a single endpoint:

- Feature flag is at 100% in production for ≥ 2 weeks with no regressions.
- The NSW-direct path for that endpoint is deleted from KRAIL.
- The corresponding mapper(s) and NSW response models are deleted (or marked for deletion if shared with another
  not-yet-migrated endpoint).
- The endpoint's `network/` subdirectory in the relevant feature module either disappears or shrinks to just the BFF
  client.
- The feature flag itself is removed from Firebase RC.

For the full migration:

- All four endpoints (departures, park-ride, trip results, journey + live) are at 100%.
- The NSW-direct API key has been **deleted from NSW** (per Modernization Plan "NSW API key migration").
- `feature/track/network/` no longer references GTFS-RT directly — the BFF serves matched vehicles only.
- `gtfs-static/` is significantly thinner (or gone) once the stops dataset is BFF-distributed.

---

## Common pitfalls

- **Forgetting `X-Krail-Version` in tests.** Your unit tests will pass; the request will 400 in production. Add it in
  the shared HTTP client config, not per-call.
- **Hard-coding the BFF base URL.** Put it in `BuildConfig` driven from `local.properties`/CI. Never commit a real URL
  to a public repo.
- **Logging response bodies.** They contain stop IDs and times that, in aggregate, may reveal user patterns. Log status
  codes and sizes only.
- **Calling both endpoints in parallel for production users.** Compare-mode is dev-only. Production calls one path or
  the other.
- **Treating the kill-switch as a regular flag.** It's a panic button. If you find yourself flipping it more than once a
  quarter, something's wrong with the per-endpoint flags.
- **Deleting the NSW path too early.** If 100% rollout is unstable, you've removed your only fallback. Wait the grace
  period.
- **Skipping the cohort steps.** Going 0 → 100 in one push has caused real outages. Use the 10/50/100 ladder even if it
  feels slow.

---

## Rollback procedure

If something breaks during a rollout:

1. **Flip the per-endpoint flag to `false`** in Firebase Remote Config. Within the app's RC fetch interval (≤ 5 min) all
   clients revert to NSW direct.
2. **If the issue is broader (multiple endpoints or unclear scope), flip `bff_kill_switch` to `true`.** Forces every
   endpoint back to NSW regardless of per-endpoint state.
3. **If the BFF itself is degraded, set `MIN_APP_VERSION` to a version higher than current** to drop traffic — the app
   reverts to a "please update" state. Use only as a last resort; this affects users on healthy versions too.
4. **Document what happened** in a postmortem note (no need for formal — even a paragraph in `docs/incidents/`). Update
   this guide if a new pitfall surfaced.

Do not redeploy the BFF as a panic move — flag flips are faster and reversible. Redeploy only after diagnosis.

---

## Quick reference

| Action                    | Where                                                           |
|---------------------------|-----------------------------------------------------------------|
| Schema source of truth    | `krail-api-proto` repo, tagged version                          |
| BFF endpoint contracts    | [API_SCHEMA_DESIGN.md](API_SCHEMA_DESIGN.md)                    |
| Field-level inventory     | [SCREEN_DATA_INVENTORY.md](SCREEN_DATA_INVENTORY.md)            |
| Deploy & infra            | [MODERNIZATION_PLAN.md](../archive/MODERNIZATION_PLAN.md) 0.3             |
| Cost cap & defenders      | [MODERNIZATION_PLAN.md](../archive/MODERNIZATION_PLAN.md) "Hard cost cap" |
| Per-endpoint feature flag | Firebase Remote Config, `bff_use_for_<endpoint>`                |
| Kill switch               | Firebase Remote Config, `bff_kill_switch`                       |
| Server-side version floor | BFF env var `MIN_APP_VERSION`                                   |
| Cohort metrics            | (TBD: add a dashboard link once metrics endpoint exists)        |
