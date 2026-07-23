# Docs Gardener Audit — 2026-07-19

Run mode: **report-only** (per charter Part B). No doc content was changed by this
run. This file records the classification, staleness re-verification, and proposed
actions for a future `active` run.

## What changed since the last audit (2026-07-17)

```
$ git log --oneline c2ddadb..HEAD
5092fbe docs: add vulnerability reporting policy to SECURITY.md (#86)
$ git diff --stat c2ddadb..HEAD
 SECURITY.md | 21 +++++++++++++++++++++
 1 file changed, 21 insertions(+)
```

Only `SECURITY.md` (protected) changed, and only by addition — no content this
agent proposed to touch was edited. All seven findings from the 2026-07-17 audit
were re-verified against the current tree and still hold, unaddressed. This run
re-confirms them with fresh evidence rather than re-deriving from scratch, and adds
no new findings beyond what 2026-07-17 already surfaced.

## Feedback ingested

Last 5 `docs-gardener`-labeled PRs (only 3 exist): #85, #84, #82. All merged (none
closed-unmerged), zero issue comments, zero review comments, zero reviews on any of
the three. No `charter:` instructions found. No Steering Log entries added this run.

## Charter Part A drift

Diffed this repo's `.github/docs-gardener/CHARTER.md` against `ksharma-xyz/KRAIL`'s
copy (read-only shallow clone, `ee8e382`). **No drift** — every line above
`## Part B: Repo Overrides` is byte-identical between the two repos. Diff output
was confined entirely to Part B (expected — repo-specific by design):
protected-file lists, ux-contract lists, archive-location wording, sibling-repo
name, and routine IDs/times all differ appropriately.

## Classification table

| File | Class | Notes |
|---|---|---|
| `CLAUDE.md` | reference (protected) | No action — protected file. |
| `SECURITY.md` | reference (protected) | No action — protected file; grew by 21 lines since last audit (vulnerability reporting policy), still protected. |
| `TODO.md` | ledger (protected) | No action — protected file; used as ground truth for staleness checks below. |
| `README.md` | guide | All linked docs resolve. No action. |
| `DEPLOY_CHECKLIST.template.md` | guide | **Broken link, unresolved** — see Proposed actions #1. |
| `docs/README.md` | reference (index) | Index gaps — see Index/README gaps. |
| `docs/index.md` | reference (index) | Index gaps — see Index/README gaps. |
| `docs/guides/index.md` | reference (nav stub) | Fine. |
| `docs/guides/DEBUGGING.md` | guide | Links resolve. No action. |
| `docs/guides/EMERGENCY.md` | guide | Links resolve. Consistent with current (pre-deploy) state. No action. |
| `docs/guides/FIRST_DEPLOY.md` | guide | Links resolve; consistent with `TODO.md` "nothing deployed yet". No action. |
| `docs/guides/LOCAL_DEVELOPMENT.md` | guide | Project-structure listing accurate but incomplete (see Coverage gaps). No action needed. |
| `docs/guides/TESTING.md` | guide | **Broken link, unresolved** — see Proposed actions #2. |
| `docs/handover/README.md` | guide | **Broken links + stale content, unresolved** — see Proposed actions #3. |
| `docs/handover/MIGRATION_GUIDE.md` | guide | **Broken links + stale proto-consumption description, unresolved** — see Proposed actions #4. |
| `docs/handover/TRACKING_INTEGRATION.md` | guide | Re-verified: `./scripts/dev.sh`, `docs/tools/track-tester.html`, `server/src/test/resources/gtfsrt/` all still exist. No action. |
| `docs/reference/index.md` | reference (nav stub) | Fine. |
| `docs/reference/API_SCHEMA_DESIGN.md` | plan | **Stale — describes a superseded design, unresolved** — see Proposed actions #5. |
| `docs/reference/BFF_ADOPTION_GUIDE.md` | guide | **Stale proto-consumption description, unresolved** — see Proposed actions #6. |
| `docs/reference/CONFIGURATION.md` | reference | Re-spot-checked `NSW_API_KEY`, `NSW_DAILY_BUDGET`, `MIN_APP_VERSION`, `BFF_PER_IP_RPS`, `CF_ORIGIN_TOKEN`, `STOPS_MANIFEST_URL` against `server/src/main/resources/application.yaml` — all present and consistent. No action. |
| `docs/reference/DEPLOYMENT.md` | guide | **Broken link, unresolved** — see Proposed actions #7. |
| `docs/reference/ROADMAP.md` | plan | Forward-looking, post-deploy roadmap; deploy hasn't happened yet per `TODO.md`. No action. |
| `docs/reference/SCREEN_DATA_INVENTORY.md` | reference | Cross-repo re-check: named KRAIL-side mapper classes (`TripResponseMapper`, `DepartureMonitorMapper`, `JourneyMapMapper`, `GtfsRealtimeMatcher`, `JourneyListMapper`, etc.) re-verified present in `ksharma-xyz/KRAIL` (fresh shallow clone, `ee8e382`). No action. |
| `docs/reference/TRACKING_DESIGN.md` | ux-contract | No broken links or dated claims found. Still silent on the injected-clock testing invariant CLAUDE.md calls out — plausibly out of scope (design-rationale doc, not a testing doc). Flagged, no action proposed. |
| `docs/tools/README.md` | guide (protected: `docs/tools/**`) | `./scripts/tester.sh` exists. No action — protected. |
| `docs/tools/bruno/README.md` | guide (protected: `docs/tools/**`) | No action — protected. |
| `scripts/README.md` | guide | Coverage gap, unresolved — see Coverage gaps. |
| `docs/archive/*.md` (11 files) + `docs/archive/README.md` | archive | All moved into archive on 2026-07-04 (15 days ago) — still well under the 90-day hard-delete threshold, and all have live inbound references from non-archive docs. No delete candidates this run. |
| `.github/docs-gardener/CHARTER.md` | **unclassifiable** | Same as last run: doesn't fit the taxonomy (it's the gardener's own operating policy). Taxonomy addition (e.g. `policy`) still proposed for a future run; no action taken on the file. |
| `.github/docs-gardener/AUDIT.md` | **unclassifiable** | This file itself — same reasoning as `CHARTER.md`. Overwritten each run by design, not archived. |

