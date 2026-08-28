# World regions — Auckland, Wellington, Boston, Berlin/Brandenburg, Prague

> Sibling to [`VIC_ROUTER_DESIGN.md`](VIC_ROUTER_DESIGN.md) (core design,
> invariants, debug playbook) and [`QLD_ROUTER_NOTES.md`](QLD_ROUTER_NOTES.md)
> (the first single-feed region). This note covers the five regions added in
> one pass on the shared core, the registry that made that cheap, and the
> measured feed quirks. Everything in the VIC doc's §2–§6, §8–§9 and §12
> applies to all five unchanged.
>
> Status: **prototype-to-production seed**, same as VIC/QLD — on the serving
> path, feature-gated off everywhere until the dataset pipeline ships.

---

## 1. The region registry

QLD was a near-verbatim copy of the VIC service/route/ingest files with the
prefix and timezone swapped. Five more copies were not going to happen, so
single-feed regions are now data, not code — one `RegionSpec` in
`region/RegionRegistry.kt`:

| Field | Drives |
|---|---|
| `code` (e.g. `akl`) | endpoint path `/api/v1/{code}/trip/plan-proto`, lab search path, `-Pregion=` |
| `displayName` | Router Lab dropdown |
| `zone` | query-date/time defaults, card instants |
| `idPrefix` (e.g. `AKL`) | public stop-id namespace `AKL:...` |
| `feedZipName` | ingest input resolution (zip or directory containing it) |
| `sourceLabel` | snapshot meta |
| `canonicalPairs` | RouterBench sanity journeys |

Derived by convention: `{CODE}_ROUTER_SNAPSHOT` env var (feature gate),
`{code}.routerSnapshot` config key, `{CODE}_GTFS_DIR` (golden-test gate),
`router-snapshot-{code}.bin` (default snapshot name).

Shared implementations over the registry: `RegionGtfsIngest` (one-zip
parse), `RegionTripService` (plan + diacritic-folded stop search),
`routes/RegionTripRoutes.kt` (validation + feature-gated registration, one
region's snapshot failure never touches another), Router Lab (dropdown from
`/internal/router-lab/regions`, per-region `/internal/{code}/stops/search`),
RouterBench (`-Pregion={code}`). VIC stays the one bespoke ingest (folder
bundle, cross-feed stop merging) but serves through the same
`RegionTripService`/`RegionTripRoutes`.

Adding another single-feed city is now: registry entry → bench run →
golden tests → done.

## 2. What the five feeds forced into the core

Both were known gaps; both closed ahead of this pass (snapshot
FORMAT_VERSION 2, see VIC doc §3–§4):

- **pickup_type/drop_off_type enforced.** Every one of the five uses them
  (SEQ's ~0.25% was not an outlier — MBTA restricts ~4% of its rows in each
  direction). Includes the exact fallback scan for drop-off-restricted
  positions where the earliest-trip-dominates argument breaks.
- **frequencies.txt expansion.** Built for VBB — and then the 2026-08-28 VBB
  drop shipped the file *empty*, as does AT's. The path is covered by
  synthetic tests (`FrequenciesTest`) and stays dormant until a live feed
  actually uses it. Semantics: concrete trips per headway step in
  `[start, end)`; `exact_times=1` exact replication, else headway-based.

