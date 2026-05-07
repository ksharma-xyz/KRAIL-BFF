# KRAIL-BFF Deployment

> Operational runbook for deploying KRAIL-BFF to DigitalOcean App Platform behind Cloudflare. Pairs with [MODERNIZATION_PLAN.md](MODERNIZATION_PLAN.md) §0.3.

Architecture: `KRAIL app → Cloudflare → DigitalOcean App Platform → NSW Open Data API`. Cost ceiling A$8/mo (DO Basic-XXS) + A$0 (Cloudflare free).

---

## First-time setup

### 1. DigitalOcean

**Provision the app**
1. Install `doctl` (`brew install doctl`) and authenticate (`doctl auth init`).
2. Create the app from the spec:
   ```
   doctl apps create --spec .do/app.yaml
   ```
   This returns an app ID — note it.
3. Confirm in the DO console that the app is in the `syd` region and using the `basic-xxs` instance size.

**Set secrets** (never commit these):
1. In DO console → Apps → krail-bff → Settings → App-Level Environment Variables, add:
   - `NSW_API_KEY` (encrypted) — your NSW Open Data API key.
   - `CF_ORIGIN_TOKEN` (encrypted) — random 32-byte hex generated below.
2. Or via CLI:
   ```
   doctl apps update <APP_ID> \
     --spec .do/app.yaml \
     --env NSW_API_KEY=YOUR_KEY \
     --env CF_ORIGIN_TOKEN=$(openssl rand -hex 32)
   ```

**Set billing alerts**
1. DO console → Billing → Notifications.
2. Add alerts at US$5 and US$8 (50% / 100% of the A$15 ceiling).
3. DO does not auto-stop on overspend — these are **alerts only**. Cloudflare in front protects against bandwidth-driven spikes; the alerts catch anything else.

### 2. Cloudflare

**DNS + proxying**
1. Add the BFF hostname (e.g. `bff.krail.app`) as a CNAME / A record pointing at the DO app's URL or IP.
2. Set the proxy status to **Proxied** (orange cloud). Without this, you're not behind Cloudflare and the IP allowlist below has nothing to enforce against.

**Origin token transform rule**
1. Cloudflare dashboard → Rules → Transform Rules → Modify Request Header.
2. Create rule: "Add CF-Origin-Token to BFF requests."
3. Match: `(http.host eq "bff.krail.app")`.
4. Set static header: `CF-Origin-Token` = your `CF_ORIGIN_TOKEN` value.
5. Deploy.

**Edge rate limiting (free tier — 10 rules)**
1. Security → WAF → Rate limiting rules.
2. Suggested rules:
   - Per-IP: 60 req / minute / per IP, expression `(http.host eq "bff.krail.app")`, action: Block 1 minute.
   - Per-IP burst: 20 req / 10 sec / per IP, action: Challenge.

### 3. DO firewall — IP allowlist

Lock origin to only accept inbound from Cloudflare's IP ranges.

1. Get current Cloudflare IPs: `curl https://www.cloudflare.com/ips-v4` and `https://www.cloudflare.com/ips-v6`.
2. DO console → Networking → Firewalls → Create Firewall.
3. Inbound rule: HTTPS (port 443), source = the Cloudflare IP ranges from step 1.
4. Apply firewall to the App Platform droplet.

⚠ Cloudflare changes their IPs occasionally. Set a calendar reminder (or cron) to refresh quarterly.

---

## Day-to-day

### Deploying

`deploy_on_push: true` is set in `.do/app.yaml`. Any push to `main` triggers a build + deploy automatically. The `pr.yml` workflow gates merges with `./gradlew :server:test`.

To force a redeploy without a code change:
```
doctl apps create-deployment <APP_ID>
```

### Smoke tests after deploy

```bash
# Health (no auth)
curl -fsS https://bff.krail.app/health
# Expected: 200 with {"status": "..."}

# Trip endpoint with required headers
curl -fsS \
  -H "X-Krail-Version: 1.5.0" \
  "https://bff.krail.app/api/v1/trip/plan?origin=10101100&destination=10101328"
# Expected: 200 with JSON

# Negative: missing version header (should be 200 if MIN_APP_VERSION=0.0.0, 400 otherwise)
curl -i "https://bff.krail.app/api/v1/trip/plan?origin=10101100&destination=10101328"

# Negative: direct origin hit (should be blocked by DO firewall)
curl -i --resolve bff.krail.app:443:<DO_IP> https://bff.krail.app/health
# Expected: connection refused or timeout
```

