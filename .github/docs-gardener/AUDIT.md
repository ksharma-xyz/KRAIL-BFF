# Docs Gardener Audit — 2026-08-16

Run mode: **report-only** (per charter Part B). No doc content was changed by this
run. This file records the classification, staleness re-verification, and proposed
actions for a future `active` run.

## What changed since the last audit

The most recent docs-gardener run was PR #106 (2026-08-09), itself a near-duplicate
of the still-open PR #101 (2026-08-02). Both remain **open, unmerged** at the time
of this run. Nothing under this agent's remit has changed since PR #106:

```
$ git diff --stat 2033b85..HEAD
 .github/workflows/codeql.yml                | 8 ++++----
 .github/workflows/dependency-submission.yml | 4 ++--
 .github/workflows/pr.yml                    | 4 ++--
 3 files changed, 8 insertions(+), 8 deletions(-)
```

Same three CI workflow files as last run, all further Dependabot version bumps
(`da3a877`, `3983f72`) — zero markdown files touched. Back to the last *merged*
audit, #98 (2026-07-26, `1caeff0`):

```
$ git diff --stat 1caeff0..HEAD -- '*.md'
(no output)
```

No markdown file has changed anywhere in the repo since the last merged
docs-gardener PR, three weeks ago. Every finding below was re-verified fresh
against today's tree (grep/`ls`/`git log` output quoted per finding) rather than
carried forward untested; none changed.

## Feedback ingested

Last 5 `docs-gardener`-labeled PRs: #106 (open), #101 (open), #98, #90, #85 (all
merged). Checked issue comments, review comments, and reviews on all five —
zero on every one, including both open PRs. No `charter:` instructions found. No
Steering Log entries added this run.

## Charter Part A drift

Diffed this repo's `.github/docs-gardener/CHARTER.md` against `ksharma-xyz/KRAIL`'s
copy (fresh shallow clone, `7474fb9`). **No drift** — every line above `## Part B:
Repo Overrides` is byte-identical between the two repos.

## Classification table

| File | Class | Notes |
|---|---|---|
| `CLAUDE.md` | reference (protected) | No action — protected file; unchanged since last audit. |
| `SECURITY.md` | reference (protected) | No action — protected file, unchanged since last audit. |
| `TODO.md` | ledger (protected) | No action — protected file. Line 43 still disagrees with `FIRST_DEPLOY.md`'s "Closed 2026-07-26" claim — see Informational note below. |
| `README.md` | guide | All linked docs resolve. No action. |
| `DEPLOY_CHECKLIST.template.md` | guide | **Broken link, unresolved** — see Proposed actions #1. |
| `docs/README.md` | reference (index) | Index gaps — see Index/README gaps. |
| `docs/index.md` | reference (index) | Index gaps — see Index/README gaps. |
| `docs/guides/index.md` | reference (nav stub) | Fine. |
| `docs/guides/DEBUGGING.md` | guide | Links resolve. No action. |
| `docs/guides/EMERGENCY.md` | guide | Links resolve. No action. |
| `docs/guides/FIRST_DEPLOY.md` | guide | **Stale content, unresolved** — see Proposed actions #8. |
| `docs/guides/LOCAL_DEVELOPMENT.md` | guide | Project-structure listing accurate but incomplete (see Coverage gaps). No action needed. |
| `docs/guides/TESTING.md` | guide | **Broken link, unresolved** — see Proposed actions #2. |
| `docs/handover/README.md` | guide | **Broken links + stale content, unresolved** — see Proposed actions #3. |
| `docs/handover/MIGRATION_GUIDE.md` | guide | **Broken links + stale proto-consumption description, unresolved** — see Proposed actions #4. |
| `docs/handover/TRACKING_INTEGRATION.md` | guide | Re-verified: `./scripts/dev.sh`, `docs/tools/track-tester.html`, `server/src/test/resources/gtfsrt/` all still exist. No action. |
| `docs/reference/index.md` | reference (nav stub) | Fine. |
| `docs/reference/API_SCHEMA_DESIGN.md` | plan | **Stale — describes a superseded design, unresolved** — see Proposed actions #5. |
| `docs/reference/BFF_ADOPTION_GUIDE.md` | guide | **Stale proto-consumption description, unresolved** — see Proposed actions #6. |
| `docs/reference/CONFIGURATION.md` | reference | No action. |
| `docs/reference/DEPLOYMENT.md` | guide | **Broken link, unresolved** — see Proposed actions #7. |
| `docs/reference/ROADMAP.md` | plan | Forward-looking, post-deploy roadmap; deploy hasn't happened yet per `TODO.md`. No action. |
| `docs/reference/SCREEN_DATA_INVENTORY.md` | reference | Cross-repo re-check: named KRAIL-side mapper classes (`TripResponseMapper`, `DepartureMonitorMapper`, `JourneyMapMapper`, `GtfsRealtimeMatcher`, `JourneyListMapper`) re-verified present in `ksharma-xyz/KRAIL` (fresh shallow clone, `7474fb9`). Both repos confirmed pinned to `krail-api-proto = "0.4.2"`. No action. |
| `docs/reference/TRACKING_DESIGN.md` | ux-contract | No broken links or dated claims found. Flagged (silent on the injected-clock testing invariant), no action proposed. |
| `docs/tools/README.md` | guide (protected: `docs/tools/**`) | No action — protected. |
| `docs/tools/bruno/README.md` | guide (protected: `docs/tools/**`) | No action — protected. |
| `scripts/README.md` | guide | Coverage gap, unresolved — see Coverage gaps. |
| `docs/archive/*.md` (11 files) + `docs/archive/README.md` | archive | All moved into archive on 2026-07-04 (43 days ago) — still well under the 90-day hard-delete threshold, and all have live inbound references. No delete candidates this run. |
| `.github/docs-gardener/CHARTER.md` | **unclassifiable** | Doesn't fit the taxonomy (it's the gardener's own operating policy). Taxonomy addition (e.g. `policy`) still proposed for a future run; no action taken. |
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
Proposed fix (unchanged): point at `TODO.md` (its designated successor per the
archive tombstone table) instead.

