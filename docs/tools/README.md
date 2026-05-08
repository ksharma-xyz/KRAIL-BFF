# API Tester

Single-file HTML tester for every BFF endpoint. No build step, no dependencies.

## Open it

```bash
# From the repo root
open docs/tools/api-tester.html        # macOS
xdg-open docs/tools/api-tester.html    # Linux
start docs/tools/api-tester.html       # Windows
```

Or serve via any static server (matters if your browser refuses `file://` for CORS):

```bash
python3 -m http.server -d docs/tools 8000
# then open http://localhost:8000/api-tester.html
```

## What it does

- **Every BFF endpoint listed and ready to fire.** Trip planner (JSON + protobuf), departures, park & ride (list + availability), GTFS-RT (v1 trip updates, v2 trip updates, v2 vehicle positions), stops & routes manifests, health & ready probes.
- **Config bar** at the top — Base URL, optional `Authorization` header, optional `X-Krail-Version`. Stored in `sessionStorage`, **cleared when you close the tab**.
- **Per-endpoint forms** — sensible defaults (real Sydney stop IDs), parameter values stored in sessionStorage so navigating away and back keeps them.
- **Response viewer** — status, elapsed time, headers, pretty-printed JSON. Binary protobuf bodies render as hex preview (first 256 bytes + ASCII).
- **Smoke runner** — one click runs every `GET` endpoint sequentially and gives a pass/fail summary. Use after standing up local BFF or after a deploy.

## API key handling

The `Authorization` field sits in `sessionStorage` only. Tab close → gone. Never written to localStorage, the URL bar, or the page DOM beyond the input itself.

You typically **don't need it** for the BFF — the BFF holds the NSW key server-side and the app talks to the BFF anonymously. The field exists so you can also point the tester at NSW directly (`https://api.transport.nsw.gov.au`) for compare-mode debugging — paste `apikey <your key>` and the request goes through with that header.

## Adding a new endpoint

Edit the `endpoints` array near the top of `<script>` in `api-tester.html`:

```js
{
  id: 'myEndpoint',
  method: 'GET',
  path: '/v1/some/{thing}/path',
  name: 'Human-readable name',
  pathParams: [{ name: 'thing', default: 'foo' }],
  params: [
    { name: 'q', placeholder: 'optional query param' },
  ],
  binary: false,           // true if the response is protobuf bytes
  followRedirect: true,    // for endpoints that 302 (manifests)
  smoke: {
    include: true,         // include in the "Run all GETs" smoke test
    pathFill: { thing: 'foo' }, // smoke-only override if path param has no default
  },
},
```

Nothing else to wire up — render, form persistence, request sending, and the smoke runner all read from this catalogue.

## Future test running

The same catalogue can drive automated smoke tests in CI later — convert the `endpoints` array to a JSON file, write a small runner that hits each one against a deploy preview URL, and assert on the response shape. The current "Smoke tests" button is the in-browser equivalent.
