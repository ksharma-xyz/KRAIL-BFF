# On-Device Routing — KMP Implementation Guide

Audience: the KRAIL (KMP client) side. This documents how to run the BFF's
RAPTOR journey planner on-device — what ports as-is, what must change, the
mobile snapshot format, budgets, and a staged "give it a go" plan with
measurable acceptance gates.

Decision context: **hybrid, staged**. Server-side routing ships first (see
[VIC_ROUTER_DESIGN.md](VIC_ROUTER_DESIGN.md)); on-device is a later offline
differentiator. The server keeps three jobs in every version of this:
realtime (upstream keys must never ship in a binary, and e.g. VIC's
24 req/min cap is shared across *all* users), GTFS ingest (the ~255 MB weekly
parse never happens on a phone), and freshness distribution.

## Why the engine is already mobile-shaped

The core was built as flat primitive arrays with an allocation-free hot loop —
which is exactly the on-device profile:

- Scans are sequential over `IntArray`s (cache-friendly, no pointer chasing,
  no per-stop-time objects).
- Measured server-side (Apple Silicon, JVM): VIC metro p50 17 ms / p95
  24.6 ms over 24,894 stops / 9.4 M stop times at 134 MB heap. Phone-class
  single-core estimate: **~30–80 ms per query** — instant UX. Precedent: the
  Transit app runs on-device routing in production on the same algorithm
  class.
- Battery: millisecond bursts; negligible.

## What ports, what doesn't

| Piece | Port? | Notes |
|---|---|---|
| `router/RouterModel.kt` | as-is | pure Kotlin + arrays |
| `router/Raptor.kt` | as-is + one change | pool the per-query state (below) |
| `router/RouterSnapshot.kt` | rewrite IO layer | `java.io` → okio (`BufferedSource`); format logic unchanged |
| `router/GtfsParser.kt`, `GtfsCsv.kt`, `RouterModelBuilder.kt` | **do not port** | phones never parse GTFS — they consume prepared snapshots |
| `vic/`, region registry, routes, mappers | do not port | server concerns; client already renders `JourneyList` |

**Mandatory change — query-state pooling.** Each query currently allocates
~9 arrays × (rounds+1) × stopCount (≈5 MB scratch on the VIC model). Fine on
a server JVM, hostile to a phone GC. Hold ONE reusable state per router
instance and clear it between queries (arrays are already index-addressed;
clearing = `fill()` calls). Single-threaded per model instance is acceptable
on-device.

## Mobile snapshot format ("m1")

Derived from server snapshot v2 by the KRAIL-GTFS pipeline as an additional
artifact — the device never builds it. Three deltas, all mechanical:

1. **Narrow the times.** arrival/departure as u16 *minutes-since-service-day*
   (max 65,535 min ≫ the ~30 h GTFS day) or 16-bit deltas. Halves the
   dominant arrays. Router converts to seconds at the boundary; after-midnight
   semantics (values > 24 h) unchanged.
2. **Externalize strings.** Stop/route names + translations to a separate
   file (offset-indexed string table). RAPTOR never touches names during
   search; hydrate only the stops in returned journeys. Client-local stop
   search continues to use the existing stops dataset — it does NOT need the
   router's names.
3. **mmap-friendly layout.** Format is already flat arrays; keep sections
   aligned + length-prefixed so okio/`FileHandle` (or platform mmap via
   expect/actual) can page-in on demand. Resident memory then tracks pages
   touched, not file size.

Keep the v2 header discipline: magic, **format version (`m1` distinct from
server v2 — reader rejects mismatches)**, source dataset date, bounded length
prefixes (the anti-OOM guard ports as-is).

Expected result for metro Melbourne (from measured v2 numbers): **~35–45 MB
download, ~50–70 MB resident**, sub-second load. Brisbane ≈ 40 % of that;
NZ cities far smaller.

## Budgets / acceptance gates

