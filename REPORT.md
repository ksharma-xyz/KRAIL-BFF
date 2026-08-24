# VIC Journey-Planning Core — Overnight Prototype Report

Branch: `feat/vic-router-prototype`. Victoria publishes no journey-planner API,
so the BFF computes routes itself from GTFS static data. This delivers a
city-agnostic RAPTOR core, VIC ingest, snapshot format, benchmarks, tests, and
a feature-gated proto endpoint.

## What was built

| Stage | Status |
|---|---|
| 1. `router/` city-agnostic core (parse → compact model → RAPTOR) | Done, tested |
| 2. `vic/` ingest (folders 2 Metro, 3 Tram, 4 Bus, 11 SkyBus) | Done, tested |
| 3. Tests (synthetic mini-network + real-data golden) | Done, all green |
| 4. Snapshot format + load benchmark | Done, tested |
| 5. Benchmarks | Done, targets beaten |
| 6a. Stretch: `GET /api/v1/vic/trip/plan-proto` + mapper | Done, tested |
| 6b. Stretch: folder 1 (V/Line train) ingest | Done (bench-verified) |

## Key design decisions (one line each)

- **Patterns, not trips**: trips grouped by (route, exact stop sequence); stop
  times are flat `IntArray`s indexed `base + trip*nStops + pos` — no
  per-StopTime objects at 9.6M rows.
- **Streaming CSV**: hand-rolled reader (BOM, quoted fields, CRLF, embedded
  newlines) with allocation-free numeric/time accessors; the 599MB bus
  stop_times.txt parses without materialising strings per field.
- **Per-folder namespacing**: every VIC folder is its own feed (agency_id is
  "1" everywhere); stops merge across feeds only on same raw id + <50m
  proximity — this unifies the 42 shared tram/bus stops.
- **Footpaths are all generated**: VIC's only transfers.txt rows (folder 2,
  13,229 rows) are ALL type 4 in-seat continuations, not stop transfers — so
  proximity transfers (grid-bucketed haversine, 350m radius, 1.33 m/s + 60s
  buffer) carry every interchange; curated stop-to-stop rows would win via
  `putIfAbsent` if a future dataset adds them.
- **Three service days per query**: RAPTOR scans D-1/D/D+1 so >24:00:00
  stop_times work on both sides of midnight (verified by test: a 24:30 trip is
  caught at 00:30 next day).
- **Explicit time inputs**: `plan(from, to, date, secondsSinceMidnight)` — the
  router core never reads a clock (repo invariant); `VicTripService` takes the
  usual injected `clock` for defaults only.
- **Stations resolve to platforms**: `vic:rail:MCE` → child platform stops;
  multi-source/multi-target RAPTOR handles the rest (City Loop needs no
  special-casing — direction falls out of stop_times order).
- **Snapshot = flat arrays + magic/version header**: `DataOutputStream`, 87MB,
  loads in ~150ms vs ~3.2s full parse; production will load this via the
  TrackDatasetStore-style manifest pattern (not built tonight).
- **Feature-gated endpoint**: `/api/v1/vic/trip/plan-proto` registers only
  when `VIC_ROUTER_SNAPSHOT` is configured — production behaviour unchanged
  until the dataset pipeline ships.

## Benchmarks

MacBook (Apple Silicon), JDK 17, `-Xmx3g`, dataset downloaded 2026-08-24
(calendar 2026-08-21 → 2026-11-22), query date 2026-08-25 (Tuesday).

Metro scope (folders 2+3+4+11): 24,894 stops, 799 routes, 3,502 patterns,
251,474 trips, 9,435,939 stop times, 146,696 transfer edges, 5,462 services.

| Metric | Target | Actual |
|---|---|---|
| Model build (parse + build, 4 feeds) | — | **3.2s** |
| Snapshot write | — | 334ms (87MB) |
| Snapshot load | — | **149ms** (~21× faster than parse) |
| Heap after load (post-GC) | < 400MB | **118MB** |
| Query p50 (200 random reachable pairs) | — | **6.3ms** |
| Query p95 | < 100ms | **9.3ms** |
| Query p99 / max | — | 9.9ms / 10.3ms |

With V/Line (folders 2+3+4+11+1): 24,988 stops, 262,119 trips, 9,580,826 stop
times; heap 123MB; p95 9.6ms — the core absorbed the regional feed unchanged.

Canonical journeys (all sane vs real timetables):

| Pair | Result |
|---|---|
| Flinders St → Melbourne Central 08:30 | 08:31 → 08:38 direct |
| Flagstaff → Parliament (City Loop) | 08:30 → 08:34 direct |
| Southern Cross → Flinders St | 08:30 → 08:34 direct |
| Southern Cross → Geelong (V/Line) | 08:30 → 09:33 direct |

Heap numbers use `System.gc()` + `totalMemory - freeMemory` — approximate by
nature (JVM may hold uncollected garbage; RSS will be higher than heap).

## Test status (honest)

`./gradlew :server:test` — all green, including all pre-existing tests.