## Proposed actions (priority order, none applied — report-only)

### 1. Fix broken link — `DEPLOY_CHECKLIST.template.md:260`
```
- [ ] Update `STATUS.md` section 1 to reflect deploy done.
```
`STATUS.md` still doesn't exist at repo root — archived to `docs/archive/STATUS.md`
on 2026-07-04. Re-confirmed today:
```
$ ls STATUS.md
ls: cannot access 'STATUS.md': No such file or directory
```
Proposed fix (unchanged from 2026-07-17): point at `TODO.md` (its designated
successor per the archive tombstone table) instead.

### 2. Fix broken link — `docs/guides/TESTING.md:256`
```
- [Protobuf Integration](PROTOBUF)
```
No `PROTOBUF.md` (or any protobuf-named doc) exists anywhere in the repo.
Re-confirmed today:
```
$ find docs -iname "*protobuf*"
(no output)
```
Proposed fix (unchanged): remove the dead link or point it at
`docs/reference/API_SCHEMA_DESIGN.md`, with a caveat that it's a design doc, not a
shipped integration guide.

### 3. Fix broken links + stale content — `docs/handover/README.md`
```
101: - [`../../STATUS.md`](../../STATUS.md) — what's on `main`, what's
103: - [`../../START.md`](../../START.md) — how to run the BFF locally.
```
Both still resolve to repo root; neither file exists there. Also:
```
104-106: [`../reference/`](../reference/) — long-form design docs
          (`MODERNIZATION_PLAN`, `API_SCHEMA_DESIGN`, `BFF_ADOPTION_GUIDE`,
          `DEPLOYMENT`).
```
`MODERNIZATION_PLAN.md` is still not under `docs/reference/` — it's under
`docs/archive/`. Re-confirmed today:
```
$ ls docs/reference/MODERNIZATION_PLAN.md
ls: cannot access 'docs/reference/MODERNIZATION_PLAN.md': No such file or directory
$ ls docs/archive/MODERNIZATION_PLAN.md
docs/archive/MODERNIZATION_PLAN.md
```
The "Pick your doc" table (lines 40-43) still recommends `API_REFERENCE.md`,
`TESTING_GUIDE.md`, and the Phase C report as primary reading — all archived per
`docs/archive/README.md`'s tombstone table. No change from last run.

### 4. Fix broken links + stale proto-consumption model — `docs/handover/MIGRATION_GUIDE.md`
```
27:  see `STATUS.md` in repo root).
524: For any of those, the canonical read is `STATUS.md` + the docs
```
Same broken-root-file issue as #1/#3, still present.

The "Common pitfalls" section (line 444) still reads:
```
- **Forgetting `submodules: true` on `actions/checkout`.** CI
  doesn't fetch `krail-api-proto/`, Wire codegen fails.
```
Per `CLAUDE.md` (protected, current ground truth): the BFF consumes the proto as a
Maven artifact from GitHub Packages, not a git submodule. Re-confirmed today:
```
$ grep krail-api-proto gradle/libs.versions.toml
krail-api-proto = "0.4.2"
krail-api-proto = { module = "xyz.ksharma.krail:api-proto", version.ref = "krail-api-proto" }
$ ls .gitmodules
ls: cannot access '.gitmodules': No such file or directory
```
No submodule exists in this repo. This pitfall entry is still stale.

