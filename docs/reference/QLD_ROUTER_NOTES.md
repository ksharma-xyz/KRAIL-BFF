# QLD (SEQ / Brisbane) Journey Planning — ingest notes

> Sibling to [`VIC_ROUTER_DESIGN.md`](VIC_ROUTER_DESIGN.md), which is the
> design reference for the shared router core (algorithm, compact model,
> snapshot format, invariants, debug playbook). This note covers only what
> Queensland adds: the SEQ ingest, the measured feed quirks, and the
> benchmark numbers. Everything in the VIC doc's §2–§6, §8–§9 and §12
> applies to QLD unchanged — same core, zero core modifications.
>
> Status: **prototype-to-production seed**, same as VIC — on the serving
> path, feature-gated off everywhere until the dataset pipeline ships.

---

## 1. What Queensland is, relative to Victoria

Translink publishes open static GTFS and no journey-planner API, so the BFF
computes SEQ routes itself, exactly as for Melbourne. Two structural
differences make QLD the *simpler* region:

- **One flat feed.** SEQ ships a single `SEQ_GTFS.zip` covering every mode —
  bus, Queensland Rail Citytrain, G:link tram (Gold Coast), CityCat/ferry —
  with one consistent id namespace. No zip-of-zips, no folder map, no
  cross-feed stop merging. `qld/QldGtfsIngest` is a one-feed parse.
- **No daylight saving.** Australia/Brisbane is a fixed +10:00 year-round,
  so the VIC DST-drift gap (VIC doc §11) does not apply to QLD.

Code layout mirrors VIC one-to-one:

```
server/src/main/kotlin/app/krail/bff/
  qld/       # QldGtfsIngest (single feed), QldTripService (Brisbane tz)
  mapper/    # RegionJourneyListMapper (shared with VIC; tz + prefix params)
  routes/    # QldTripRoutes (/api/v1/qld/trip/plan-proto, feature-gated
             #   on QLD_ROUTER_SNAPSHOT, same fail-open boot as VIC)
```

Public stop ids are `QLD:`-prefixed: `QLD:600019` (a platform),
`QLD:place_censta` (a station, resolved to its child platforms at query
time).

## 2. SEQ feed facts and quirks (drop 2026-08-27, measured)

Feed: `https://gtfsrt.api.translink.com.au/GTFS/SEQ_GTFS.zip` (~30 MB,
no auth, CC-BY 4.0, refreshed frequently — treat like the VIC weekly drop).

| Fact / quirk | Detail | Absorbed by |
|---|---|---|
| Files present | agency, calendar, calendar_dates, feed_info, routes, shapes, stop_times, stops, trips | shapes.txt skipped (as VIC) |
| **No transfers.txt at all** | VIC at least shipped (unusable) type-4 rows; SEQ ships nothing | Every interchange edge is generated (350 m / 1.33 m/s / +60 s rules) |
| No frequencies.txt | — | — |
| No BOM; CRLF line endings | Unlike VIC's BOM-on-every-file | `CsvReader` handles both |
| route_types all standard | 3 bus ×528, 2 rail ×398, 4 ferry ×9, 0 tram ×1 (G:link) | Existing product-class table |
| Rail `route_short_name` is a service code | e.g. `BDBR` / long name "Airport - Brisbane City"; one route_id per timetable version, so 398 rail "routes" ≪ real lines | Known gap §5 — cards show the code |
| Stop id scheme | 12,854 routable stops, all-numeric ids; 270 stations with `place_*` ids; 849 stops carry parent_station | Station→platform resolution as VIC |
| Bus + rail share stations | e.g. Eagle Junction: bus stops and rail platforms are children of one `place_egjsta` | Free station-level interchange |
| Leading space in stop_lat/lon | `", -27.467834"` | Double parse trims whitespace |
| Spaces inside service/trip ids | `35827515-ATS_HBL 26-41689` | Ids are opaque strings throughout |
| agency.txt has no agency_id column | Single agency (Translink) | Column never read |
| **Zero blank stop times** | VIC's interpolation path goes unused | — |
| After-midnight times to 30:59 | 38,838 rows ≥ 24:00:00 | Three-service-day scan (VIC doc §5) |
| **Trip overtaking here too** | 13,647 of 68,620 pattern-positions (20%) non-FIFO (VIC: 23%) | Per-position FIFO flags — nothing to do |
| pickup/drop_off restrictions **are used** | pickup_type=1 on 130,649 rows (122,625 at trip-final stops — harmless; **8,024 mid-trip**); drop_off_type=1 on 7,164 (7,107 mid-trip) | **Not absorbed — known gap §5** |
| Calendar span ~2 months | 171 services, 20260827–20261026; 377 date exceptions; no exception-only services; referential integrity clean | Weekly rebuilds |

