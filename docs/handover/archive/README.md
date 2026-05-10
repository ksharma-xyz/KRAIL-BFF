# Archive — historical handover narratives

These docs captured work-in-progress and post-mortem context for shipped
changes. The active handover set lives one level up in `docs/handover/`:

| Active doc | What it covers |
|---|---|
| `README.md` | Index + state of play |
| `API_REFERENCE.md` | Endpoint specs + wire-size benchmarks |
| `TESTING_GUIDE.md` | How to test JSON + proto endpoints |
| `MIGRATION_GUIDE.md` | Phase A → E playbook |

If you need anything from the active set, start there.

## What's in this folder

| File | When it was useful | Why kept |
|---|---|---|
| `PROTO_REPO_MIGRATION.md` | Cutting `KRAIL-API-PROTO v0.1.0` and swapping the BFF to consume it as a submodule | Forensics on the submodule wiring + auto-bump workflow |
| `TRIP_POLYLINE_FIX_HANDOVER.md` | The 2026-05-10 JSON pass-through bug (typed deserializer was dropping 229 NSW fields including `coords[]`) | Captures what was wrong, the fix rationale, and the before/after diff |
| `PROTO_TRIP_POLYLINE_HANDOVER.md` | The 2026-05-10 follow-up: same polyline gap on the proto side, fixed by `KRAIL-API-PROTO v0.2.0` adding `Coord` + the field on `TransportLeg` / `Stop` / `WalkInterchange` | Captures schema decisions for v0.2.0 |
| `PHASE_A_INTEGRATION_REPORT_FROM_KRAIL.md` | KRAIL-side report after Phase A wired up locally and validated on AVD | Useful for cross-checking what KRAIL actually consumed; left in original styling (it's a copy of their doc) |

## When to read these

Mostly never. The fixes shipped and the active docs reflect the result.
Reach for these only when:

- A bug suspiciously resembles something in the changelog and you want
  the original context.
- A future schema bump touches the same parts and you want to know why
  the previous decisions went the way they did.
- Onboarding a new contributor who's curious about the project's
  history.
