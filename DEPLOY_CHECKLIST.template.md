# Deploy checklist · KRAIL-BFF (template)

> **How to use this file**
>
> 1. Copy → `cp DEPLOY_CHECKLIST.template.md DEPLOY_CHECKLIST.md`
> 2. The working copy (`DEPLOY_CHECKLIST.md`) is gitignored — safe to
>    paste secrets / hostnames / URLs in while you work.
> 3. After deploy, wipe the working copy (`shred -uvz DEPLOY_CHECKLIST.md`
>    or just `rm`). This template stays committed so the next deploy
>    has a starting point.
> 4. If steps change after a successful deploy, **update this template**
>    so future-you doesn't redo discovery work.

---

## 0 · One-time setup (skip if already done)

- [ ] DigitalOcean account exists, billing set up (~A$8/mo flat).
- [ ] `doctl` installed + authenticated:
  ```bash
  brew install doctl
  doctl auth init      # paste DO API token
  doctl account get    # sanity check
  ```
- [ ] Cloudflare account exists with `krail.app` (or chosen domain) added
      and **nameservers switched to Cloudflare** at the registrar.
      *(First time? `docs/guides/FIRST_DEPLOY.md` §2 walks through the
      move, including keeping the GitHub Pages site working.)*
- [ ] You own / control DNS for the chosen hostname.
- [ ] Separate **BFF-only** NSW Open Data API key registered at
      <https://opendata.transport.nsw.gov.au/>. *(Do not reuse the
      in-app key — keep them rotatable independently.)*
      - [ ] Paste key here while you work, wipe before discarding the file:
        ```
        BFF_NSW_API_KEY=<paste-here-then-delete>
        ```

---

## 1 · Decisions to lock down

- [ ] **Hostname:** `bff.krail.app`
- [ ] **DO region:** Sydney (`syd1`, already in `.do/app.yaml`)
- [ ] **CF_ORIGIN_TOKEN:** generated with `openssl rand -hex 32`. Paste here while working:
      ```
      CF_ORIGIN_TOKEN=<paste-here-then-delete>
      ```

---

## 2 · Verify the code is on main

