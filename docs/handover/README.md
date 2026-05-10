# KRAIL ↔ BFF integration — handover index

> Audience: KRAIL agent / app maintainer. Pick the doc you actually need.
> Last updated: 2026-05-10 against `proto-submodule` branch.

---

## What's the BFF?

A Kotlin/Ktor server sitting between the KRAIL app and NSW Open Data.
Holds the NSW API key server-side, rate-limits per IP, caps daily
upstream usage, distributes versioned static datasets, and reshapes
heavy payloads into smaller protobuf for the trip-results screen.

It runs locally on `:8080` while you develop. It is not yet deployed
to a real host — Phase B (production rollout) is blocked on that step.

## State of play

- ✅ All 11 endpoints implemented, smoke-tested, contract-tested.
- ✅ Phase A on the KRAIL side (debug-only override) integrated and
  validated end-to-end on AVD.
- ✅ `KRAIL-API-PROTO` repo at `v0.2.0`, includes polyline fields.
- ✅ BFF mapper populates polyline data in proto responses.
- ✅ Park & Ride batch endpoint with stop-ID resolution.
- ⏳ Phase B (production rollout, Firebase RC cohort) — blocked on BFF deploy.
- ⏳ Phase C (KRAIL adopts proto endpoint) — foundation laid in KRAIL repo.
- ⏳ Phase D (local stop search via dataset) — schema ready, not wired.
- ⏳ Phase E (delete in-app NSW key) — depends on A–D at 100%.

## Pick your doc

| Goal | Read |
|---|---|
| "What endpoints exist? What's the request / response shape? What does the wire look like?" | [`API_REFERENCE.md`](API_REFERENCE.md) |
| "How do I test endpoint X — JSON or proto?" | [`TESTING_GUIDE.md`](TESTING_GUIDE.md) |
| "I'm doing the Phase A migration in KRAIL" / "What's Phase B / C / D / E?" | [`MIGRATION_GUIDE.md`](MIGRATION_GUIDE.md) |
| "Why does the project work this way? Historical narrative." | [`archive/`](archive/) (mostly never; see `archive/README.md`) |

If you read just two of the four, **API_REFERENCE.md + the section of
MIGRATION_GUIDE.md for your current phase** covers most needs.

## Smoke check the BFF in 30 seconds

From this repo's root:

```bash
./scripts/dev.sh up        # starts BFF on :8080 + dashboard on :8000
curl -s -w '\n%{http_code}\n' http://localhost:8080/health
# → {} \n 200
```

The dashboard at <http://localhost:8000/api-tester.html> has
point-and-click access to every endpoint, grouped by KRAIL screen.

## Endpoint index (one-liner each)

| Endpoint | Format | Used by KRAIL screen |
|---|---|---|
| `/v1/tp/trip` | JSON pass-through | Trip results (today) |
| `/api/v1/trip/plan-proto` | binary protobuf | Trip results (Phase C) |
| `/v1/stops/{id}/departures` | JSON pass-through | Departures / Saved Trips |
| `/v1/parking/facilities/{id}/availability` | JSON pass-through | Park & Ride detail |
| `/v1/parking/availability?ids=` or `?stopIds=` | JSON | Park & Ride home batch |
| `/v[1\|2]/gtfs/realtime/{feed}` | binary protobuf | Live tracking |
| `/v2/gtfs/vehiclepos/{feed}` | binary protobuf | Map markers |
| `/v1/data/{stops\|routes}/manifest` | 302 → JSON manifest → `.pb` | Phase D dataset |
| `/health`, `/ready` | JSON | Operational probes |

Full specs (params, real captured response bodies, error codes, wire
sizes) → `API_REFERENCE.md`.

## Where to go next

If you're new to this project: read this README → glance at
`API_REFERENCE.md` table of contents → jump to the doc that matches
your current task.

If you've integrated before: just open the doc you need from the
table above.

## Status doc, plan doc, and other repo links

- [`../../STATUS.md`](../../STATUS.md) — what's on `main`, what's
  outstanding, deployment plan.
- [`../../START.md`](../../START.md) — how to run the BFF locally.
- [`../reference/`](../reference/) — long-form design docs
  (`MODERNIZATION_PLAN`, `API_SCHEMA_DESIGN`, `BFF_ADOPTION_GUIDE`,
  `DEPLOYMENT`).
- KRAIL-API-PROTO: <https://github.com/ksharma-xyz/KRAIL-API-PROTO> ·
  [docs site](https://ksharma-xyz.github.io/KRAIL-API-PROTO/)