Search-side: stop-name autocomplete folds Unicode NFD and strips combining
marks on both sides, so `otahuhu` finds Ōtāhuhu, `andel` finds Anděl,
`mockernbrucke` finds Möckernbrücke. The lab's query allowlist admits
`\p{L}\p{M}` (still reject-don't-sanitize).

## 3. Per-region feed facts and quirks (drops of 2026-08-28, measured)

| | AKL (AT) | WLG (Metlink) | BOS (MBTA) | BER (VBB) | PRG (PID) |
|---|---|---|---|---|---|
| Source zip | `gtfs.at.govt.nz/gtfs.zip` | `static.opendata.metlink.org.nz/v1/gtfs/full.zip` | `cdn.mbta.com/MBTA_GTFS.zip` | `vbb.de/gtfs` | `data.pid.cz/PID_GTFS.zip` |
| Zip size | 24.5 MB | 10.8 MB | 17.7 MB | 76.2 MB | 53.2 MB |
| Timezone | Pacific/Auckland | Pacific/Auckland | America/New_York | Europe/Berlin | Europe/Prague |
| Calendar span | 08-20 → 11-29 | 08-23 → 10-31 | 08-12 → 09-05 (~3 weeks!) | 08-25 → 12-12 | **2025-12-13 → 2026-12-12** |
| route_types | basic 2/3/4 | 2/3/4 + **712 school bus**, **5 cable car** | basic 0/1/2/3/4 | **extended only**: 100/106/109 rail, 400 U-Bahn, 700 bus, 900 tram, 1000 ferry | 0/1/2/3/4 + 11 trolleybus |
| no-pickup / no-drop-off rows | 35,326 / 1,944 | 10,691 / 9,632 | 93,514 / 89,798 | 5,877 / 3,391 | 36,526 / 35,692 |
| transfers.txt | 6 rows (types 0/2) | 2,168 rows, **all trip-qualified type 2** (all skipped) | 7,270 rows: 2,605 usable 0/1/2, 4,650 type-4 continuations | **52,478 rows, ~48.5k usable type 2** — largest curated set; I5 precedence matters | 14,840 rows: 50 usable, 14,790 trip-qualified type 1 (skipped) |
| frequencies.txt | present, **empty** | absent | absent | present, **empty** | absent |
| After-24:00 rows | 9,145 | 1,029 | 104,922 | 204,642 | 43,472 |
| Non-ASCII stop names | **0** (macrons transliterated in this drop) | 50 | 0 | 8,921 | 15,357 |
| Pathways/levels etc. | — | custom `stop_patterns`/`stop_pattern_trips` | pathways, levels, facilities, fares v2, 2.2k location_type 2/3 | pathways 9.8 MB, levels | pathways, levels, fares, `route_sub_agencies`, `vehicle_*`, location_type 4 boarding areas |
| Stop ids | `1002-c8bb8209` — **hash suffix rotates per drop** | stable (`WELL`, `5500`) | `place-pktrm`, long door/node ids (non-routable) | DHID with colons `de:11000:900003201` | `U400Z101P` / stations `U400S1` |

Notes worth keeping:

- **AKL id churn**: station/stop ids carry a per-drop content hash. Registry
  canonical pairs use the current drop's ids (bench skips cleanly when they
  rotate); the golden test resolves Britomart/Newmarket **by name**. Anything
  that ever persists AKL stop ids across drops will need a mapping layer.
- **AKL macrons**: this drop publishes `Waitemata`, not `Waitematā` — zero
  non-ASCII names. Search folding still matters for WLG/BER/PRG, and AKL may
  re-introduce macrons any drop.
- **PRG dead calendar head**: the calendar opens 2025-12-13 but the drop's
  trips run near the drop date. Query-date heuristics must pick by **active
  trip count**, not active service count (the golden-test base does; RouterBench
  wants an explicit `-PbenchDate` for PID).
- **BOS three-week span**: MBTA rebuilds its feed roughly weekly and the
  calendar covers ~3 weeks — snapshot staleness bites faster here than
  anywhere else.
- **BER U-Bahn cards as train**: VBB publishes U-Bahn as extended 400, the
  same code Melbourne uses for metro rail, so the shared mapping sends both
  to product class 1 (train). Cosmetic; revisit if BER ever ships to users.
- **WLG cable car**: basic route_type 5 → mapped to light rail (4), the
  closest client class.
- All five parse with **zero core changes** beyond the two Stage-0 features —
  unknown files and columns are ignored by design, location_type 2/3/4 rows
  are non-routable, quoted UTF-8 CSV is handled by the streaming reader.

## 4. Measured performance (Apple Silicon laptop, JDK 17)

Snapshot format v2. "Heap" is post-GC after loading the snapshot, JVM
measurement — treat as approximate.

| Region | Stops | Routes | Trips | Stop times | Transfer edges | Build | Snapshot | Load | Heap | p50 / p95 / p99 (200 random pairs) |
|---|---|---|---|---|---|---|---|---|---|---|
| akl | 6,835 | 218 | 33,030 | 982,219 | 40,220 | 0.7 s | 11 MB | 44 ms | 24 MB | 2.1 / 3.2 / 6.5 ms |
| wlg | 3,126 | 245 | 9,551 | 334,850 | 20,832 | 0.4 s | 4 MB | 39 ms | 13 MB | 1.0 / 1.3 / 1.6 ms |
| bos | 7,768 | 399 | 89,073 | 2,221,055 | 61,596 | 0.8 s | 23 MB | 38 ms | 49 MB | 3.9 / 5.0 / 5.5 ms |
| ber | 28,002 | 1,253 | 265,259 | 5,870,898 | 131,196 | 2.3 s | 68 MB | 111 ms | 111 MB | 17.7 / 25.9 / 27.5 ms |
| prg | 18,120 | 893 | 104,088 | 2,176,862 | 62,912 | 1.0 s | 26 MB | 75 ms | 57 MB | 8.8 / 12.3 / 13.2 ms |
| qld (rebuilt, v2) | 12,854 | 936 | 123,220 | 3,238,150 | 65,550 | 1.3 s | 36 MB | 53 ms | 65 MB | 6.3 / 9.2 / 9.7 ms |
| vic (rebuilt, v2, metro scope) | 24,894 | 799 | 251,474 | 9,435,939 | 146,696 | 3.3 s | 96 MB | 150 ms | 134 MB | 17.4 / 24.6 / 28.8 ms |

