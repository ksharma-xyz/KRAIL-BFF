# First deploy — guided walkthrough

> For a first-ever backend deploy. This doc **sequences** the existing
> material and fills its gaps; it does not replace it:
>
> - [`DEPLOYMENT.md`](../reference/DEPLOYMENT.md) — the reference runbook
>   (rotation, DR, troubleshooting). Where a step exists there, this doc
>   links it.
> - `DEPLOY_CHECKLIST.template.md` (repo root) — copy it to a gitignored
>   working file and tick boxes as you go:
>   `cp DEPLOY_CHECKLIST.template.md DEPLOY_CHECKLIST.md`
> - [`SECURITY_AUDIT_2026-06.md`](../archive/SECURITY_AUDIT_2026-06.md)
>   — read §5 (pre-deploy gate) before you start; you'll re-run it at the
>   end.
>
> Architecture you're building:
> `KRAIL app → Cloudflare (free) → DO App Platform basic-xxs (US$5/mo, Sydney) → NSW API`.
> Total ≈ A$8/mo, flat. Budget a quiet evening for steps 1–5 and a
> second one for 6–8.

---

## 1 · Accounts & prerequisites (~30 min)

1. **DigitalOcean account** — sign up, add billing.
   Immediately set billing alerts: console → Billing → Notifications →
   alerts at **US$5** and **US$8**. DO never auto-stops on overspend;
   alerts are your tripwire (see §9 for why you're still bounded).
2. **`doctl` CLI**:
   ```bash
   brew install doctl
   doctl auth init      # paste an API token from DO console → API
   doctl account get    # sanity check
   ```
3. **Mint the BFF-only NSW API key** — *blocking, from audit finding F1.*
   At <https://opendata.transport.nsw.gov.au/>: log in → create a new
   application/key (free). This key is **only** for the production BFF —
   not the app's build key, not your `local.properties` key. Store it in
   your password manager now.
4. **Cloudflare account** — free plan is enough. Domain move is step 2.

---

## 2 · Move `krail.app` DNS to Cloudflare (~20 min + propagation)

Your domain currently serves the website from GitHub Pages with DNS at
the registrar. Cloudflare needs to run the DNS for `krail.app` so it can
proxy `bff.krail.app`. **GitHub Pages keeps working throughout** — you
are changing who answers DNS questions, not where the site lives.

1. Cloudflare dashboard → **Add a site** → `krail.app` → Free plan.
2. Cloudflare scans and imports your existing records. Verify it picked
   up the GitHub Pages ones; if not, add them manually:
   - Apex `krail.app` → `A` records `185.199.108.153`, `185.199.109.153`,
     `185.199.110.153`, `185.199.111.153`
   - `www` → `CNAME` → `<your-github-username>.github.io`
3. Set the GitHub Pages records to **DNS only** (grey cloud) for now —
   it keeps GitHub's own HTTPS cert handling undisturbed. (You can
   experiment with proxying the website later; it's independent of the
   BFF.)
4. Cloudflare shows two **nameservers** (e.g. `xxx.ns.cloudflare.com`).
   At your **registrar**, replace the current nameservers with those two.
5. Wait for Cloudflare to email "site is active" (minutes to a few
   hours). Verify nothing broke:
   ```bash
   dig +short krail.app          # the four 185.199.x.153 IPs
   curl -sI https://krail.app | head -1   # HTTP/2 200 — Pages still up
   ```
6. In Cloudflare → SSL/TLS → set mode **Full (strict)**.

Do not create the `bff` record yet — you need the DO URL first.

---

## 3 · Pre-flight repo checks (~5 min)

```bash
git switch main && git pull
git ls-files | grep local.properties   # must print ONLY local.properties.template
./gradlew :server:test                  # green before you ship it
```

Skim [`.do/app.yaml`](../../.do/app.yaml): single `basic-xxs` instance,
`instance_count: 1`, region `syd`, `MIN_APP_VERSION: 0.0.0`. That file
is the whole infrastructure definition — what's not in it (autoscaling,
bigger instances) cannot silently happen.

---

## 4 · Provision DigitalOcean (~30 min)

Follow checklist §3; the sequence with gap-fills:

1. ```bash
   doctl apps create --spec .do/app.yaml
   export DO_APP_ID=<uuid it printed>
   ```
2. Generate the origin token and keep it in your password manager:
   ```bash
   openssl rand -hex 32
   ```
