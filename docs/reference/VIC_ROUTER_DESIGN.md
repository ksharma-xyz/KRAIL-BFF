# VIC Journey Planning — server-side router design

> Point-in-time build report for the original prototype:
> [`docs/archive/VIC_ROUTER_PROTOTYPE_REPORT_2026-08-25.md`](../archive/VIC_ROUTER_PROTOTYPE_REPORT_2026-08-25.md).
> This doc is the living reference; the archive is what was true on build night.
>
> Status: **prototype-to-production seed**. Code is on the serving path but
> feature-gated off everywhere until the dataset pipeline ships (§10).

---

## 1. Why the BFF computes routes itself

NSW has a journey-planner API; the BFF proxies it. **Victoria has none** —
only GTFS static (no key) and GTFS-Realtime (key). The same is true of the
other expansion targets (Brisbane, Adelaide: open GTFS, no planner API). So
outside NSW, journey planning must be computed, and it must be computed
server-side: multi-hundred-MB GTFS zips don't belong on phones, and the
KMP client's contract is already "BFF returns a `JourneyList` proto".

Consequence for the code layout: the router core (`router/`) is
**city-agnostic** — it consumes parsed GTFS feeds and knows nothing about
Victoria. Everything region-specific is one `RegionSpec` (code, display
name, timezone, stop-id prefix, feed zip name, canonical pairs) in
`region/RegionRegistry.kt`; the registry drives ingest, trip service,
route registration (`/api/v1/{code}/trip/plan-proto`), Router Lab wiring
and RouterBench. Adding a single-feed city = one registry entry + golden
tests — proven by Brisbane/SEQ
([`QLD_ROUTER_NOTES.md`](QLD_ROUTER_NOTES.md)) and then by five world
regions at once ([`WORLD_REGIONS_NOTES.md`](WORLD_REGIONS_NOTES.md):
Auckland, Wellington, Boston, Berlin/Brandenburg, Prague). VIC is the one
multi-feed region and keeps its bespoke folder-map ingest in `vic/`.

```
server/src/main/kotlin/app/krail/bff/
  router/    # city-agnostic: GTFS parse → compact model → RAPTOR → snapshot
  region/    # RegionSpec + RegionRegistry, single-feed RegionGtfsIngest,
             # RegionTripService (plan + diacritic-folded stop search)
  vic/       # VIC ingest only (folder map — the one multi-feed region)
  mapper/    # RegionJourneyListMapper (Journey → JourneyList proto, tz + prefix params)
             # JourneyListJson (JSON mirror for the Router Lab, dev-gated)
  routes/    # RegionTripRoutes (all plan-proto endpoints, feature-gated per region)
             # RouterLabRoutes (dev dashboard, see ROUTER_LAB.md)
  tools/     # RouterBench (offline build + benchmark, writes the snapshots)
```

## 2. Algorithm: RAPTOR