### 2. Fix broken link — `docs/guides/TESTING.md:256`
```
- [Protobuf Integration](PROTOBUF)
```
No `PROTOBUF.md` (or any protobuf-named doc) exists anywhere in the repo.
Re-confirmed today:
```
$ git ls-files | grep -i protobuf
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
`docs/archive/README.md`'s tombstone table. No change from prior runs.

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
The mapper layer this doc proposes has been shipped for over two months, and the
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

### 8. Stale "submodule" wording — `docs/guides/FIRST_DEPLOY.md:256-258`
```
256: - **Dataset pipeline** — lives in the KRAIL-GTFS repo (`track-dataset.yml`
257:   publishes the tracking datasets weekly); `proto-bump.yml` here PRs proto
258:   submodule updates.
```
Same submodule-vs-Maven-artifact contradiction as #4/#5/#6. The workflow it
describes says otherwise in its own header comment:
```
$ sed -n '1,11p' .github/workflows/proto-bump.yml
name: proto-bump
# Daily check + manual dispatch. Opens a PR if KRAIL-API-PROTO has a newer
# release tag than the version currently pinned in gradle/libs.versions.toml.
# ...
# Proto is consumed as a Maven artifact from GitHub Packages (no submodule).
```
Proposed fix: reword to "PRs `gradle/libs.versions.toml` proto-version bumps"
(or similar) — no submodule exists (same `.gitmodules` check as #4).

## Index/README gaps (lower priority — not itemized as standalone actions this run)

Unchanged from prior runs: `docs/README.md` and `docs/index.md` both list a
"Design & planning" table that includes the now-archived `MODERNIZATION_PLAN.md`
but omits `docs/reference/TRACKING_DESIGN.md`, `docs/reference/ROADMAP.md`, and
all of `docs/handover/`. `docs/README.md`'s "Guides" table also omits
`FIRST_DEPLOY.md` and `EMERGENCY.md` (confirmed present in `docs/index.md`'s
equivalent table, so this is a `docs/README.md`-specific gap, not a repo-wide
one). Deferred to a future run under priority 3 once the higher-priority broken
links (#1, #3, #7) are resolved, to avoid re-touching these tables twice.

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
  dedicated doc. Re-confirmed today (file counts unchanged from prior audits).
  Candidate for a small factual doc (under 60 lines) in a future `active` run.

## Informational — not actionable (protected files)

- `CLAUDE.md`'s module-layout table (protected, no action taken) lists `route/`
  (singular) and a separate `trackdata/` directory. Actual structure still has
  `routes/` (plural) and no standalone `trackdata/` — `TrackDatasetStore.kt` lives
  inside `track/`. Re-confirmed today, unchanged across seven consecutive audits:
  ```
  $ find server/src/main/kotlin/app/krail/bff -maxdepth 1 -type d
  .../client .../config .../data .../di .../mapper .../model .../plugins .../routes .../tools .../track .../util
  $ find server -iname "*trackdata*"
  server/src/main/kotlin/app/krail/bff/track/TrackDatasetStore.kt
  ```
- `TODO.md` (protected, ledger — no action taken) line 43 still lists, as an open
  manual action, *"(Recommended) Main ruleset: add `PR Build` as a required status
  check so direct pushes can't land with failing tests."* `docs/guides/FIRST_DEPLOY.md`
  (not protected) still says *"Closed 2026-07-26: the `main` ruleset now requires
  the `build` status check to pass."* These two docs still disagree on whether
  this item is open or done, unresolved across four consecutive audits
  (2026-07-26, 2026-08-02, 2026-08-09, 2026-08-16). This agent has no access to the
  actual GitHub branch-protection settings to arbitrate, and `TODO.md` is
  off-limits to edit regardless — flagged for the human to reconcile.

## Deferred to next run

Nothing deferred for budget reasons (report-only mode produces no content edits).
The eight proposed actions above, plus the two coverage gaps, remain candidates
for the first `active`-mode run — none have been applied yet across six
consecutive report-only audits (2026-07-17, 2026-07-19, 2026-07-26, 2026-08-02,
2026-08-09, 2026-08-16).

## Note on open duplicate audit PRs (#101, #106)

Both PR #101 (2026-08-02) and PR #106 (2026-08-09) are still open, unreviewed, and
unmerged at the time of this run — 14 and 7 days respectively. This run's findings
are, again, identical to both: nothing in the repo's markdown or the code it
describes has changed in three weeks. This is now the **third** consecutive
report-only run producing substantively the same audit while two earlier copies
sit unreviewed.

This agent has no authority to merge, close, or supersede those PRs, and the
charter's feedback mechanism (Steering Log via `charter:` PR comments) has not
been used to change this behavior, so this run followed the same process as
#106 rather than unilaterally skipping the PR. But three near-identical open PRs
is a signal worth the human's attention: either merge #101 or #106 (they're
equivalent; either closes the gap), or add a `charter:` comment telling this
agent to skip opening a new PR when no markdown has changed since the last
*open* (not just last *merged*) gardener PR. Absent that instruction, the next
run will very likely produce a fourth near-duplicate.