### 5. Archive candidate — `docs/reference/API_SCHEMA_DESIGN.md`
Still opens: *"This is a **review doc**. Nothing here is implemented yet."* — and
still describes proto vendoring via git submodule (line 420,
`// settings.gradle.kts — vendor via git submodule at krail-api-proto/`).

Both claims are still verifiably false. Re-confirmed today:
```
$ ls server/src/main/kotlin/app/krail/bff/mapper/
DepartureMapper.kt  JourneyListMapper.kt  ParkingProtoMapper.kt
$ git log --diff-filter=A --format=%cd --date=short -- server/src/main/kotlin/app/krail/bff/mapper/ | tail -1
2026-06-11
```
The mapper layer this doc proposes has been shipped for over a month, and the
proto is consumed via Maven/GitHub Packages (same evidence as #4). Recommendation
unchanged: archive with a tombstone noting it's superseded by the shipped mapper
layer and the Maven-artifact proto flow documented in `CLAUDE.md`.

### 6. Stale proto-consumption description — `docs/reference/BFF_ADOPTION_GUIDE.md`
```
30: - [x] **`krail-api-proto` repo exists and is pinned** — done 2026-05-09. ...
     KRAIL-BFF consumes via submodule at `krail-api-proto/`. ...
59: git submodule update --remote krail-api-proto
```
Same submodule-vs-Maven-artifact contradiction as #4/#5, still present. Doc is
otherwise current — all other links still resolve correctly.

### 7. Fix broken link — `docs/reference/DEPLOYMENT.md:3`
```
> ... Pairs with [MODERNIZATION_PLAN.md](MODERNIZATION_PLAN.md) §0.3.
```
Relative to this file's directory, still resolves to a nonexistent
`docs/reference/MODERNIZATION_PLAN.md`. Re-confirmed today (see #3's evidence).
The correct target is `docs/archive/MODERNIZATION_PLAN.md`, as every sibling doc
in `docs/reference/` already links it (`../archive/MODERNIZATION_PLAN.md`) — this
file remains the one outlier.

## Index/README gaps (lower priority — not itemized as standalone actions this run)

Unchanged from 2026-07-17: `docs/README.md` and `docs/index.md` both list a
"Design & planning" table that includes the now-archived `MODERNIZATION_PLAN.md`
but omits `docs/reference/TRACKING_DESIGN.md`, `docs/reference/ROADMAP.md`, and
all of `docs/handover/`. `docs/README.md`'s "Guides" table also omits
`FIRST_DEPLOY.md` and `EMERGENCY.md`. Deferred to a future run under priority 3
once the higher-priority broken links (#1, #3, #7) are resolved, to avoid
re-touching these tables twice.

## Coverage gaps

- `scripts/README.md`'s "Available Scripts" section still documents only
  `dev.sh` and `test-trip-planning.sh`. Still-undocumented in that file:
  `check-size.sh`, `compare-json-vs-proto.sh`, `quick-debug.sh`,
  `test-proto-endpoint.sh`, `tester.sh`. Re-confirmed today:
  ```
  $ ls scripts/*.sh
  scripts/check-size.sh scripts/compare-json-vs-proto.sh scripts/dev.sh
  scripts/quick-debug.sh scripts/test-proto-endpoint.sh
  scripts/test-trip-planning.sh scripts/tester.sh
  ```
  Below the 10-file coverage threshold for a new doc; this remains an
  existing-doc gap, not a missing-doc gap.
- `server/src/main/kotlin/app/krail/bff/routes/` (10 files) and `.../plugins/`
  (11 files) still meet the "10+ source files" coverage-duty threshold with no
  dedicated doc. Re-confirmed today (file counts unchanged from last audit).
  Candidate for a small factual doc (under 60 lines) in a future `active` run.

## Informational — not actionable (protected files)

Unchanged from 2026-07-17: `CLAUDE.md`'s module-layout table (protected, no
action taken) lists `route/` (singular) and a separate `trackdata/` directory.
Actual structure still has `routes/` (plural) and no standalone `trackdata/` —
`TrackDatasetStore.kt` lives inside `track/`. Re-confirmed today:
```
$ find server/src/main/kotlin/app/krail/bff -maxdepth 1 -type d
.../client .../config .../data .../di .../mapper .../model .../plugins .../routes .../tools .../track .../util
$ find server -iname "*trackdata*"
server/src/main/kotlin/app/krail/bff/track/TrackDatasetStore.kt
```
Noted for the human to fix directly, since `CLAUDE.md` content is off-limits to
this agent.

## Deferred to next run

Nothing deferred for budget reasons (report-only mode produces no content edits).
All seven proposed actions above, plus the two coverage gaps, remain candidates
for the first `active`-mode run — none have been applied yet across two
consecutive report-only audits (2026-07-17, 2026-07-19).
