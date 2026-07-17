# Docs Gardener Audit — 2026-07-17

Run mode: **report-only** (per charter Part B). No doc content was changed by this
run. This file records the classification, staleness verification, and proposed
actions for a future `active` run.

## Feedback ingested

Last 5 `docs-gardener`-labeled PRs (only 2 exist): #84, #82. Both merged, zero
issue comments, zero review comments on either. No `charter:` instructions found.
No Steering Log entries added this run.

## Charter Part A drift

Diffed this repo's `.github/docs-gardener/CHARTER.md` Part A against
`ksharma-xyz/KRAIL`'s copy (read-only shallow clone). **No drift** — Part A
(everything above `## Part B: Repo Overrides`) is byte-identical between the two
repos. All diff output was confined to Part B (expected, since Part B is
repo-specific by design).

## Classification table

| File | Class | Notes |
|---|---|---|
| `CLAUDE.md` | reference (protected) | No action — protected file. |
| `SECURITY.md` | reference (protected) | No action — protected file. |
| `TODO.md` | ledger (protected) | No action — protected file; used as ground truth for staleness checks below. |
| `README.md` | guide | All 9 linked docs resolve. No action. |
| `DEPLOY_CHECKLIST.template.md` | guide | **Broken link** — see Proposed actions #1. |
| `docs/README.md` | reference (index) | Index gaps — see Coverage/index notes. |
| `docs/index.md` | reference (index) | Index gaps — see Coverage/index notes. |
| `docs/guides/index.md` | reference (nav stub) | Fine. |
| `docs/guides/DEBUGGING.md` | guide | Links resolve. No action. |
| `docs/guides/EMERGENCY.md` | guide | Links resolve. Consistent with current (pre-deploy) state. No action. |
| `docs/guides/FIRST_DEPLOY.md` | guide | Links resolve; consistent with `TODO.md` "nothing deployed yet". No action. |
| `docs/guides/LOCAL_DEVELOPMENT.md` | guide | Project-structure listing is accurate but incomplete (see Coverage notes). No action needed. |
| `docs/guides/TESTING.md` | guide | **Broken link** — see Proposed actions #2. |
| `docs/handover/README.md` | guide | **Broken links + stale content** — see Proposed actions #3. |
| `docs/handover/MIGRATION_GUIDE.md` | guide | **Broken links + stale proto-consumption description** — see Proposed actions #4. |
| `docs/handover/TRACKING_INTEGRATION.md` | guide | Verified: `./scripts/dev.sh`, `docs/tools/track-tester.html`, `server/src/test/resources/gtfsrt/` all exist. No action. |
| `docs/reference/index.md` | reference (nav stub) | Fine. |
| `docs/reference/API_SCHEMA_DESIGN.md` | plan | **Stale — describes a superseded design** — see Proposed actions #5. |
| `docs/reference/BFF_ADOPTION_GUIDE.md` | guide | **Stale proto-consumption description** — see Proposed actions #6. |
| `docs/reference/CONFIGURATION.md` | reference | Spot-checked `NSW_API_KEY`, `NSW_DAILY_BUDGET`, `MIN_APP_VERSION`, `BFF_PER_IP_RPS`, `CF_ORIGIN_TOKEN`, `STOPS_MANIFEST_URL` against `server/src/main/resources/application.yaml` — all present and consistent. No action. |
| `docs/reference/DEPLOYMENT.md` | guide | **Broken link** — see Proposed actions #7. |
| `docs/reference/ROADMAP.md` | plan | Forward-looking, post-deploy roadmap; deploy hasn't happened yet per `TODO.md`, so nothing here is prematurely "shipped." No action. |
| `docs/reference/SCREEN_DATA_INVENTORY.md` | reference | Cross-repo check: all 12 named KRAIL-side mapper classes (`TripResponseMapper`, `DepartureMonitorMapper`, `ParkRideMapper`, `JourneyMapMapper`, `GtfsRealtimeMatcher`, etc.) verified present in `ksharma-xyz/KRAIL` (shallow clone). No action. |
| `docs/reference/TRACKING_DESIGN.md` | ux-contract | No broken links or dated claims found. Does not mention the injected-clock testing invariant CLAUDE.md calls out — plausibly out of scope for a design-rationale doc (implementation/testing detail, not design). Flagged, no action proposed. |
| `docs/tools/README.md` | guide (protected: `docs/tools/**`) | `./scripts/tester.sh` exists. No action — protected. |
| `docs/tools/bruno/README.md` | guide (protected: `docs/tools/**`) | No action — protected. |
| `scripts/README.md` | guide | Coverage gap — see Coverage notes. |
| `docs/archive/*.md` (11 files) + `docs/archive/README.md` | archive | All moved into archive on 2026-07-04 (13 days ago) — well under the 90-day hard-delete threshold, and all have live inbound references from non-archive docs. No delete candidates this run. |
| `.github/docs-gardener/CHARTER.md` | **unclassifiable** | Doesn't fit the existing taxonomy (ledger/ux-contract/reference/guide/plan/investigation/archive) — it's the gardener's own operating policy, edited by the human and by Steering Log appends, not a project doc. Proposing a taxonomy addition (e.g. `policy`) in a future run; no action taken on the file itself. |

