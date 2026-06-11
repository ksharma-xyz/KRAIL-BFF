# PLAN — the one doc to open when you sit down

> Master execution plan. Everything else hangs off this.
> Resuming with Claude: say **"read PLAN.md and TODO.md, continue"**.
>
> Working agreement (decided 2026-06-12): solo project, **commit
> directly to `main`, no PRs**. Two non-negotiables that replace the
> PR gate: run `./gradlew :server:test` locally **before every push**
> (once deployed, every push to main auto-deploys — a broken push is a
> broken deploy), and never push secrets (see §4).
> One-time setup for this: the GitHub ruleset on `main` currently
> *requires* PRs — remove the "pull_request" rule (Settings → Rules →
> main) or pushes will be rejected.

---

## 0. Doc map — which file answers what

| Question | Doc |
|---|---|
| What's the state right now? | [STATUS.md](STATUS.md) |
| What's the full work queue? | [TODO.md](TODO.md) |
| Is the code secure? what must I never do? | [docs/reference/SECURITY_AUDIT_2026-06.md](docs/reference/SECURITY_AUDIT_2026-06.md) |
| How do I deploy, step by step? | [docs/guides/FIRST_DEPLOY.md](docs/guides/FIRST_DEPLOY.md) (+ checklist template, DEPLOYMENT.md for ops) |
| How does live tracking work / what am I building? | [docs/reference/TRACKING_DESIGN.md](docs/reference/TRACKING_DESIGN.md) |
| What comes after (datasets, bootstrap, FCM, key endgame)? | [docs/reference/ROADMAP.md](docs/reference/ROADMAP.md) |
| How does the app integrate? | `docs/handover/` (existing for v1 endpoints; tracking adds its own per design §7b) |

## 1. The sequence (your chosen order)

```
NOW                                  LOCAL ONLY — nothing deployed, zero risk
 ├─ A. Land the docs branch on main
 ├─ B. Build tracking: T0 → T1 + T1-dash      (./scripts/dev.sh up, local NSW key)
 ├─ C. Soak in the browser dashboard           (days; real trains)
 ├─ D. Write docs/handover/TRACKING_INTEGRATION.md   (when satisfied)
THEN
 ├─ E. Deploy                                  (FIRST_DEPLOY.md, ~2 evenings)
 └─ F. App integration (A1/A2) + cohort rollout
```

**A — land the docs (5 min).**
```bash
git switch main
git merge docs/predeploy-audit-and-roadmap   # fast-forward-ish, docs only
git push
git branch -d docs/predeploy-audit-and-roadmap
```
(If the ruleset still blocks direct pushes, fix the ruleset first — see
header note.)

**B — build tracking (the actual fun).** Work the
[TODO.md](TODO.md) tracking section top-down:

1. **O-spikes first, ~1 evening** — they de-risk everything:
   capture real GTFS-R bytes (your local key works:
   `curl -H "Authorization: apikey $KEY" https://api.transport.nsw.gov.au/v2/gtfs/vehiclepos/sydneytrains -o fixture.pb`),
   check PassLoad/TfnswVehicleDescriptor presence (O1), grab ~20
   Trip-Planner-vs-GTFS-R trip_id pairs (O3).
2. **T0** — `track.proto` into krail-api-proto; vendor extension
   protos in the BFF; fixtures into `docs/handover/fixtures/`.
3. **T1 + T1-dash together** — endpoint + dashboard tab; the
   dashboard *is* the test harness (design §7c).
4. Everything runs locally: `./scripts/dev.sh up` → BFF on :8080,
   dashboard on :8000. No deploy needed to build and test all of it.

**C — soak.** Track real trains from the browser for a few days.
Watch match-tier metrics, staleness behaviour, the EXPIRED flow via
the share-link simulator. Satisfaction gate is yours.

**D — handover doc.** Per design §7b: `TRACKING_INTEGRATION.md` +
fixtures. Written while behaviour is fresh, before deploy.

**E — deploy.** [FIRST_DEPLOY.md](docs/guides/FIRST_DEPLOY.md) start
to finish. Prerequisites you can knock out any idle moment before:
mint the BFF-only NSW key (**enable Trip Planner + GTFS-R + GTFS
static products on it** — tracking needs all three) and move
krail.app DNS to Cloudflare (§2 of the guide; site keeps working).

**F — app side.** A1 (point `TripPoller` at the BFF behind
`bff_use_for_track`), A2 (deep-link v2 + krail.app links), cohort
rollout per BFF_ADOPTION_GUIDE.md.

## 2. Cost guarantee at deploy — the honest version

There is **no provider-enforced hard cap on DigitalOcean** — no
setting makes "charge me max $X" true. You are bounded *structurally*
instead, and the structure is strong:

- The spec pins **one** `basic-xxs` instance, no autoscaling: compute
  is **US$5/mo flat, cannot self-grow**. Nothing you deploy can add
  instances without you editing `.do/app.yaml`.
- Bandwidth overage is the only variable line, and Cloudflare sits in
  front absorbing/limiting traffic for free; with edge rate rules the
  origin can't be made to stream meaningful egress.