[RAPTOR](https://www.microsoft.com/en-us/research/publication/round-based-public-transit-routing/)
(Round-bAsed Public Transit routing) over the standard alternatives:

| Option | Why not |
|---|---|
| Dijkstra/A* on a time-expanded graph | Graph blow-up at 9.6M stop times; hard to get the transfers dimension |
| Connection Scan (CSA) | Simple, but whole-network scan per query; Pareto-by-transfers is bolted on |
| Contraction-hierarchy variants (TB, ULTRA) | Big preprocessing complexity budget; unnecessary at Melbourne's size |
| **RAPTOR** | No preprocessing beyond the compact model, naturally Pareto-optimal in (arrival × transfers), round structure maps 1:1 onto "0 transfers, 1 transfer, …" — exactly what journey cards show |

Round *k* holds the best arrival times using exactly *k* vehicle boardings.
Each round: collect patterns touching improved stops → scan each pattern
once, hopping onto the earliest catchable trip → relax footpaths from
ride-improved stops. Local pruning (`best[]` across rounds) + target pruning
(min over target platforms) keep scans short. `maxRounds` default 5.

City Loop note: no special-casing. Loop direction falls out of stop_times
order; Flinders St is first or last stop on loop trips; RAPTOR just scans
the pattern.

## 3. Compact data model

No per-StopTime objects — 9.6M rows live in flat primitive arrays
(`router/RouterModel.kt`). Trips are grouped into **patterns** (same route +
exact stop sequence); within a pattern trips sort by first-stop departure.

| Structure | Layout |
|---|---|
| Stop times | `arrivals`/`departures` IntArrays; slot = `patternTimesBase[p] + tripLocal * stopCount(p) + pos` |
| Stop-time flags | `stopTimeFlags` ByteArray parallel to arrivals: bit0 = no-pickup (`pickup_type=1`), bit1 = no-drop-off (`drop_off_type=1`) |
| Patterns | CSR: `patternStopsOffset`/`patternStops`, `patternTripsOffset`, `patternRoute` |
| FIFO flags | `patternFifo` boolean per (pattern, position) — see invariant I2 |
| Stop → patterns | CSR adjacency `stopAdjOffset`/`adjPattern`/`adjPosition` (all positions — loops appear twice) |
| Footpaths | CSR `transferOffset`/`transferTarget`/`transferSeconds` |
| Id lookup | `stopIdLookup: Map<String, IntArray>` — raw stop ids and station ids (`vic:rail:MCE` → child platforms) |
| Calendars | `ServiceCalendar[]`: weekday mask + date range + sorted added/removed exception dates |

Times are **seconds since the service-day midnight** and may exceed 24:00:00
(after-midnight service). All cross-references are int indices.

`frequencies.txt` is expanded at parse time: each row clones the template
trip once per headway step in `[start_time, end_time)` (times shifted to the
step; `exact_times=1` is exact schedule replication, absent/0 materialises
headway-based service the same way), and the template trip itself is dropped
— its stop_times are travel-time offsets, not a schedulable trip. Guards:
headway < 10s, end ≤ start and unknown trips are skipped; ≤ 5040 trips per
row. VIC/SEQ publish no frequencies; VBB and AT ship the file **empty** in
current drops, so the path is exercised by synthetic tests until a live feed
uses it.

The router enforces the stop-time flags: no boarding at no-pickup calls
(both the binary-search walk and the non-FIFO linear scan filter on the
flag) and no alighting at no-drop-off calls. When the earliest catchable
trip forbids a drop-off, an exact per-trip fallback scan recovers journeys
on later trips of the same pattern — the classic "earliest trip dominates"
argument breaks under alight restrictions (regression-tested in
`PickupDropOffTest`). `pickup_type`/`drop_off_type` 2/3 (phone / arrange
with driver) stay boardable.

Stations resolve to platforms at query time: source/target sets feed a
multi-source, multi-target RAPTOR run; best over the set wins.

## 4. Snapshot format

`router/RouterSnapshot.kt`. The model builds offline (`routerBench`) and
production loads the binary snapshot at startup — ~150ms vs ~3.3s full parse.

- Header: magic `KRVR` + `FORMAT_VERSION` int; then meta (source label,
  calendar span, built-at epoch), then every model array length-prefixed.
- **Bounded lengths**: every declared array length is validated against the
  file size before allocation. A truncated/corrupt snapshot fails with an
  exception at load — never a multi-GB allocation/OOM that would take the
  NSW serving path down with it (load failure logs + disables VIC routes
  only).
- **Version bump rule**: any change to array layout, ordering, or field set
  ⇒ bump `FORMAT_VERSION`. Reader rejects mismatches; there is deliberately
  no migration path — snapshots are rebuilt weekly, never migrated.

## 5. Calendars and the three service days

`calendar.txt` (weekday mask + range) merged with `calendar_dates.txt`
exceptions per service; services existing only via exceptions are supported.
`activeServices(date)` → BitSet over service indices.

A query on date **D** scans trips of three service days:

| Service day | Time offset | Catches |
|---|---|---|
| D-1 | −86,400s | Yesterday's after-midnight trips (25:30 = 01:30 today) |
| D | 0 | The normal day |
| D+1 | +86,400s | Journeys that roll past midnight into tomorrow's service |

This is why a 24:30 trip is boardable at 00:30 the next calendar day, and
why a query on a fully-cancelled day still finds next-morning journeys
(arrival > 86,400 in the query frame).

## 6. Footpath transfers

Discovery that shaped this: **VIC publishes zero usable stop-to-stop
transfers.** Folder 2's `transfers.txt` (13,229 rows, the only folder with
any) is entirely `transfer_type=4` — trip-to-trip in-seat continuations
(City Loop through-running), not footpaths. So every interchange edge is
generated:

| Constant | Value | Note |
|---|---|---|
| Proximity radius | 350 m | Grid-bucketed haversine, 3×3 cell neighbourhood |
| Walk speed | 1.33 m/s | |
| Interchange buffer | +60 s | Added to every generated edge |
| Same-stop transfer | 0 s | Known gap — see §11 |
| Stop-merge distance | 50 m | Same raw id across feeds + within 50 m ⇒ same physical stop |
| Curated precedence | `putIfAbsent` | If a future dataset ships real type-0/1/2 rows, they win over generated edges |

Types 3 (not possible), 4/5 (continuations) and trip-qualified rows are
skipped. Type-4 continuations are otherwise ignored: a through-running trip
counts as one extra "transfer" at the shared stop; journeys stay valid.

## 7. Melbourne feed quirks (and where each is absorbed)

The published archive is an outer zip of numbered folders, each with its own
`google_transit.zip`. Every folder claims `agency_id="1"` and id schemes
overlap, so **each folder is parsed as its own namespaced feed**; the model
builder merges stops across feeds only on same raw id + <50 m (which is
exactly what unifies the 42 tram/bus shared stop ids — verified all <50 m;
train↔tram and train↔bus id overlap is zero).

| Folder | Content | In scope |
|---|---|---|
| 1 | V/Line regional train | Yes (verified) |
| 2 | Metro train (route_type 400, hierarchical stations + platforms) | Yes |
| 3 | Tram (route_type 0) | Yes |
| 4 | Bus (route_types 3, 701; largest — ~7.5M stop-time rows) | Yes |
| 5 | Regional coach | Not yet (needs pickup/drop_off types, §11) |
| 6 | Regional bus | Not yet |
| 10 | Interstate | No |
| 11 | SkyBus | Yes |

| Quirk | Absorbed by |
|---|---|
| UTF-8 BOM on every file | `CsvReader` strips before header parse |
| Extended route types (400, 701, 102, 204, …) | Parser passes any int through; product-class mapping in the mapper |
| Blank intermediate stop times | Linear interpolation between timepoints; trips with blank endpoints dropped |
| Out-of-order times within a trip | Clamped non-decreasing |
| Quoted fields, CRLF, embedded commas/newlines | Streaming CSV state machine (599MB bus stop_times parses without per-field strings) |
| Night buses (routes 941–982, unlabelled) | Nothing needed — they're ordinary trips |
| **Trip overtaking within patterns** | **Per-position FIFO flags — the big one.** 29,703 of 129,966 pattern-positions (23%) have inverted departures across first-stop-sorted trips, mostly interleaved weekday/weekend timetables in one pattern. A plain binary search for "earliest catchable trip" provably *misses* trips at inverted positions (found in adversarial review, regression-tested). The builder verifies departure order at every position; binary search runs only where provably exact, inverted positions get a linear scan. |

## 8. Query semantics

`RaptorRouter.plan(fromId, toId, date, secondsSinceMidnight, maxRounds)` →
Pareto set over (arrival time, transfers): one journey per round that
strictly improves arrival. Fewer-transfers-but-slower and
more-transfers-but-faster coexist; equal-arrival extra-transfer journeys are
excluded.