**All seven regions resident together: ~453 MB heap** (sum of the above; the
Stage-3 combined boot confirmed all seven load side by side). Snapshots on
disk total ~264 MB.

Heap vs feed-zip size (informal ≤1.5× aim): akl 1.0×, wlg 1.2×, prg 1.1×,
ber 1.5×, **bos 2.8×** — honest miss; the MBTA zip is deceptively small
because its 104 MB `stop_times.txt` compresses ~6:1, and per-row cost is what
heap actually tracks. VIC/QLD snapshots grew ~9/3 MB v1→v2 (the flags byte
per stop time).

## 5. Canonical journeys (bench output, checked against published timetables)

| Pair | Result | Sanity |
|---|---|---|
| AKL Waitematā (Britomart) → Newmarket | 08:30 → 08:39 direct rail | Southern/Western line ~7–10 min ✓ |
| AKL Waitematā → Ōtāhuhu | 08:30 → 08:57 direct | ~25 min published ✓ |
| WLG Wellington → Porirua | 08:33 → 08:54 direct | Kapiti Line ~21 min ✓ |
| WLG Wellington → Taita | 08:30 → 08:59 direct | Hutt Valley Line ~29 min ✓ |
| BOS Park Street → Harvard | 08:30 → 08:41 direct | Red Line ~9–11 min ✓ |
| BOS Park Street → Government Center | 08:31 → 08:33 direct | Green Line one stop ✓ |
| BER Hauptbahnhof → Alexanderplatz | 08:31 → 08:37 direct | Stadtbahn ~6–8 min ✓ |
| BER Alexanderplatz → Potsdam Hbf | 08:30 → 09:05, 1 transfer (35 min); direct 42 min | S-Bahn/RE ~40 min ✓ |
| PRG Muzeum → Anděl | 08:30 → 08:39, 1 transfer (metro) | A/C → B interchange ~9 min ✓ |
| PRG Muzeum → Hlavní nádraží | 08:31 → 08:32 direct | One stop on line C ✓ |

(The PRG Muzeum → Anděl Pareto set also contains a 0-transfer night-tram
journey arriving 00:56 next day — correct Pareto-by-transfers semantics, the
same-arrival-fewer-transfers card a rider might genuinely want.)

## 6. Build, serve, test

```sh
# Build + benchmark one region (writes <dir>/router-snapshot-<code>.bin)
./gradlew :server:routerBench -PgtfsDir=<feed.zip-or-dir> -Pregion=akl
# PID: pass a bench date near the drop (see §3)
./gradlew :server:routerBench -PgtfsDir=<pid-dir> -Pregion=prg -PbenchDate=2026-09-01

# Golden tests against the real feeds (each skips cleanly when unset)
AKL_GTFS_DIR=<dir> WLG_GTFS_DIR=<dir> BOS_GTFS_DIR=<dir> \
BER_GTFS_DIR=<dir> PRG_GTFS_DIR=<dir> \
./gradlew :server:test --tests 'app.krail.bff.region.*'

# Serve any subset side by side (Router Lab: see ROUTER_LAB.md)
AKL_ROUTER_SNAPSHOT=<path> BER_ROUTER_SNAPSHOT=<path> ... \
BFF_DEV_PASSTHROUGH=true ./gradlew :server:run -PrunPort=8080
```

Boot gates mirror VIC/QLD exactly, per region: one log line of
"disabled: no {CODE}_ROUTER_SNAPSHOT configured", "snapshot load failed …
disabled", or "{code} router model loaded: …".

## 7. Known gaps (beyond the VIC doc's list)

- **DST drift** (VIC doc §11) applies to BOS/BER/PRG around their own
  changeovers (early Nov / late Oct 2026): journeys spanning the 1–3 am
  changeover window derive instants 1 h off. Documented, deliberately not
  fixed in this pass. AKL/WLG observe DST too (late Sep); QLD remains the
  only DST-free region.
- **Line naming**: `route_short_name` is blank on MBTA subway routes
  (falls back to long name — fine) but VBB S/U lines ("S7", "U6") and PID
  metro ("A"/"B"/"C") card correctly; MBTA commuter rail uses long names.
  The QLD rail service-code issue (QLD notes §5) has no analogue here.
- **AKL id churn** (§3): registry canonical pairs need a refresh when AT
  rotates its id hashes; golden tests are immune (name-based).
- **Snapshot ops**: same as VIC — offline-built, env-pointed; the manifest +
  sha256 + hot-swap distribution story is still the open TODO item, now ×7.