3. DO console → Apps → krail-bff → Settings → env vars. Add as
   **encrypted**:
   - `NSW_API_KEY` = the BFF-only key from step 1.3
   - `CF_ORIGIN_TOKEN` = the hex from step 4.2

   And verify the non-secrets (should already match `.do/app.yaml`):
   - `BFF_DEV_PASSTHROUGH` absent or `false` — **must never be true in
     prod** (audit F2)
   - `STOPS_MANIFEST_URL` / `ROUTES_MANIFEST_URL` — leave **unset** for
     v1; the manifest endpoints return 404 until you ship dataset
     distribution (see [ROADMAP.md](../reference/ROADMAP.md)).
   - `TRACK_DATASET_MANIFEST_URL` — already in `.do/app.yaml`, points
     at the **KRAIL-GTFS** repo's rolling `track-latest` release. Needs
     that release to exist: in KRAIL-GTFS run Actions → Build Track
     Datasets → Run workflow once. Until then tracking degrades
     gracefully (search-dataset stop names, straight-line map
     fallback).
4. Watch the first build in the Activity tab (~5 min), then:
   ```bash
   doctl apps get $DO_APP_ID --format DefaultIngress --no-header
   export DO_DIRECT_URL=https://krail-bff-xxxxx.ondigitalocean.app
   ```
5. **Smoke the direct URL** — the token gate activates once
   `CF_ORIGIN_TOKEN` is set, so health works but real endpoints 403:
   ```bash
   curl -s $DO_DIRECT_URL/health        # 200 — container alive
   curl -s -o /dev/null -w '%{http_code}\n' \
     "$DO_DIRECT_URL/api/v1/stops/200060/departures-proto"   # 403 — gate works
   ```

Also set resource alerts now: app → Insights/Alerts → CPU > 80%,
memory > 80%.

---

## 5 · Cloudflare in front (~20 min)

Checklist §4 / DEPLOYMENT.md §2, in order:

1. **DNS**: Cloudflare → DNS → add `CNAME` `bff` →
   `krail-bff-xxxxx.ondigitalocean.app`, **Proxied** (orange cloud).
2. **Transform Rule** (this is what unlocks the origin): Rules →
   Transform Rules → Modify Request Header → create:
   - When: `Hostname equals bff.krail.app`
   - Then: set static header `CF-Origin-Token` = the hex from step 4.2
   - Deploy.
3. **Rate-limit rules**: Security → WAF → Rate limiting rules:
   - 60 req/min per IP on `bff.krail.app` → Block 1 min
   - 20 req/10 s per IP → Managed Challenge
4. Verify:
   ```bash
   dig +short bff.krail.app    # Cloudflare IPs (104.x / 172.x), NOT DO's
   curl -s https://bff.krail.app/health             # 200 via Cloudflare
   ```

### Origin lockdown — what to expect

App Platform offers **no IP firewall** (DO Cloud Firewalls are a
Droplet feature). The direct `*.ondigitalocean.app` URL therefore stays
*reachable* — and that's fine, because the token gate means it answers
**403** to anyone who isn't Cloudflare:

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  "$DO_DIRECT_URL/api/v1/stops/200060/departures-proto"
# 403 — locked. Only Cloudflare-proxied traffic carries the token.
```

If that ever prints 200, `CF_ORIGIN_TOKEN` is unset on DO — fix before
anything else.

---

## 6 · Full smoke suite (~15 min)

Checklist §6 has the copy-paste commands (trip JSON, trip proto,
departures proto, park-ride proto, correlation ID). Run all of them
against `https://bff.krail.app`. Additionally, from the audit gate:

```bash
# Dev passthrough is dead in prod
curl -s -o /dev/null -w '%{http_code}\n' \
  "https://bff.krail.app/internal/passthrough?url=https://api.transport.nsw.gov.au/v1/tp/trip"
# expect 404

# Rate limiting bites (run ~30 requests fast)
for i in $(seq 1 30); do curl -s -o /dev/null -w '%{http_code} ' \
  "https://bff.krail.app/api/v1/stops/200060/departures-proto"; done; echo
# expect a tail of 429s

# Logs are flowing and clean
doctl apps logs $DO_APP_ID --type RUN --tail 100 | grep -iE 'authorization|apikey' 
# expect: nothing (or only redacted values)
```

Then run the whole **pre-deploy gate** in
[SECURITY_AUDIT_2026-06.md §5](../archive/SECURITY_AUDIT_2026-06.md).
All boxes ticked = you are live.

---

## 7 · After deploy

- **Wipe your working checklist**: `rm DEPLOY_CHECKLIST.md` (it has
  secrets pasted in it).
- **Day-to-day**: pushing to `main` auto-deploys (tests gate the merge).
  Logs: `doctl apps logs $DO_APP_ID --type RUN --follow`.