Journeys reconstruct into the leg shape trip-view UIs show: vehicle legs
(board stop → alight stop, trip id, every intermediate stop call) with walk
legs between. Journey `depSec` = first boarding minus initial walk;
walk-only journeys are found by the router but dropped by the proto mapper
(no boarding to build a card from). `VicTripService` orders the proto list
fastest-arrival-first.

The public endpoint is `GET /api/v1/vic/trip/plan-proto` (allowlist
validation, `VIC:`-prefixed ids, protobuf out; JSON mirror is dev-gated —
see [ROUTER_LAB.md](ROUTER_LAB.md)). It registers only when
`VIC_ROUTER_SNAPSHOT` is configured.

## 9. Key invariants

| # | Invariant | Breaks if violated |
|---|---|---|
| I1 | Trips within a pattern are sorted by first-stop departure | Binary search precondition; snapshot readers assume it |
| I2 | `patternFifo` is recomputed by the builder and persisted in the snapshot; the router trusts it | Binary search at a falsely-FIFO position silently drops journeys (23% of VIC positions are non-FIFO) |
| I3 | Every query scans service days D-1/D/D+1 with ±86,400s offsets | After-midnight trips vanish on one side of midnight |
| I4 | Pruning uses monotone bounds only (`best[]` tightens, target bound tightens, rounds = transfer count ascending) | Non-monotone pruning deletes Pareto journeys |
| I5 | Curated `transfers.txt` edges take precedence over generated footpaths (`putIfAbsent` order) | Generated 60s-buffered edges would override curated interchange times |
| I6 | **The router core never reads a clock.** `plan()` takes explicit date + seconds; `VicTripService` uses the injected `clock` only for request defaults and card text | Same failure mode as `TrackService`: fixture-dated tests rot days after capture; irreproducible planning |
| I7 | `RouterSnapshot.FORMAT_VERSION` bumps on any array-layout change; reader rejects other versions; lengths bounded by file size | Old server + new snapshot = garbage arrays or startup OOM |
| I8 | Stop merging across feeds requires same raw id **and** <50 m; ambiguous ids (same id, distant stops) log a build warning | Silent wrong-stop departures on a bad weekly drop |

## 10. Measured performance

Apple Silicon laptop, JDK 17, `-Xmx3g`, dataset drop 2026-08-24 (calendar
2026-08-21 → 2026-11-22). Metro scope = folders 2+3+4+11: 24,894 stops,
799 routes, 3,502 patterns, 251,474 trips, 9,435,939 stop times, 146,696
transfer edges.

| Metric | Target | Measured |
|---|---|---|
| Build (parse + model, 4 feeds) | — | 3.3 s |
| Snapshot size / write / load | — | 87 MB / ~300 ms / **~150 ms** |
| Heap after load (post-GC) | < 400 MB | **120 MB** |
| Query p50 / p95 / p99 (200 random reachable pairs) | p95 < 100 ms | **16.0 / 22.7 / 25.5 ms** |

History worth knowing: the first cut benchmarked p95 9.3 ms with the naive
binary search — the 23% non-FIFO discovery (§7) made the linear fallback
mandatory and 22.7 ms is the honest number. Projecting per-service-day
timetables at query time would win most of it back if ever needed.

Adding V/Line (folder 1): +10.6k trips, heap ~123 MB, p95 24.5 ms — the
core absorbed a regional feed unchanged. Sanity journeys against real
timetables: Flinders St → Melbourne Central 7 min direct; Flagstaff →
Parliament 4 min; Southern Cross → Geelong 63 min direct V/Line.

Serving-path note: each query allocates its round state (~a few MB at VIC
scale). Fine at current latencies; pool per-thread if concurrent load ever
makes GC visible.

## 11. Known gaps

- **DST**: times are seconds-since-midnight; instants derive by elapsed
  seconds from local midnight, drifting 1 h across the October changeover
  for journeys spanning 2–3 am. Fix: GTFS "noon minus 12h" anchoring in the
  mapper. Applies equally to the DST-observing world regions (BOS/BER/PRG
  around their own changeovers) — documented, not yet fixed.
