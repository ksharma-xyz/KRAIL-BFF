# Proto extraction — what's done & what KRAIL-BFF still needs to do

> Companion to `docs/handover/KRAIL_APP_INTEGRATION_HANDOVER.md` and the
> KRAIL app's `KRAIL_API_PROTO_EXTRACTION_REQUEST.md`. This doc captures
> the state of the extraction work as of **2026-05-09** and the
> remaining BFF-side changes.

---

## §1 · Status

| Area | Status |
|---|---|
| New repo created | ✅ <https://github.com/ksharma-xyz/KRAIL-API-PROTO> (public, Apache 2.0) |
| Three protos moved | ✅ trip / stops_dataset / routes_dataset under `proto/api/` and `proto/data/` |
| `// contract:` annotations | ✅ Every field annotated `required` or `optional` per the convention |
| CI (lint + breaking-check) | ✅ `buf v1.47.2`, runs on every PR + push to main |
| Release automation | ✅ `release.yml` — tag-driven + workflow_dispatch bump-PR mode |
| Pages site | ✅ <https://ksharma-xyz.github.io/KRAIL-API-PROTO/> (Jekyll cayman theme) |
| `v0.1.0` cut | ✅ <https://github.com/ksharma-xyz/KRAIL-API-PROTO/releases/tag/v0.1.0> + tarball asset |
| Branch protection | ✅ Required status checks, no force-push, linear history |
| **BFF submodule swap** | ❌ **Not done — see §3** |
| KRAIL-BFF docs path updates | ❌ Not done — `STATUS.md`, `BFF_ADOPTION_GUIDE.md` still reference `server/src/main/proto/` |

---

## §2 · What was done in the new repo

### Layout

```
KRAIL-API-PROTO/
├── README.md                                Submodule wiring + versioning rules
├── LICENSE                                  Apache 2.0 (matches KRAIL app)
├── version.txt                              0.1.0
├── buf.yaml                                 buf v2 config; STANDARD lint w/ minor exceptions
├── .gitignore
├── proto/
│   ├── api/
│   │   └── trip.proto                       JourneyList + screen-shaped trip results
│   └── data/
│       ├── stops_dataset.proto              StopsDataset (TransportMode lives here)
│       └── routes_dataset.proto             RoutesDataset (imports stops_dataset.proto)
├── docs/                                    Jekyll site published to GH Pages
│   ├── _config.yml
│   ├── index.md
│   ├── getting-started.md                   Submodule + Wire snippets (BFF + KMP)
│   ├── contract.md                          required vs optional convention
│   ├── versioning.md                        SemVer bump table
│   ├── backward-compatibility.md            Practical rules + worked examples
│   ├── testing.md                           Three layers of enforcement
│   └── releasing.md                         How to cut a new version
└── .github/workflows/
    ├── ci.yml                               buf lint + buf build + buf breaking + version sanity
    ├── release.yml                          Tag push → release; workflow_dispatch → bump-PR
    └── pages.yml                            Auto-deploy docs/ to GH Pages
```

### Layout note — why two subdirectories

The original BFF protos used two packages (`app.krail.bff.proto` for trip,
`app.krail.bff.proto.data` for the datasets) under one flat directory.
buf's `PACKAGE_SAME_DIRECTORY` lint rejects mixed packages in one directory.
The fix was to split: `proto/api/` for `app.krail.bff.proto`, `proto/data/`
for `app.krail.bff.proto.data`. **Wire compatibility is unchanged** — package
names and field numbers are identical. Only the file paths and the
`import` statement in `routes_dataset.proto` changed.

### Contract annotations

Every field has a comment of the form:

```proto
// contract: required — display string like "in 5 mins" / "12:30pm".
string time_text = 1;

// contract: optional — only set for trains/metros where platform is known.
optional string platform_text = 2;
```

The convention is documented in
<https://ksharma-xyz.github.io/KRAIL-API-PROTO/contract>. Server-side
defaults for `contract: required` fields when upstream data is missing
are tabulated there too.

### Release automation