## 3. Measured performance

Apple Silicon laptop, JDK 17, `-Xmx3g`, dataset drop 2026-08-27. Full SEQ
scope (one feed, all modes): 12,854 stops, 936 routes, 2,663 patterns,
123,220 trips, 3,238,150 stop times, 65,550 generated transfer edges.
Roughly one-third of VIC's row count, and the numbers scale accordingly:

| Metric | VIC (metro+V/Line) | QLD (all SEQ) |
|---|---|---|
| Build (parse + model) | 3.3 s | **1.3 s** |
| Snapshot size / write / load | 87 MB / ~300 ms / ~150 ms | **33 MB / 117 ms / 56 ms** |
| Heap after load (post-GC) | ~123 MB | **55 MB** |
| Query p50 / p95 / p99 (200 random reachable pairs) | 16.0 / 22.7 / 25.5 ms | **6.2 / 9.2 / 9.6 ms** |

Canonical pairs (2026-09-01 08:30 query, checked against published
Translink timetables where feasible):

| Pair | Result | Sanity |
|---|---|---|
| Central → Roma Street (rail) | dep 08:31 arr 08:33, direct | Citytrain city section is 2–3 min ✓ |
| West End → New Farm Park (CityCat) | 08:35 → 09:27 direct; faster 1-transfer alternative | CityCat winds most of the river between them; ~50 min plausible ✓ |
| Broadbeach South → Cavill Ave (G:link) | 08:33 → 08:48 | Published tram leg ~13 min + interchange walk ✓ |
| UQ Chancellors Pl → Eagle Junction | 08:30 → 09:12, bus + rail | Cross-mode via CBD as expected ✓ |

## 4. Build, serve, debug

```sh
# Build + benchmark the SEQ model, writes <dir>/router-snapshot-qld.bin
./gradlew :server:routerBench -PgtfsDir=<seq-gtfs-dir-or-zip> -Pregion=qld

# Golden tests against the real feed (skip cleanly when unset)
QLD_GTFS_DIR=<seq-gtfs-dir-or-zip> ./gradlew :server:test --tests 'app.krail.bff.qld.*'

# Serve + Router Lab (see ROUTER_LAB.md)
QLD_ROUTER_SNAPSHOT=<dir>/router-snapshot-qld.bin \
BFF_DEV_PASSTHROUGH=true ./gradlew :server:run -PrunPort=8080
```

Boot gate states mirror VIC exactly (`configureQldTripRoutes` logs one of
"disabled: no QLD_ROUTER_SNAPSHOT configured", "snapshot load failed …
disabled", or "qld router model loaded: …"); a QLD load failure never
touches the NSW or VIC serving paths. The debug playbook in the VIC doc
§12 applies verbatim — substitute the qld ingest and `QLD_GTFS_DIR`.

## 5. QLD-specific known gaps

- **Mid-trip pickup/drop-off restrictions ignored.** The router does not
  read `pickup_type`/`drop_off_type` (VIC doc §11 — already earmarked
  before VIC's regional-coach folder). SEQ actually uses them mid-trip:
  8,024 set-down-only and 7,107 pick-up-only calls (~0.25% of rows), so
  the model will board/alight at a few calls a rider can't. Fix belongs in
  the core (parse both columns, honour them in boarding/alighting; snapshot
  FORMAT_VERSION bump) and then benefits VIC too.
- **Rail cards show service codes.** `line_name` uses `route_short_name`
  (VIC convention), which for Citytrain is a code like `BDBR`; the human
  name lives in `route_long_name`. Revisit the mapper's line-name choice
  before QLD ships to the client.
- Everything else in the VIC gap list (same-stop 0 s transfers, no
  shapes/polylines, frequencies.txt unparsed, snapshot distribution ops)
  applies as-is — minus DST, which Queensland does not observe.
