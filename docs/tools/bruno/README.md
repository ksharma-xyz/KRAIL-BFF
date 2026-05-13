# Bruno collection

Git-friendly Postman alternative — every request is a plain text `.bru` file. Open this `bruno/` folder in [Bruno](https://www.usebruno.com) and you have the full collection ready.

## Open

1. Install Bruno (`brew install --cask bruno` on macOS, or download from <https://www.usebruno.com>).
2. Bruno → **Open Collection** → pick this `bruno/` directory.
3. Pick an environment:
   - `local` — `http://localhost:8080`
   - `production` — `https://bff.krail.app` (placeholder until DNS lands)
4. Click any request → **Send**.

## Why Bruno (not Postman)

- **Files in git.** Each request is a `.bru` text file — diffs in PR review, no proprietary cloud sync.
- **Free for collections.** Postman gates collection sharing on a paid tier.
- **Same UX as Postman.** Headers, query params, scripts, environments, secret vars all work identically.

## Adding a request

Copy any `.bru` file as a template, edit the meta + URL, save in the right folder. Bruno picks it up on the next refresh. Order within a folder is controlled by `meta.seq`.

## Secret values

Bruno's environment files have a `vars:secret` block (see `environments/local.bru`). Put your NSW Open Data key there if you want to test NSW endpoints directly — Bruno keeps secrets in a per-machine vault, not in the repo.

## Run from CLI

Bruno has a CLI (`@usebruno/cli`). Useful for CI smoke checks:

```bash
npm install -g @usebruno/cli
bru run --env local docs/tools/bruno/Health/health.bru
bru run --env local docs/tools/bruno/Trip
```

The `tests { ... }` blocks run automatically and exit non-zero on assertion failure.

## Why both Bruno *and* the HTML tester?

Different tools for different moments:

| Need | Use |
|---|---|
| One-off sanity check, see the JSON | `api-tester.html` (open in browser, hit Send) |
| Latency / payload-size metrics with repeated runs | `api-tester.html` ("× N times" button) |
| Smoke run from a script / CI | Bruno CLI |
| Sharing a request with someone | Bruno (.bru text in git, paste / link) |
| Authoring complex assertion scripts | Bruno (proper test runner) |
| Quick visual comparison BFF vs NSW direct | `api-tester.html` (paste base URL + key) |