- ~~pickup_type / drop_off_type unread~~ **Closed**: parsed and enforced
  since snapshot FORMAT_VERSION 2 (§3). Folder 5 (Regional Coach) is now
  unblocked on this axis, though still unscoped.
- **Same-stop transfers are 0 s** (generated stop-to-stop edges carry the
  60 s buffer; same-platform reboard has none). Revisit with GTFS-RT.
- **Arrival-overtaking within a pattern**: the scan rides the earliest-
  *departing* catchable trip; at a non-FIFO position a later-departing trip
  of the same pattern that *arrives* earlier is never adopted, so its better
  arrival can be missed when the boarding stop is only reachable upstream.
  Inherent to single-current-trip RAPTOR; drop-off-restricted positions are
  ironically exact (the fallback scans every trip). Rare in practice —
  first-stop-sorted trips overtake on departures far more than on arrivals.
- **No shapes/geometry**: legs carry stop coordinates, no polylines
  (shapes.txt skipped for memory).
- ~~frequencies.txt unparsed~~ **Closed**: expanded at parse time (§3);
  synthetic coverage in `FrequenciesTest` (no ingested feed currently ships
  non-empty frequencies).
- **Walk-label overwrite edge**: two consecutive walk legs can render with
  durations that under-sum the recorded arrival (arrival stays a valid upper
  bound). Cosmetic.
- **Distribution**: snapshot is offline-built and env-pointed; the
  production loader should imitate `track/TrackDatasetStore` (manifest +
  sha256 + hot-swap). Ops plan in TODO.md ("VIC router — ops playbook").

## 12. Debug playbook

**Build + benchmark from a local dataset** (expects `<gtfs-dir>/<n>/google_transit.zip`,
i.e. the outer zip's numbered folders extracted):

```sh
./gradlew :server:routerBench -PgtfsDir=<gtfs-dir> \
  [-Pfolders=2,3,4,11] [-PsnapshotOut=<path>] [-PbenchDate=YYYY-MM-DD]
```

Prints model counts (incl. non-FIFO positions), build/snapshot timings, heap,
canonical-pair journeys, and latency percentiles. Writes
`<gtfs-dir>/router-snapshot.bin` by default.

**Golden tests against real data** (skip cleanly when unset — CI stays green):

```sh
VIC_GTFS_DIR=<gtfs-dir> ./gradlew :server:test --tests 'app.krail.bff.vic.*'
```

Assertions are structural (journey exists, sane duration/legs) because the
dataset shifts weekly; the query date derives from the dataset's own
calendar, never the wall clock.

**Localizing a wrong/missing journey**, layer by layer:

1. **Parse**: `routerBench` model counts — did stops/trips/patterns drop
   sharply vs the last run? Builder WARNs on ambiguous stop ids.
2. **Calendar**: is the service active that date? `ServiceCalendar.activeOn`
   is unit-testable in isolation; check `calendar_dates.txt` removals first.
3. **Transfers**: interchange never taken → check edge existence/length
   (350 m cap; two stops 400 m apart simply have no edge).
4. **RAPTOR**: reproduce in a `MiniNetworkFixture`-style synthetic test —
   every prior bug (overtaking, after-midnight, Pareto) has a regression
   test there to crib from.
5. **Mapper/endpoint**: use the Router Lab (ROUTER_LAB.md) to see the exact
   JSON the proto carries, per leg and stop.

**Boot gate states** (`configureVicTripRoutes` logs exactly one of three):

| Log at startup | Meaning |
|---|---|
| `vic trip routes disabled: no VIC_ROUTER_SNAPSHOT configured` | Feature off — every prod config today |
| `vic router snapshot load failed; vic trip routes disabled` + stack | Bad/missing/corrupt snapshot; NSW serving unaffected |
| `vic router model loaded: … stops, … trips, calendar …` | Routes live |