- **First two weeks** (checklist §8): glance at DO billing weekly
  (expect ≈ US$5 flat), watch for sustained >100 req/min before any app
  rollout (would mean someone found the URL), check the NSW daily-budget
  counter in logs.
- **App rollout**: hand `https://bff.krail.app` to the KRAIL app per
  checklist §7, then follow
  [`BFF_ADOPTION_GUIDE.md`](../reference/BFF_ADOPTION_GUIDE.md)
  (0% → 10% → 50% → 100% → 2-week grace → delete the NSW-direct path).
  Rollback at any point is a Firebase RC flag flip (`bff_kill_switch`),
  ≤5 min to take effect — checklist §9.

---

## 8 · Making changes after deploy (CI/CD — mostly already wired)

The pipeline for every future change is already in place; this is the
loop you'll live in:

```
branch → push → PR → CI (pr.yml: build + tests; CodeQL) → merge to main
   → DigitalOcean auto-builds + deploys (deploy_on_push: true)
   → health check gates the swap → smoke /health
```

What exists today (verified 2026-06-11):

- **`pr.yml`** — builds + runs `:server:test` on every PR to `main`
  and on pushes to `main`.
- **CodeQL** (PRs + weekly) and **Dependabot** (CVE alerts).
- **GitHub ruleset on `main`** — PR required, linear history, no
  force-push, no deletion.
- **DO auto-deploy** — `.do/app.yaml` has `deploy_on_push: true`; any
  merge to `main` rebuilds the Docker image and rolls it out. The app's
  health check must pass before the new container takes traffic, so a
  completely broken build never replaces a working one.
- **Dataset pipeline** — lives in the KRAIL-GTFS repo (`track-dataset.yml`
  publishes the tracking datasets weekly); `proto-bump.yml` here PRs proto
  submodule updates.

**One gap to close (2 min, GitHub UI):** the `main` ruleset requires a
PR but does **not** require status checks to pass — a red CI run
doesn't block the merge button. Fix: repo → Settings → Rules → `main`
→ add **Require status checks to pass** → select the `pr.yml` build
job. After that, broken code physically cannot reach `main` (and
therefore cannot deploy).

**Why no prod/release branch (decided 2026-06-11):** release branches
exist for mobile because app releases are irreversible — store review,
slow user updates, no binary recall. A stateless server has the
opposite properties: a bad merge is reverted and live again in ~5 min,
the health check refuses broken containers, and `bff_kill_switch`
makes even that invisible to users. Trunk-based (`main` = prod) is the
correct model here; a prod branch would only add merge ceremony.
Optionally `git tag vX.Y.Z` notable releases for traceability — a
label, not a gate. Revisit only if deploys gain real risk (a database
with migrations, multiple contributors); the escape hatch then is
`deploy_on_push: false` + manual `doctl apps create-deployment`, not a
branch model.

Per-change routine once that's set:

1. Branch, commit, push, open PR. Wait for green checks.
2. Merge (linear history enforced — use rebase/squash, not merge
   commits).
3. Watch DO → Apps → Activity until the deployment goes **Active**
   (~5 min), then `curl -s https://bff.krail.app/health`.
4. If something regresses: re-deploy the previous commit by reverting
   the PR on `main` (auto-deploys again), or use the rollback ladder in
   `DEPLOY_CHECKLIST.template.md` §9 (`bff_kill_switch` RC flag is the
   instant, zero-risk option once the app integrates).

## 9 · Cost: why you're bounded without a hard cap

DigitalOcean cannot enforce a spending cap. You are still safe because
**nothing in this setup can scale itself**:

| Layer | Bound |
|---|---|
| Compute | One fixed `basic-xxs` container, `instance_count: 1`, no autoscaling in the spec. The compute bill is US$5/mo whatever happens. |
| Bandwidth | Cloudflare absorbs and rate-limits traffic at the edge for free before it reaches DO. |
| Upstream | `NSW_DAILY_BUDGET=10000` hard-stops NSW calls per day. |
| Per-client | BFF per-IP (5 rps) + global (50 rps) token buckets. |
| Tripwires | DO billing alerts at US$5/US$8; CPU/memory alerts. |

**Panic move** (site under attack, bill anxiety, anything): the app
keeps working without the BFF — flip `bff_kill_switch` in Firebase RC,
then `doctl apps delete $DO_APP_ID`. Recreating later takes ~10 minutes
(DEPLOYMENT.md → Disaster recovery); the BFF is stateless, there is
nothing to lose.
