# 🚨 Emergency runbook — exact steps when something's wrong

> For production incidents. No theory, just steps. Each scenario:
> do → verify → recover. Read top-to-bottom *once* on a calm day so
> you know what's here.
>
> **The golden rule, in every scenario: protect app users FIRST
> (step "flip the kill switch"), spend/secrets second, diagnosis
> last.** The app survives without the BFF — it falls back to calling
> NSW directly. Killing the server never breaks the app.

Keep handy (fill into your password manager, not this file):
DO app ID, Cloudflare login, Firebase console login, NSW Open Data login.

---

## Scenario A — "STOP THE SERVER NOW" (the big red button)

Use when: runaway bill fear, suspected compromise, any panic.

1. **Flip the app kill switch** (only matters once the app uses the
   BFF; skip before that):
   - <https://console.firebase.google.com> → KRAIL project →
     **Remote Config** → set `bff_kill_switch` = `true` → **Publish
     changes** (top right).
   - Effect: every app reverts to NSW-direct within ≤ 5 min. Users
     notice nothing.
2. **Destroy the DO app** (this is what stops all spend):
   ```bash
   doctl apps list                      # find the ID
   doctl apps delete <APP_ID> --force
   ```
   No terminal handy? Phone browser works:
   <https://cloud.digitalocean.com> → **Apps** → `krail-bff` →
   **Settings** tab → scroll to bottom → **Destroy** → type the app
   name to confirm.
3. **Verify it's dead:**
   ```bash
   curl -m 5 https://bff.krail.app/health        # expect: 5xx / error
   doctl apps list                                # expect: no krail-bff
   ```
   And verify users are fine: open the KRAIL app → plan a trip →
   works (NSW direct).

**Recovery later** (~10 min, the BFF is stateless — nothing is lost):
1. `doctl apps create --spec .do/app.yaml`
2. Re-set secrets `NSW_API_KEY` + `CF_ORIGIN_TOKEN` (password manager)
   in DO console → Apps → krail-bff → Settings → env vars.
3. Cloudflare → DNS → point the `bff` CNAME at the new
   `*.ondigitalocean.app` hostname.
4. Smoke: `curl https://bff.krail.app/health` → 200.
5. Firebase RC: `bff_kill_switch` = `false` → Publish.

---

## Scenario B — billing alert email arrived (US$5 or US$8)

Don't panic-delete yet — the $5 alert fires every normal month
(that's the instance itself). Decide with data, ~5 minutes:

1. **Look at the bill breakdown:**
   <https://cloud.digitalocean.com> → left sidebar **Billing** →
   current month → expand line items.
   - Only `App Platform basic-xxs ~$5`? **Normal month. Stop here.**
   - Bandwidth/overage line growing? Continue.
2. **Look at traffic:** DO console → Apps → krail-bff → **Insights**
   tab (CPU / memory / bandwidth graphs) and Cloudflare dashboard →
   krail.app → **Analytics** (requests, top IPs/paths).
3. **If it's attack traffic:** go to Scenario C.
4. **If you can't tell within 10 minutes:** Scenario A. Killing it
   costs nothing (users fall back); investigating while it burns
   does.

---

## Scenario C — under attack / traffic spike (server slow, weird logs)

1. **Tighten Cloudflare first** (free, instant, reversible):
   <https://dash.cloudflare.com> → krail.app → **Security → WAF →
   Rate limiting rules** → lower the per-IP rule (e.g. 60 → 20
   req/min). For a hard wall: **Security → Settings → "Under Attack
   Mode" ON** (challenges every request — fine, the app's API calls
   will fail but kill switch covers users; see step 2).
2. **Flip `bff_kill_switch` = true** (Firebase, as in Scenario A) so
   real users don't fight the attackers for rate-limit slots.
3. **Watch:** Cloudflare Analytics → is blocked traffic climbing and
   origin traffic falling? Good — hold and wait it out. Logs:
   ```bash
   doctl apps logs <APP_ID> --type RUN --follow
   ```
4. **If the origin is still being hit hard** (attacker found the
   direct DO URL): it's all 403s (cheap), but if CPU is pinned and
   you want it over: Scenario A. The attacker is then hitting a
   deleted app.
5. **After it passes:** restore rate rules / Under Attack Mode,
   un-flip the kill switch, and rotate `CF_ORIGIN_TOKEN` if the
   direct URL was being targeted with the right header (DEPLOYMENT.md
   → Secret rotation).

---

## Scenario D — a secret leaked (key/token committed, pasted, exposed)

Order: rotate first, clean up second. Rotation steps live in
**[DEPLOYMENT.md → Secret rotation](../reference/DEPLOYMENT.md)**.

- `NSW_API_KEY`: mint new key at
  <https://opendata.transport.nsw.gov.au> → set in DO env vars →
  verify trip endpoint works → **delete the leaked key** in the NSW
  portal (this is the step that actually ends the leak).
- `CF_ORIGIN_TOKEN`: `openssl rand -hex 32` → update Cloudflare
  Transform Rule AND DO env var (order per DEPLOYMENT.md).
- If it was committed to git: rotating already killed its value. Do
  NOT force-push history rewrites on a public repo (clones exist);
  just rotate.
- Verify nothing else leaked: `git log -p -3 | grep -iE "apikey|token|eyJ"`.

---

## Scenario E — bad deploy (pushed broken code, app users on BFF erroring)

1. Firebase RC `bff_kill_switch` = `true` → users safe instantly.
2. Fix forward or revert:
   ```bash
   git revert <bad-sha> && ./gradlew :server:test && git push
   ```
   Push to main auto-deploys (~5 min). Watch DO → Apps → Activity
   until **Active**.
3. Smoke: `curl https://bff.krail.app/health` + one real endpoint.
4. Kill switch back to `false`.

---

## Drill (do once, after first deploy)

Spend 15 minutes proving you can execute Scenario A: flip the RC flag
(watch a dev build fall back), `doctl apps delete`, recreate from
spec, re-set secrets, smoke test. An emergency plan you've never run
is a hypothesis, not a plan.