| Metric | Gate (modern mid-range device) |
|---|---|
| Query p95 (200 random reachable pairs) | < 150 ms |
| Resident memory after 50 queries | < 100 MB |
| Snapshot open → first query | < 1 s |
| Snapshot download (metro Melbourne) | < 50 MB |
| Battery per plan | unmeasurable in practice |

Miss a gate → the levers, in order: verify state pooling is on; check K/N
boxing (see risks); shrink further (drop headsigns, per-mode sub-snapshots).

## Where the shared code lives

Recommended v1: **copy-port** `RouterModel` + `Raptor` + snapshot reader into
a KRAIL `commonMain` module (`:core:router`), with this doc's checklist. It is
~900 lines of pure logic; drift risk is real but small and the format version
handshake catches skew. Extracting a shared multiplatform library published
like `api-proto` is the right end state — do it after M0 proves the numbers,
not before.

## Staged plan — "give it a go"

### M0 — benchmark harness (1–2 evenings, decides everything)
Port the three files to `commonMain` (expect/actual for file IO via okio).
Build one narrowed VIC snapshot (pipeline-side script exists conceptually;
ask the BFF side — narrowing is a ~day of work there). Ship a debug-only
screen: load snapshot → run 200 random reachable queries + the canonical
pairs (Flinders St→Melbourne Central etc.) → print p50/p95, resident memory,
load time. Run on a real Android device AND a real iPhone (K/N is the
unknown, not ART).
**Gate:** table above. If iOS misses badly, investigate boxing first (below)
before concluding anything.

### M1 — offline planning behind a flag (after M0 passes)
Snapshot download manager (WiFi-preferred, resumable, sha256 from the
manifest the pipeline already publishes; delete-on-corrupt). Plan screen:
try server (fresh + realtime-ready) → fall back to on-device when offline or
slow; badge offline results as "timetable only". Same `JourneyList`-shaped
render path — construct the same UI models the proto mapper produces.
**Gate:** airplane-mode Melbourne trip plans correctly; server result
preferred whenever reachable.

### M2 — freshness + polish
Manifest-checked weekly snapshot refresh (piggyback the existing dataset
manifest pattern), staleness UX (data older than N days → banner; past
calendar end → refuse with a clear message, never guess), delta downloads if
size ever hurts, then consider sharing the extracted library.

## Risks / gotchas (read before M0)

- **Kotlin/Native perf:** tight `IntArray` loops are normally fine, but K/N
  can box in surprising places — keep the hot loop free of interfaces,
  lambdas, and `Int?`; prefer top-level functions over virtual dispatch.
  Benchmark before optimizing; don't assume ART numbers transfer.
- **iOS memory pressure:** jetsam kills silently. Respond to memory-warning
  notifications by dropping the model (reload lazily); never hold two
  snapshots during swap — swap file paths, reload.
- **Version skew:** device snapshot format `m1` + app must handshake; an old
  app must cleanly refuse a newer snapshot (same rule as server v2 — reject,
  never misread).
- **Staleness is a correctness issue:** an out-of-date snapshot produces
  confidently wrong journeys. Calendar-bounded refusal + visible data-date
  beats silent wrongness. Server-side answer remains the source of truth
  whenever the network exists.
- **Don't ship GTFS parsing to the phone** — if a future feature seems to
  need it, the answer is a new pipeline artifact, not a mobile parser.
- **Realtime stays server-side.** No upstream key in the binary, ever; no
  per-device polling of agency feeds (rate caps are shared and finite).

## Pointers

- Engine design + invariants: [VIC_ROUTER_DESIGN.md](VIC_ROUTER_DESIGN.md)
  (FIFO flags, 3-service-day scan, Pareto semantics — all apply on-device
  unchanged).
- Multi-region facts + benchmarks: [WORLD_REGIONS_NOTES.md](WORLD_REGIONS_NOTES.md).
- Dataset/manifest distribution pattern the downloads should reuse:
  `track/TrackDatasetStore.kt` (server) + the stops-dataset manifest flow in
  [KRAIL_INTEGRATION.md](KRAIL_INTEGRATION.md).
