# KRAIL-BFF · Status & next actions

> Where we are, what's blocking, what to do next. Refresh by running
> `gh pr list --state open` + `git log main --oneline -5`.

Last refresh: **2026-06-13**.

---

> **Start here instead: [PLAN.md](PLAN.md)** — the master sequence
> (build tracking locally → dashboard soak → handover → deploy),
> doc map, cost bounds, and open-source hygiene rules.

## TL;DR

**Live tracking T1 is built, soaked on real trips, and hardened**
(2026-06-12/13): track.proto contract (with journey-segment tags),
`POST /api/v1/track/snapshot` joining GTFS-R feeds server-side with
per-trip stop memory (complete snapshots even after NSW trims passed
stops), user-relative end-of-journey semantics (ENDED at *your*
destination, vehicle omitted), per-carriage occupancy + live fleet
type flowing, and a control-room browser dashboard
(`docs/tools/track-tester.html`) with API inspector. App-side
contract: [`docs/handover/TRACKING_INTEGRATION.md`](docs/handover/TRACKING_INTEGRATION.md).

Workflow is direct-to-main, no PRs (test locally before pushing).
**Now: T1.5** (platform-level stop names + map polylines via the
dataset job), **then deploy** — spec and runbook ready.

---

## What to do next — in order

1. **Read the security audit** —
   [`docs/reference/SECURITY_AUDIT_2026-06.md`](docs/reference/SECURITY_AUDIT_2026-06.md).
   One blocking action before deploy: mint a separate BFF-only NSW API key.
2. **Deploy** — follow
   [`docs/guides/FIRST_DEPLOY.md`](docs/guides/FIRST_DEPLOY.md)
   (novice-friendly sequence; links into
   [`docs/reference/DEPLOYMENT.md`](docs/reference/DEPLOYMENT.md) and
   `DEPLOY_CHECKLIST.template.md` rather than duplicating them).
3. **App rollout** — once the BFF is live, integrate from KRAIL per
   [`docs/reference/BFF_ADOPTION_GUIDE.md`](docs/reference/BFF_ADOPTION_GUIDE.md):
   0% → 10% → 50% → 100% → 2-week grace → delete the NSW-direct path.
4. **Endgame** — delete the in-app NSW API key at the NSW portal. That is
   the security goal of this whole project.
5. **Future work** (push notifications via FCM, dataset distribution,
   response caching, key-rotation details) — see
   [`docs/reference/ROADMAP.md`](docs/reference/ROADMAP.md).

Full ordered queue with checkboxes: [`TODO.md`](TODO.md).

---

## Done (no longer pending)

- All 8 endpoints written, tested, merged (PRs #44–#72).
- Security stack: per-IP + global rate limits, NSW daily budget, circuit
  breaker, version gate, origin-token gate, input validation, CodeQL,
  Dependabot.
- `krail-api-proto` public repo + submodule wiring (`proto-bump.yml`).
- Deploy spec (`.do/app.yaml`) and runbook (`DEPLOYMENT.md`) — written,
  not yet executed.
- Local dev tooling, dashboard, Bruno collection, testing playbook.

---

## How to refresh this doc

```bash
gh pr list --state open --json number,title,headRefName | \
  python3 -c 'import sys,json; [print(f"#{p[\"number\"]} {p[\"title\"]}") for p in json.load(sys.stdin)]'
```

Update the "Last refresh" date. Once deployed, the "What to do next"
list shrinks to rollout + roadmap pointers.