*(The historical PR stack is fully merged — #46 through #72. Nothing to
merge; just confirm you're deploying what you think you are.)*

- [ ] ```bash
      git switch main && git pull
      gh pr list --state open --base main   # expect: empty
      git log --oneline -3                  # expect: recent merged PRs
      ./gradlew :server:test                # expect: green
      ```

---

## 3 · Provision DigitalOcean App Platform

- [ ] Verify the spec:
      ```bash
      cat .do/app.yaml | head -20
      ```
- [ ] Create the app:
      ```bash
      doctl apps create --spec .do/app.yaml
      # Note the app ID (UUID) it returns.
      export DO_APP_ID=<uuid-here>
      ```
- [ ] List apps to confirm:
      ```bash
      doctl apps list
      ```
- [ ] Set the secrets in the DO console (Apps → krail-bff → Settings →
      Components → env vars). *(Alternatively `doctl apps update`, but
      secrets are easiest in the UI.)*

      | Env var | Value | Notes |
      |---|---|---|
      | `NSW_API_KEY` | (BFF-only NSW key) | from section 0 |
      | `CF_ORIGIN_TOKEN` | (random 32+ char hex) | from section 1 |
      | `MIN_APP_VERSION` | `0.0.0` | hold per KRAIL request |
      | `BFF_DEV_PASSTHROUGH` | `false` | must NOT be true in prod |
      | `BFF_CORS_ORIGINS` | empty | no cross-origin clients in prod |
      | `STOPS_MANIFEST_URL` | (unset) | Phase D dropped; endpoint returns 404 |
      | `ROUTES_MANIFEST_URL` | (unset) | same |

- [ ] Wait for first deploy (DO Apps → Activity tab).
- [ ] Find the assigned `ondigitalocean.app` URL:
      ```bash
      doctl apps get $DO_APP_ID --format DefaultIngress --no-header
      # e.g. https://krail-bff-xxxxx.ondigitalocean.app
      export DO_DIRECT_URL=https://krail-bff-xxxxx.ondigitalocean.app
      ```
- [ ] Direct smoke (NOT yet locked down):
      ```bash
      curl -s $DO_DIRECT_URL/health
      # expect: {}
      ```

---

## 4 · Cloudflare in front

- [ ] In Cloudflare dashboard → DNS:
      Add A/CNAME record `bff.krail.app` → DO ingress IP (proxied; orange cloud on).
      *(DO Apps' ingress IP comes from `doctl apps get $DO_APP_ID --format LiveURL`.)*
- [ ] Wait for DNS to propagate:
      ```bash
      dig +short bff.krail.app
      # expect: a Cloudflare IP (104.x.x.x or similar)
      ```
- [ ] SSL/TLS mode → **Full** (Cloudflare ⇄ DO origin both have certs).
- [ ] Transform Rule — Rules → Transform Rules → Modify Request Header:
      - Rule name: `add CF-Origin-Token`
      - When incoming requests match: `Hostname` equals `bff.krail.app`
      - Then: Set static header `CF-Origin-Token` = the same 32+ char hex from section 1
- [ ] Verify the rule fires:
      ```bash
      curl -s https://bff.krail.app/health
      # expect: {}    (because Cloudflare added the header)
      ```
- [ ] Direct DO call should now fail because the token is missing:
      ```bash
      curl -s -o /dev/null -w '%{http_code}\n' $DO_DIRECT_URL/health
      # expect: 403  (or your BFF's choice of failure code for missing CF-Origin-Token)
      ```

---

## 5 · Origin lockdown — verify the token gate

*(App Platform has **no** IP firewall — DO Cloud Firewalls are
Droplet-only and "Trusted Sources" is a managed-database feature. The
direct `ondigitalocean.app` URL stays reachable by design; the
application-level `CF-Origin-Token` gate is the lockdown.)*

- [ ] Direct DO URL on a real endpoint → rejected:
      ```bash
      curl -s -o /dev/null -w '%{http_code}\n' \
        "$DO_DIRECT_URL/api/v1/stops/200060/departures-proto"
      # expect: 403  (if 200: CF_ORIGIN_TOKEN unset/empty on DO — fix now)
      ```
- [ ] Same endpoint via Cloudflare → allowed:
      ```bash
      curl -s -o /dev/null -w '%{http_code}\n' \
        "https://bff.krail.app/api/v1/stops/200060/departures-proto"
      # expect: 200  (Transform Rule injects the token)
      ```
- [ ] Dev passthrough dead in prod:
      ```bash
      curl -s -o /dev/null -w '%{http_code}\n' \
        "https://bff.krail.app/internal/passthrough?url=https://api.transport.nsw.gov.au/v1/tp/trip"
      # expect: 404  (BFF_DEV_PASSTHROUGH must be false/unset)
      ```

---

## 6 · Production smoke tests

Run these in order. Stop and fix if any fail.

- [ ] Health:
      ```bash
      curl -s https://bff.krail.app/health
      # expect: {}
      ```
- [ ] Readiness (probes NSW upstream):
      ```bash
      curl -s https://bff.krail.app/ready
      # expect: {}    (200 — NSW upstream OK)
      ```
- [ ] Trip planner JSON (proves NSW key works):
      ```bash
      curl -s 'https://bff.krail.app/v1/tp/trip?name_origin=200070&name_destination=215020&depArrMacro=dep&type_destination=any&calcNumberOfTrips=2&type_origin=any&TfNSWTR=true&version=10.2.1.42&coordOutputFormat=EPSG:4326&itOptionsActive=1&computeMonomodalTripBicycle=false&cycleSpeed=16&useElevationData=1&outputFormat=rapidJSON' \
        | jq '.journeys | length'
      # expect: > 0
      ```
- [ ] Trip planner proto:
      ```bash
      curl -s -o /tmp/trip.pb -w 'http=%{http_code} bytes=%{size_download}\n' \
        'https://bff.krail.app/api/v1/trip/plan-proto?origin=200070&destination=215020&depArr=dep'
      # expect: http=200, bytes ~ 100,000
      ```
- [ ] Departures proto:
      ```bash
      curl -s -o /tmp/dep.pb -w 'http=%{http_code} bytes=%{size_download}\n' \
        'https://bff.krail.app/api/v1/stops/200060/departures-proto'
      # expect: http=200, bytes ~ 2,000
      ```
- [ ] Park & Ride proto:
      ```bash
      curl -s -o /tmp/parking.pb -w 'http=%{http_code} bytes=%{size_download}\n' \
        'https://bff.krail.app/api/v1/parking/availability-proto?stopIds=2155384,275010'
      # expect: http=200, bytes ~ 500
      ```
- [ ] Correlation ID round-trip:
      ```bash
      curl -s -D - 'https://bff.krail.app/health' -o /dev/null | grep -i "x-request-id"
      # expect: X-Request-Id header in the response
      ```

---

## 7 · KRAIL handoff

- [ ] Tell the KRAIL agent the deploy URL:
      ```
      KRAIL_BFF_PROD_BASE_URL=https://bff.krail.app
      ```
- [ ] KRAIL adds release-build `KRAIL_BFF_BASE_URL` pointing at it.
- [ ] KRAIL adds Firebase RC flag `enable_proto_bff` (default `false`).
- [ ] KRAIL begins Phase B cohort rollout: 10% → 50% → 100% over 7+ days.
- [ ] After 100% holds for 14 days, KRAIL deletes the JSON-fallback paths
      and (optionally) the in-app facility-list Firebase RC flag.

---

## 8 · Monitoring + cost gates (first 2 weeks)

- [ ] DigitalOcean alerts: set CPU > 80% alert + memory > 80% alert
      in the App Platform UI.
- [ ] Check NSW daily-budget usage weekly via logs or metrics.
- [ ] DO billing dashboard — confirm ~A$8/mo and no surprises.
- [ ] Watch for sustained > 100 req/min — that's a sign of either
      retry storms or someone discovered the URL outside Cloudflare.

---

## 9 · Rollback plan (if anything regresses)

- [ ] **Soft rollback (preferred):** flip Firebase RC
      `bff_kill_switch = true`. Every endpoint in the KRAIL app reverts
      to NSW direct within ≤ 5 min (the RC fetch interval). BFF stays
      up; users are unaffected.
- [ ] **Per-endpoint rollback:** flip `bff_use_for_<endpoint>` to
      `false` for the offending endpoint only.
- [ ] **BFF-side hard rollback:** redeploy a prior `main` SHA via
      `doctl apps create-deployment $DO_APP_ID --force-rebuild` after
      pointing the app spec at the previous git ref.
- [ ] **Cold disable:** Cloudflare → DNS → set the orange cloud to
      grey on `bff.krail.app` → all traffic stops resolving through
      Cloudflare. Drastic; only if compromised.

---

## 10 · Post-deploy cleanup

- [ ] Wipe secrets from the working copy before discarding:
      ```bash
      shred -uvz DEPLOY_CHECKLIST.md   # or just rm
      ```
- [ ] Update `STATUS.md` section 1 to reflect deploy done.
- [ ] Update `docs/handover/README.md` state-of-play: Phase B unblocked.
- [ ] If the deploy revealed any new steps or footguns, **update this
      template** (the `.template.md` file) so the next deploy benefits.