Two flows. Both produce a GitHub Release with auto-generated notes
(categorised by PR labels — `breaking-change` / `enhancement` / `bug` /
`documentation`) and a `proto/` tree tarball asset.

1. **workflow_dispatch (recommended):** Actions → `release.yml` → Run
   workflow → pick `patch/minor/major`. Workflow opens a release PR
   bumping `version.txt`. Merge → push tag → release fires.
2. **Manual:** edit `version.txt`, commit, push, tag, push tag.

CI runs `buf breaking --against` the most recent tag on every PR. To
land a wire-breaking change you must label the PR `major-bump`.

---

## §3 · What KRAIL-BFF needs to do (the swap)

This is the work captured by **task #15** in the session task list. Do
it on a **new branch stacked on `dashboard`** (the user prefers stacking
to opening branches off main while the existing PR stack is still
unmerged — they'll rebase-and-merge the latest tip into main when ready).

### §3.1 · Add the submodule

```bash
cd /Users/ksharma/code/apps/KRAIL-BFF
git switch -c proto-submodule          # off `dashboard`
git submodule add https://github.com/ksharma-xyz/KRAIL-API-PROTO.git krail-api-proto
git -C krail-api-proto checkout v0.1.0
git add .gitmodules krail-api-proto
git commit -m "chore: add KRAIL-API-PROTO submodule pinned to v0.1.0"
```

After this, `git status` should be clean and `cat krail-api-proto/version.txt`
prints `0.1.0`.

### §3.2 · Point Wire at the submodule

Edit `server/build.gradle.kts`. The current Wire block is at line ~115:

```kotlin
// BEFORE:
wire {
    kotlin {
        // ...
    }
    sourcePath {
        srcDir("src/main/proto")
    }
}

// AFTER:
wire {
    kotlin {
        // ...
    }
    sourcePath {
        // The .proto files live in the KRAIL-API-PROTO submodule. Wire
        // resolves imports relative to this directory, so both proto/api/
        // and proto/data/ are visible.
        srcDir("$rootDir/krail-api-proto/proto")
    }
}
```

### §3.3 · Delete the in-tree protos

```bash
git rm -r server/src/main/proto/
```

The Wire codegen output under `server/build/generated/source/wire/` regenerates
on the next `:server:compileKotlin`, so don't worry about that path.

### §3.4 · Verify the build

```bash
./gradlew :server:clean :server:test
```

Expectations:

- `compileKotlin` succeeds (Wire finds the protos via the new `sourcePath`).
- All existing tests pass — package names and field numbers are
  unchanged so generated Kotlin classes look identical to before.
- If a test references a class by its FQN like
  `app.krail.bff.proto.JourneyList`, no changes needed.

If compilation fails because Wire reports "import not found", check
that `krail-api-proto/` exists at repo root and `git submodule update --init`
has run.

### §3.5 · Add the contract-enforcement unit tests

Per <https://ksharma-xyz.github.io/KRAIL-API-PROTO/testing#2-krail-bff-server-side-contract-enforcement>,
the BFF owes consumers a non-null value for every `contract: required`
field. Add one test per response message type:

```kotlin
// server/src/test/kotlin/.../proto/JourneyListContractTest.kt
package app.krail.bff.proto

class JourneyListContractTest {
    @Test
    fun `every contract-required field on JourneyCardInfo is non-null`() {
        val response = TripPlanService(StubNswClient.empty()).planTrip(...)

        response.journeys.shouldNotBeEmpty()
        response.journeys.forAll { j ->
            j.timeText.shouldNotBeNull()
            j.originTime.shouldNotBeNull()
            j.originUtcDateTime.shouldNotBeNull()
            j.destinationTime.shouldNotBeNull()
            j.destinationUtcDateTime.shouldNotBeNull()
            j.travelTime.shouldNotBeNull()
            j.transportModeLines.shouldNotBeNull()
            j.legs.shouldNotBeNull()
            j.totalUniqueServiceAlerts.shouldNotBeNull()

            // Genuinely-optional fields — DO NOT assert:
            // j.platformText, j.platformNumber, j.totalWalkTime, j.departureDeviation
        }
    }
}
```

The test fixture deliberately starves the response builder of upstream
data. Builders that rely on upstream without substituting a default
fail the test — exactly the enforcement the contract requires.

Repeat for every response message:

| Test class | Covers |
|---|---|
| `JourneyListContractTest` | `JourneyList`, `JourneyCardInfo`, `Leg`, `TransportLeg`, `WalkingLeg`, `TransportModeLine`, `Stop`, `WalkInterchange`, `ServiceAlert`, `DepartureDeviation` |
| `StopsDatasetContractTest` | `StopsDataset`, `DatasetStop`, `LatLng` |
| `RoutesDatasetContractTest` | `RoutesDataset`, `RouteGroup`, `RouteVariant`, `TripOption` |

For v0.1.0 these tests can be skeletons that just instantiate the response
builder against a stubbed NSW client and assert non-null on the required
fields — full upstream-failure injection can land in a follow-up.

### §3.6 · Add the auto-bump workflow

Create `.github/workflows/proto-bump.yml` in KRAIL-BFF:

```yaml
name: proto-bump

# Daily check + manual dispatch. Opens a PR if KRAIL-API-PROTO has a
# newer tag than the currently-pinned submodule SHA. Never auto-merges —
# schema changes need a human in the loop.

on:
  schedule:
    - cron: '0 14 * * *'    # 14:00 UTC daily — adjust to your TZ if you like
  workflow_dispatch:

permissions:
  contents: write
  pull-requests: write

jobs:
  bump:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: true
          fetch-depth: 0
      - name: Find latest KRAIL-API-PROTO tag
        id: latest
        run: |
          cd krail-api-proto
          git fetch --tags --quiet
          latest=$(git tag --list 'v*.*.*' --sort=-version:refname | head -n1)
          current=$(git describe --tags --exact-match 2>/dev/null || git rev-parse --short HEAD)
          echo "latest=$latest" >> "$GITHUB_OUTPUT"
          echo "current=$current" >> "$GITHUB_OUTPUT"
      - name: Skip if already at latest
        if: steps.latest.outputs.latest == steps.latest.outputs.current
        run: |
          echo "Already at ${{ steps.latest.outputs.latest }} — nothing to do."
          exit 0
      - name: Open bump PR
        if: steps.latest.outputs.latest != steps.latest.outputs.current
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          LATEST: ${{ steps.latest.outputs.latest }}
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          branch="proto-bump/${LATEST}"
          git switch -c "$branch"
          cd krail-api-proto
          git checkout "$LATEST"
          cd ..
          git add krail-api-proto
          git commit -m "chore(proto): bump KRAIL-API-PROTO to ${LATEST}"
          git push --set-upstream origin "$branch"
          # Skip if a PR for this version already exists.
          if gh pr list --state open --search "head:$branch" --json number --jq 'length' | grep -q '^0$'; then
            gh pr create \
              --title "chore(proto): bump KRAIL-API-PROTO to ${LATEST}" \
              --body "Automated submodule bump from the daily proto-bump workflow.

      Diff: https://github.com/ksharma-xyz/KRAIL-API-PROTO/compare/${{ steps.latest.outputs.current }}...${LATEST}

      Existing CI runs on this PR — if any contract-enforcement test fails, populate the new \`contract: required\` fields in the response builders before merging." \
              --label "dependencies"
          fi
```

### §3.7 · Update existing CI workflows

In `.github/workflows/pr.yml` (or whichever file runs the test suite),
fetch submodules in the checkout step:

```yaml
- uses: actions/checkout@v4
  with:
    submodules: true   # add this
```

Otherwise CI checkout won't have `krail-api-proto/` and Wire codegen
will fail with "no protos found."

### §3.8 · Update doc path references

The following BFF docs reference `server/src/main/proto/` — update each
to point at `krail-api-proto/proto/` (or just remove the path mention
where it's incidental):

- `STATUS.md` §2 (Create krail-api-proto repo) → mark this done, link to the new repo + v0.1.0.
- `docs/reference/BFF_ADOPTION_GUIDE.md` — Prerequisites checklist first item should now read "✅ KRAIL-API-PROTO repo exists; pinned at v0.1.0."
- `docs/handover/KRAIL_API_REFERENCE.md` §8 (`/api/v1/trip/plan-proto`) — change path reference from `server/src/main/proto/trip.proto` to `krail-api-proto/proto/api/trip.proto`.

```bash
# Quick audit:
grep -rn "server/src/main/proto" docs/ STATUS.md README.md 2>/dev/null
```

### §3.9 · Run the full test suite + push

```bash
./gradlew :server:test
./gradlew :server:detekt   # if you have it
git push -u origin proto-submodule
```

Open a PR against `dashboard` (or whichever branch is your stack tip)
titled something like `chore(proto): swap server/src/main/proto for KRAIL-API-PROTO submodule`.
The user does the rebase-and-merge into main when the stack is ready
to land.

---

## §4 · After §3 is done

The submodule is in place; KRAIL-BFF reads its proto contract from a
versioned external source. From here:

1. **Daily auto-bump runs at 14:00 UTC.** When KRAIL-API-PROTO cuts
   `v0.2.0` — say, when screen-shaped messages from
   `API_SCHEMA_DESIGN.md §2` land — a PR opens automatically. Review,
   make any builder changes the new contract requires (driven by the
   contract-enforcement test failures), merge.
2. **KRAIL app's mirror task:** the KRAIL repo runs the same
   `proto-bump.yml` shape, reads from the same submodule path, runs
   `./scripts/fullQualityChecks.sh` to gate. That's tracked in
   `KRAIL/docs/bff-integration-plan.md` Phase C.
3. **Future schema work:** add new `.proto` files in KRAIL-API-PROTO
   under `proto/api/` (or `proto/data/` for static datasets), bump the
   minor version via `release.yml`, both consumers' auto-bump workflows
   pick it up.

---

## §5 · Things deliberately NOT done in v0.1.0

For the record (per request §7):

- Renaming or restructuring proto messages — `JourneyCardInfo` is still
  named `JourneyCardInfo`, fields keep their numbers, etc.
- Screen-shaped messages (`TripResultsResponse`, `DepartureBoardResponse`)
  — these exist in `API_SCHEMA_DESIGN.md §2` as designed-not-built; they
  land in `v0.2.0+` whenever the BFF builds them.
- Maven publishing — submodule pattern only.
- Making every field carry the `optional` keyword — proto3 already
  treats scalars as having default-without-presence, and adding `optional`
  to every field would be a large source-breaking diff for consumers.
  The contract enforcement (server unit tests + client mappers) is what
  the request actually needs; the keyword change is cosmetic and can
  land in a coordinated follow-up.

---

## §6 · Quick links

- Repo: <https://github.com/ksharma-xyz/KRAIL-API-PROTO>
- Docs site: <https://ksharma-xyz.github.io/KRAIL-API-PROTO/>
- v0.1.0 release: <https://github.com/ksharma-xyz/KRAIL-API-PROTO/releases/tag/v0.1.0>
- buf v2 lint config: [`buf.yaml`](https://github.com/ksharma-xyz/KRAIL-API-PROTO/blob/main/buf.yaml)
- CI workflow: [`.github/workflows/ci.yml`](https://github.com/ksharma-xyz/KRAIL-API-PROTO/blob/main/.github/workflows/ci.yml)
- Release workflow: [`.github/workflows/release.yml`](https://github.com/ksharma-xyz/KRAIL-API-PROTO/blob/main/.github/workflows/release.yml)
- KRAIL app's original request: `KRAIL/docs/KRAIL_API_PROTO_EXTRACTION_REQUEST.md`
- Companion handover docs: [`KRAIL_APP_INTEGRATION_HANDOVER.md`](KRAIL_APP_INTEGRATION_HANDOVER.md), [`KRAIL_API_REFERENCE.md`](KRAIL_API_REFERENCE.md)