## Proposed actions (priority order, none applied — report-only)

### 1. Fix broken link — `DEPLOY_CHECKLIST.template.md:260`
```
- [ ] Update `STATUS.md` section 1 to reflect deploy done.
```
`STATUS.md` no longer exists at repo root — it was archived to `docs/archive/STATUS.md`
on 2026-07-04 (see `docs/archive/README.md` tombstone table). Evidence:
```
$ ls STATUS.md
ls: cannot access 'STATUS.md': No such file or directory
```
Proposed fix: point at `TODO.md` (its designated successor per the archive tombstone table) instead.

### 2. Fix broken link — `docs/guides/TESTING.md:256`
```
- [Protobuf Integration](PROTOBUF)
```
No `PROTOBUF.md` (or any protobuf-named doc) exists anywhere in the repo. Evidence:
```
$ find docs -iname "*protobuf*"
(no output)
```
Proposed fix: remove the dead link or point it at `docs/reference/API_SCHEMA_DESIGN.md`
(closest existing content) with a caveat that it's a design doc, not a shipped integration guide.

### 3. Fix broken links + stale content — `docs/handover/README.md`
Three issues, quoted with line numbers:
```
101: - [`../../STATUS.md`](../../STATUS.md) — what's on `main`, what's
103: - [`../../START.md`](../../START.md) — how to run the BFF locally.
```
Both resolve to repo root; neither `STATUS.md` nor `START.md` exists there (archived
2026-07-04, see #1's evidence). Also:
```
104-106: [`../reference/`](../reference/) — long-form design docs
          (`MODERNIZATION_PLAN`, `API_SCHEMA_DESIGN`, `BFF_ADOPTION_GUIDE`,
          `DEPLOYMENT`).
```
`MODERNIZATION_PLAN.md` is not under `docs/reference/` — it's under `docs/archive/`:
```
$ ls docs/reference/MODERNIZATION_PLAN.md
ls: cannot access 'docs/reference/MODERNIZATION_PLAN.md': No such file or directory
```
Separately, the "Pick your doc" table (lines 40-43) recommends `API_REFERENCE.md`,
`TESTING_GUIDE.md`, and the Phase C report as primary reading — all three are
archived/superseded per `docs/archive/README.md`'s own tombstone table
(`API_REFERENCE.md` → `docs/openapi/openapi.yaml`; `TESTING_GUIDE.md` →
`docs/guides/TESTING.md`). The links aren't broken (files exist in `docs/archive/`),
but presenting them as the primary integration reference without noting they're
historical is stale content. Note: the file's "not yet deployed" framing (line 15-16)
is still accurate — `TODO.md` (last updated 2026-07-04, more current than this doc)
confirms "nothing is deployed yet."

### 4. Fix broken links + stale proto-consumption model — `docs/handover/MIGRATION_GUIDE.md`
```
27:  see `STATUS.md` in repo root).
...
524: For any of those, the canonical read is `STATUS.md` + the docs
```
Same broken-root-file issue as #1/#3.

Also, this doc is entirely silent on how the proto contract is actually consumed
today, but its "Common pitfalls" section (line 444) says:
```
- **Forgetting `submodules: true` on `actions/checkout`.** CI
  doesn't fetch `krail-api-proto/`, Wire codegen fails.
```
Per `CLAUDE.md` (protected, current ground truth): *"The BFF consumes it as a
**Maven artifact from GitHub Packages** (`xyz.ksharma.krail:api-proto`), not a git
submodule."* Confirmed in code:
```
$ grep krail-api-proto gradle/libs.versions.toml
krail-api-proto = "0.4.2"
krail-api-proto = { module = "xyz.ksharma.krail:api-proto", version.ref = "krail-api-proto" }
$ cat .gitmodules
cat: .gitmodules: No such file or directory
```
No submodule exists in this repo. This pitfall entry is stale.

### 5. Archive candidate — `docs/reference/API_SCHEMA_DESIGN.md`
The doc opens: *"This is a **review doc**. Nothing here is implemented yet. Sign
off on the shape first; the build follows."* — and describes proto vendoring via
git submodule (`// settings.gradle.kts — vendor via git submodule at
krail-api-proto/`, line 420).

Both claims are verifiably false today:
```
$ ls server/src/main/kotlin/app/krail/bff/mapper/
DepartureMapper.kt  JourneyListMapper.kt  ParkingProtoMapper.kt
$ git log --diff-filter=A --format=%cd --date=short -- server/src/main/kotlin/app/krail/bff/mapper/ | tail -1
2026-06-11
```
The mapper layer this doc proposes has been implemented for over a month, and the
proto is consumed via Maven/GitHub Packages, not the submodule this doc designs
around (same evidence as #4). Recommend archiving with a tombstone noting it's
superseded by the shipped mapper layer (`server/.../mapper/`) and the Maven-artifact
proto flow documented in `CLAUDE.md`.

### 6. Stale proto-consumption description — `docs/reference/BFF_ADOPTION_GUIDE.md`
```
30: - [x] **`krail-api-proto` repo exists and is pinned** — done 2026-05-09. ...
     KRAIL-BFF consumes via submodule at `krail-api-proto/`. ...
59: git submodule update --remote krail-api-proto
```
Same submodule-vs-Maven-artifact contradiction as #4/#5. This doc is otherwise
current (all other links resolve correctly to `../archive/MODERNIZATION_PLAN.md`
etc.) — only the proto-consumption mechanism needs updating in the prerequisites
checklist and step 1 of the migration playbook.

### 7. Fix broken link — `docs/reference/DEPLOYMENT.md:3`
```
> ... Pairs with [MODERNIZATION_PLAN.md](MODERNIZATION_PLAN.md) §0.3.
```
Relative to this file's own directory, this resolves to
`docs/reference/MODERNIZATION_PLAN.md`, which doesn't exist:
```
$ ls docs/reference/MODERNIZATION_PLAN.md
ls: cannot access 'docs/reference/MODERNIZATION_PLAN.md': No such file or directory
```
The file is at `docs/archive/MODERNIZATION_PLAN.md`. Every other doc in
`docs/reference/` that links to it uses the correct `../archive/MODERNIZATION_PLAN.md`
form (see `API_SCHEMA_DESIGN.md`, `BFF_ADOPTION_GUIDE.md`, `SCREEN_DATA_INVENTORY.md`)
— this is the one outlier.

## Index/README gaps (lower priority — not itemized as standalone actions this run)

`docs/README.md` and `docs/index.md` (the GitHub-render and GitHub-Pages home
pages) both list a "Design & planning" table that includes the now-archived
`MODERNIZATION_PLAN.md` but omits `docs/reference/TRACKING_DESIGN.md`,
`docs/reference/ROADMAP.md`, and all of `docs/handover/`. `docs/README.md`'s
"Guides" table also omits `FIRST_DEPLOY.md` and `EMERGENCY.md`, which
`docs/index.md` does include. Deferred to a future run under priority 3
("update index and README files to match moves") once the higher-priority broken
links above are resolved — fixing the archive-link staleness first avoids
re-touching these tables twice.

## Coverage gaps

- `scripts/README.md`'s "Available Scripts" section documents only `dev.sh` and
  `test-trip-planning.sh`. Four more executable scripts exist undocumented in that
  file: `check-size.sh`, `compare-json-vs-proto.sh`, `quick-debug.sh`,
  `test-proto-endpoint.sh`, `tester.sh`. (`check-size.sh` and
  `test-proto-endpoint.sh` are referenced from other docs — `docs/guides/TESTING.md`,
  root `README.md` — just not from `scripts/README.md` itself.) Below the
  10-file coverage threshold for a new doc; this is an existing-doc gap, not a
  missing-doc gap.
- `server/src/main/kotlin/app/krail/bff/routes/` (10 files) and `.../plugins/`
  (11 files) meet the "10+ source files" coverage-duty threshold and have no
  dedicated doc. `routes/` is covered piecemeal (endpoint tables in
  `docs/handover/README.md`, `CONFIGURATION.md`), and `plugins/` piecemeal via
  `SECURITY.md`/`CONFIGURATION.md`, but neither has a "what is this directory"
  doc. Candidate for a small factual doc in a future `active` run (target: under
  60 lines, per charter).

## Informational — not actionable (protected files)

`CLAUDE.md`'s module-layout table (protected, no action taken) lists `route/`
(singular) and a separate `trackdata/` directory. Actual structure has `routes/`
(plural) and no standalone `trackdata/` — `TrackDatasetStore.kt` lives inside
`track/`:
```
$ find server/src/main/kotlin/app/krail/bff -maxdepth 1 -type d
.../client .../config .../data .../di .../mapper .../model .../plugins .../routes .../tools .../track .../util
$ find server -iname "*trackdata*"
server/src/main/kotlin/app/krail/bff/track/TrackDatasetStore.kt
```
Noted for the human to fix directly, since `CLAUDE.md` content is off-limits to
this agent.

## Deferred to next run

Nothing was deferred for budget reasons this run (report-only mode produces no
content edits, so the 500-line cap wasn't a constraint) — this AUDIT.md itself is
the full output. All seven proposed actions above are candidates for the first
`active`-mode run.