- `NSW_DAILY_BUDGET` caps upstream calls; per-IP + global rate limits
  cap serving work.
- Billing alerts at US$5/US$8 are the tripwire; **the panic move is
  absolute**: `doctl apps delete` ends all spend instantly, recreate
  later in ~10 min (stateless).

Realistic worst month ≈ US$5 + cents. A surprise bill requires DO to
bill something the spec doesn't define — alerts exist for exactly
that. This is as close to a threshold guarantee as DO allows; the
only true hard-cap option is a prepaid-style provider, which we
rejected for other reasons (locked architecture).

**"Even under attack?"** — walking the bot scenarios, because the
only variable cost line is bandwidth (egress):

| Attack | What happens | Bill impact |
|---|---|---|
| Volumetric DDoS on `bff.krail.app` | Dies at Cloudflare's edge (free, unmetered DDoS mitigation) — never reaches DO | ~0 |
| Bots hammering the direct DO URL | Token gate answers 403s (~200 bytes each). Generating even 100 GB of 403 egress needs ~500M requests, and the single fixed container saturates CPU long before — it *slows down*, it cannot scale up or bill more | cents-to-few-$ |
| Distributed botnet through Cloudflare with valid-looking requests | CF per-IP rules thin it; BFF global cap (50 rps) bounds total work; and the structural kicker: **big responses only exist when NSW returns data, and NSW calls stop at `NSW_DAILY_BUDGET` (10k/day)**. After that everything is tiny 503s. Max heavy egress ≈ 10k × ~100 KB = ~1 GB/day ≈ 30 GB/mo | single-digit $ at worst |
| Something nobody predicted | Billing alerts at US$5/US$8 fire; `doctl apps delete` is the absolute ceiling-enforcer — spend stops the moment you run it, app users unaffected (kill switch → NSW direct) | bounded by how fast you react to the alert email |

Net: an attacker can make the BFF *unavailable* (worst case — and the
app degrades to NSW-direct via the kill switch), but there is no
lever that converts attacker traffic into meaningful spend. The
expensive resource (NSW data) is day-capped, and the compute is
fixed-price.

## 3. No-PR workflow — what replaces the safety net

| PR gave you | Replacement |
|---|---|
| CI before merge | `./gradlew :server:test` locally before push (pr.yml still runs on main pushes — treat a red ✗ on GitHub as "fix forward immediately") |
| Review pause | The dashboard soak (step C) is the review |
| Revert unit | Keep commits small + single-purpose; `git revert <sha> && git push` is the rollback, and once deployed it auto-redeploys the fix |
| Broken main can't deploy | **Gone.** This is the real cost of no-PRs after deploy. If it bites twice, reconsider — flipping back is just re-adding the ruleset rule. |

## 4. Open-source hygiene — what stays OUT of the public repo

Never commit:
- **Secrets:** NSW API keys (any of them), `CF_ORIGIN_TOKEN`, future
  FCM service-account JSON, DO API tokens. They live in: env vars on
  DO (encrypted), your password manager, `local.properties`
  (gitignored — verify with `git ls-files | grep local.properties`
  whenever unsure).
- **`DEPLOY_CHECKLIST.md`** (the working copy — it has secrets pasted
  in; only the `.template.md` is committed).
- Real `Authorization` headers in any captured fixture/log/doc —
  scrub before committing fixtures.

Fine to be public (don't over-hide):
- GTFS-R fixture bytes — public transit data, no auth material inside.
- `.do/app.yaml`, rate-limit numbers, architecture docs — none of it
  is exploitable; the token gate + key secrecy are the security
  boundary, not obscurity.
- The DO direct URL leaking somewhere is a non-event (origin answers
  403 without the token).

Rule of thumb: **if leaking it costs money or access, it's a secret;
if it just explains the system, it's documentation.** When unsure run
the audit gate's log check: nothing greppable for `apikey`/tokens.

**Planning/strategy docs specifically** (the .md files we keep
writing): committed docs in `docs/` are public the moment they're
pushed — write them knowing that. Audited 2026-06-12: everything
currently committed is engineering documentation, safe to publish
(controls described are visible in the source anyway; obscurity is
not the security boundary). For anything that *shouldn't* be public —
personal budgets, account details, half-formed business ideas,
incident notes — two gitignored escapes exist:

- `docs/private/` — directory, never pushed
- `*.private.md` — any file, anywhere in the repo, never pushed

**Automatic guard:** `.githooks/pre-commit` scans every staged diff
for credential patterns (JWTs, private keys, GitHub/DO/Google/AWS
token shapes) and blocks the commit. Enabled via
`git config core.hooksPath .githooks` — **run that once in every
fresh clone** (hook configs don't travel with `git clone`). False
positive? `git commit --no-verify`.

## 5. Keeping this plan honest

When a phase completes: tick TODO.md, update STATUS.md's "what's
next", and if reality diverged from a design doc, fix the doc in the
same commit as the code. The docs are only useful while they're true.