- **Unit (always run)**: CSV reader (BOM/quotes/CRLF/embedded newlines/times),
  ServiceCalendar (mask, range, added/removed dates), snapshot round-trip +
  corrupt-file rejection, 14 RAPTOR tests on a synthetic network built through
  the real parser (Pareto direct-vs-transfer, same-stop tight connection,
  earliest-boarding, footpath leg with explicit transfer precedence, station →
  platform resolution, blank-time interpolation, after-midnight catch,
  Saturday-only service, calendar_dates removal/addition, no-journey,
  unknown-stop), 6 endpoint tests (proto shape, walk legs, VIC: prefixes,
  clock-injected defaults, validation 400s, unknown 404).
- **Integration (gated on `VIC_GTFS_DIR`, skip cleanly otherwise)**: real-data
  golden tests — FSS→MCE, City Loop FGS→PAR, tram route 96 pair (pattern
  discovered at runtime), suburban bus →FSS cross-mode with a bus + rail/tram
  leg. Assertions are structural (exists, sane duration/legs), never exact
  times — the dataset shifts weekly. All 4 pass against the real dataset.

## Known gaps

- **No shapes/geometry**: walking and vehicle legs carry no polylines
  (shapes.txt skipped for memory); stop coordinates are included.
- **DST approximation**: times are seconds-since-midnight; instants derived by
  elapsed seconds from local midnight drift 1h across the Oct 4 changeover for
  journeys spanning 2-3am. Fix: GTFS "noon minus 12h" anchoring in the mapper.
- **Same-stop transfers are 0s**: boarding another route at the same stop has
  no minimum interchange (footpaths between stops carry a 60s buffer). Real
  connections may be missed on late-running services once GTFS-RT lands.
- **Trip overtaking**: within a pattern, trips are sorted by first-stop
  departure; earliest-trip binary search has a backward guard for mild
  inversions but pathological overtaking could board a slightly later trip.
- **Type-4 in-seat continuations ignored**: City Loop through-running trips are
  treated as separate boardings (counts one extra "transfer" at the shared
  stop); journeys remain valid.
- **No route-level filters** (excludedModes equivalent), no arrive-by queries,
  single departure time per query (no profile/range queries).
- **Departure-time semantics**: `depSec` of a journey is board-time minus
  initial walk; walk-only journeys are found by the router but dropped by the
  proto mapper (no boarding to build a card from).
- **Snapshot is offline-only**: no manifest/sha256/hot-swap distribution yet —
  production wiring should imitate `track/TrackDatasetStore`.
- **Regional scope**: folder 5 (Regional Coach, 117MB) and folder 6 (Regional
  Bus) not ingested tonight; folder 1 (V/Line) verified via bench but has no
  golden tests.

## Recommended next steps

1. Dataset pipeline (KRAIL-GTFS repo): weekly download → build snapshot →
   publish with manifest + sha256; BFF loads via TrackDatasetStore-style store
   with hot-swap.
2. GTFS-RT overlay (key arrives tomorrow): match on the trip ids the router
   already emits; tracking never re-plans (existing invariant).
3. Stop search/directory for Melbourne (client searches locally — ship a VIC
   stops dataset via the existing manifest pattern).
4. Fix DST anchoring; add board-slack config; consider arrive-by (reverse
   RAPTOR) and range queries (rRAPTOR) when product needs them.
5. Add folders 1/5/6 golden tests + V/Line-aware display naming.

## Files added

Main (`server/src/main/kotlin/app/krail/bff/`):
- `router/GtfsCsv.kt` — streaming CSV reader + IntVec
- `router/GtfsSource.kt` — feed source abstraction (zip / in-memory)
- `router/GtfsFeed.kt` — parsed-feed model + ServiceCalendar
- `router/GtfsParser.kt` — GTFS → GtfsFeed (columns journey planning needs)
- `router/RouterModel.kt` — compact flat-array model
- `router/RouterModelBuilder.kt` — feeds → model (patterns, merge, transfers)
- `router/Raptor.kt` — RAPTOR query + journey reconstruction
- `router/RouterSnapshot.kt` — binary snapshot writer/reader
- `vic/VicGtfsIngest.kt` — VIC folder layout → feeds/model
- `vic/VicTripService.kt` — plan + map, injected clock
- `mapper/VicJourneyListMapper.kt` — Journey → JourneyList proto (Melbourne tz)
- `routes/VicTripRoutes.kt` — feature-gated endpoint, allowlist validation
- `tools/RouterBench.kt` — offline benchmark (`./gradlew :server:routerBench -PgtfsDir=…`)

Tests (`server/src/test/kotlin/`):
- `router/CsvReaderTest.kt`, `router/ServiceCalendarTest.kt`,
  `router/MiniNetworkFixture.kt`, `router/RaptorTest.kt`,
  `router/RouterSnapshotTest.kt`
- `routes/VicTripRoutesTest.kt`
- `vic/VicRouterIntegrationTest.kt` (gated on `VIC_GTFS_DIR`)

Modified: `server/build.gradle.kts` (routerBench task, 3g test heap,
VIC_ROUTER_SNAPSHOT forwarding), `Application.kt` (route registration only).
