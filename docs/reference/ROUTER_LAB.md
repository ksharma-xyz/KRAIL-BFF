# Router Lab — journey-planner debug dashboard

Dev-only, in-browser tool for staring at planner output: type an origin and
destination, pick a region, get the static-timetable journeys with every leg
and intermediate stop the proto carries. It exists to debug the router and
the mappers — it is not a product surface.

## Launch

```sh
# One-time: build the snapshots (see VIC_ROUTER_DESIGN.md §12, QLD_ROUTER_NOTES.md §4)
./gradlew :server:routerBench -PgtfsDir=<vic-gtfs-dir>
./gradlew :server:routerBench -PgtfsDir=<seq-gtfs-dir-or-zip> -Pregion=qld

VIC_ROUTER_SNAPSHOT=<vic-gtfs-dir>/router-snapshot.bin \
QLD_ROUTER_SNAPSHOT=<seq-gtfs-dir>/router-snapshot-qld.bin \
BFF_DEV_PASSTHROUGH=true \
./gradlew :server:run -PrunPort=8080
# open http://localhost:8080/internal/router-lab
```

Each `*_ROUTER_SNAPSHOT` var enables that region's planner independently;
with neither set the lab still serves for NSW-only debugging (autocomplete
+ BFF planning absent), and both loaded together serve side by side. All
three vars also work from `local.properties` (`vic.routerSnapshot`,
`qld.routerSnapshot`, `bff.devPassthrough`) via the `:server:run`
forwarding.

## UI

Single self-contained HTML page (classpath resource
`router-lab/index.html`) — no CDNs, no framework, dark-scheme aware.

- **Region** VIC / QLD / NSW. BFF-routed regions (VIC, QLD) autocomplete
  origin/destination by stop name (`/internal/vic/stops/search`,
  `/internal/qld/stops/search`; picks fill in ids like `VIC:vic:rail:FSS`,
  `QLD:place_censta`); NSW takes raw stop ids free-text (e.g. `200060`).
- **Date/time** default to now; **Find timetable** queries the region's
  plan-proto endpoint with `Accept: application/json`.
- **Journey cards**, sorted by departure: dep→arr, duration, transfers,
  mode chips. Click to expand legs: line, headsign, per-stop times, stop
  ids, walk legs, plus a raw-JSON view of the exact payload.
- **Paging**: scrolling to the bottom re-queries with time = last journey's
  departure + 1 minute and appends; scrolling to the top queries 30-minute
  earlier windows and prepends (scroll position anchored; wheel fallback at
  the top edge). After a search, later journeys auto-fetch until the pane
  overflows — scroll events can't fire on a short list. Results dedupe on
  the full leg signature; three consecutive empty pages in a direction show
  "no more".
- **Errors** show the server's code/message verbatim — a validation 400 or
  envelope 404 appearing here is the endpoint's real behaviour.

## The Accept-JSON switch

All plan-proto endpoints (`/api/v1/trip/plan-proto` NSW,
`/api/v1/vic/trip/plan-proto` VIC,
`/api/v1/qld/trip/plan-proto` QLD) return a JSON mirror of the JourneyList
proto when the request sends `Accept: application/json` **and the dev gate
is on**. Field names match the proto exactly (`mapper/JourneyListJson.kt`,
same precedent as `TrackJson`) — the lab debugs the real code path, not a
parallel one.

The gate condition is deliberate: Ktor client ContentNegotiation appends
`application/json` to Accept, so an ungated switch could silently flip the
released KMP client from protobuf to JSON. With `BFF_DEV_PASSTHROUGH`
unset (every production config) the encoding is protobuf regardless of
Accept — regression-tested in `RouterLabRoutesTest`.

## Security posture

- Gated identically to `/internal/passthrough`
  (`BFF_DEV_PASSTHROUGH=true`, default off, WARN log when enabled).
  Everything under `/internal/router-lab` and the per-region
  `/internal/{vic,qld}/stops/search` endpoints is a 404 in production
  config. Not in `EXEMPT_PATHS` — normal gates and rate limits apply.
- The lab calls only BFF endpoints; **the NSW key never reaches the
  browser** (unlike the passthrough, the lab has no upstream proxy at all).
- `q` on the stop search is allowlist-validated (`[A-Za-z0-9 .,'&/#()_:-]`,
  1–64 chars), reject-don't-sanitize; responses carry only model stop data
  (id/name/lat/lon, top 20).
- Every server-derived string reaching the DOM is escaped; errors and raw
  JSON render via `textContent`.