### Logs

```
doctl apps logs <APP_ID> --type RUN --follow
```

Look for the JSON-structured logs from logback. Filter for `correlationId` to trace a single request.

---

## Secret rotation

### NSW_API_KEY

NSW does not offer programmatic rotation; you mint a new key in their portal.

1. Generate a new key in NSW Open Data Hub (do not delete the old yet).
2. Set the new key in DO:
   ```
   doctl apps update <APP_ID> --env NSW_API_KEY=<new key>
   ```
3. Wait for the app to redeploy and become healthy (~2 min).
4. Smoke test the trip endpoint.
5. Delete the old NSW key in their portal.

### CF_ORIGIN_TOKEN

Rotation requires updating Cloudflare and DO close together. Do this during low-traffic hours.

1. Generate new value: `openssl rand -hex 32`.
2. In Cloudflare Transform Rules: edit the rule, change the value, **save without deploying yet**.
3. In DO: set `CF_ORIGIN_TOKEN` to the new value, wait for deploy.
4. In Cloudflare: deploy the transform rule change.
5. Smoke test.

A brief window exists where Cloudflare and origin disagree — accept that or do a dual-token approach (env vars `CF_ORIGIN_TOKEN_PRIMARY` / `_SECONDARY`, accept either). Dual-token is a future enhancement; for v1 a 30-second window is acceptable.

### Pre-shipped JWT signing keys (future PRs)

Not used yet — the BFF has no auth. When auth is added, document the rotation steps here.

---

## Disaster recovery

Everything we run is stateless. To restore from total loss of the DO app:

1. `doctl apps create --spec .do/app.yaml` from this repo.
2. Set `NSW_API_KEY` and `CF_ORIGIN_TOKEN` from your password manager backup.
3. Update Cloudflare DNS to point at the new app URL.
4. Smoke test.

Total expected RTO: ~10 minutes.

There is no persistent database to restore. If GTFS dataset distribution (PR 4) is failing, GitHub Releases has the artifact history.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| 502 from Cloudflare | DO app down or unhealthy | `doctl apps logs <APP_ID> --type RUN --tail 200` |
| 403 forbidden on every request | `CF_ORIGIN_TOKEN` mismatch | Re-sync values between Cloudflare Transform Rule and DO env |
| 426 Upgrade Required | `MIN_APP_VERSION` too high or app version below floor | Lower the floor or roll out the new app version |
| 503 service_temporarily_limited | Daily NSW budget hit | Wait until Sydney midnight or raise `NSW_DAILY_BUDGET` |
| 429 Too Many Requests | Per-IP or global rate limit hit | Tune `BFF_PER_IP_RPS` / `BFF_RATE_LIMIT_RPS` if legitimate; otherwise leave |
| Direct origin IP works (firewall not blocking) | DO firewall not applied or Cloudflare IPs stale | Verify firewall is attached to the droplet; refresh IP ranges |
| Build fails on deploy | Dockerfile path or test failure | Check DO build logs; reproduce locally with `docker build .` |

---

## Pre-deploy checklist

Before flipping the switch on a real domain:

- [ ] DO app provisioned and healthy on a temporary `*.ondigitalocean.app` URL
- [ ] `NSW_API_KEY` set as a secret in DO; `local.properties` confirmed not in repo
- [ ] `CF_ORIGIN_TOKEN` set in both Cloudflare and DO
- [ ] DO firewall attached, inbound limited to Cloudflare IPs
- [ ] DO billing alerts at 50% and 100%
- [ ] Cloudflare proxying enabled (orange cloud) on the BFF DNS record
- [ ] Cloudflare Transform Rule for `CF-Origin-Token` deployed
- [ ] Cloudflare rate limit rules active
- [ ] Smoke test against the proxied URL passes
- [ ] Direct origin IP hit is blocked
- [ ] Logs are flowing
- [ ] `MIN_APP_VERSION` matches the minimum app version you actually want to support
