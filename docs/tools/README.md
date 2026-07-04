# KRAIL Dispatch — API tester

Single-file HTML tester (`api-tester.html`) for the BFF and the NSW APIs behind
it. No build step, no dependencies, replaces Postman/Bruno for this project.
`track-tester.html` is its sibling for the live-tracking flow.

## Start it (one command)

```bash
./scripts/tester.sh
```

That serves `docs/tools/` on :8000, opens the tester, and runs the BFF on :8080
with `BFF_DEV_PASSTHROUGH=true` (needed for station search + NSW-direct calls)
and `BFF_METRICS_ENABLED=true`. Ctrl-C stops the BFF. Requires
`local.properties` with `nsw.apiKey`.

Manual equivalent:

```bash
python3 -m http.server -d docs/tools 8000 &
BFF_DEV_PASSTHROUGH=true ./gradlew :server:run
# open http://localhost:8000/api-tester.html
```

Don't open via `file://` — the BFF's CORS allowlist covers
`http://localhost:8000` (see `local.properties`), not null origins.

## What it does

- **Station search, not stop IDs** — FROM/TO autocomplete backed by NSW
  `stop_finder` (via `/internal/passthrough`; the NSW key never reaches the
  browser). Keyboard navigation, mode badges, swap, selections persist.
- **Endpoint catalog** — trip planning (BFF JSON / BFF proto / NSW direct),
  departures, park & ride, GTFS-RT feeds, stop finder, any-URL/curl.
- **Response bay** — status/latency/body/wire strip; collapsible JSON tree with
  key/value filter; generic protobuf **wire-format decoder** (readable fields,
  no schema needed); raw view; headers.
- **Compare** — races NSW direct vs BFF JSON vs BFF proto, tables body/wire
  size and latency deltas.
- **Export** — writes the response into `api-exports/` (git-ignored; pick the
  folder once, Chrome/Edge) or falls back to a download. Plus copy body / URL /
  curl.
- **History** — last 40 requests, click to restore.
- **Quota-aware** — zero NSW calls on page load; session counter in the top bar.

## Health LEDs

- **BFF** — `/health` on the configured base URL.
- **NSW passthrough** — probes `/internal/passthrough` (400 = enabled,
  404 = off). Off means station search and NSW-direct rows won't work; start
  the BFF with `BFF_DEV_PASSTHROUGH=true`.

## Adding an endpoint

Edit the `ENDPOINTS` array in `api-tester.html`: `id`, `name`, `src`
(`bff`/`proto`/`nsw`), `binary`, `needs` (`from`/`to` inputs), and a
`build(inputs)` function returning the URL. Rendering, validation, history and
compare all read from that catalog.
