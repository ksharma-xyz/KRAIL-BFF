# Roadmap — after the first deploy

> Where the BFF goes once it's live and the app rollout
> ([BFF_ADOPTION_GUIDE.md](BFF_ADOPTION_GUIDE.md)) is underway.
> Constraints that shape everything here: solo maintainer, hard budget
> ≈ A$15/mo with the BFF already taking ~A$8, no PII by design.

Ordered by recommended sequence:

1. [NSW key endgame](#1--nsw-api-key-endgame) — the security goal
2. [Dataset distribution](#2--stops--bus-routes-dataset-distribution) — stops/bus-routes via manifest
3. [Push notifications](#3--push-notifications-fcm-over-sns) — FCM
4. [Smaller items](#4--smaller-items)

---

## 1 · NSW API key endgame

**Goal:** no NSW credential ships inside the app binary, ever again.
Today the key is injected at build time (BuildKonfig, GitHub Actions
secret) and is extractable from any public APK — the founding reason
for the BFF.

Phases (mostly already encoded in the adoption guide; collected here):

| Phase | Action | State after |
|---|---|---|
| 0 — at deploy | Mint **BFF-only** NSW key for production (audit F1). | App key and server key independently rotatable. |
| 1 — rollout | Migrate endpoints to BFF per adoption guide cohorts (0→10→50→100%). | NSW-direct traffic shrinking. |
| 2 — grace | Hold 100% for 2+ weeks. Bump `MIN_APP_VERSION` so pre-BFF app versions are force-upgraded (426). | Only BFF-capable clients remain. |
| 3 — kill | Delete the in-app key at the NSW portal; remove it from GitHub Actions secrets and BuildKonfig. | Old binaries' baked key is dead. Extracting it gains nothing. |

Until phase 3 the extractable key is an **accepted, time-boxed risk**:
it's a free-tier transit-data key, so the blast radius is quota abuse
against NSW, not user data.

Note: `stopFinder()` in the app's `TripPlanningService` is **dead
code** — declared but never called from production code (verified
2026-06-11; local stops search replaced it). No BFF stop-finder route
is needed. Delete the dead service method + models from the app as
part of phase 3 cleanup so it can't be resurrected accidentally.

---

## 2 · Stops & bus-routes dataset distribution

**Today:** the app bundles `NSW_STOPS.pb` (2.2 MB, v59) and
`NSW_BUSES_ROUTES.pb` (2.5 MB, v32) as compose resources; versions are
constants in `SandookPreferences`. The refresh pipeline is: GitHub
Actions in the `krail-config` repo regenerates the datasets and opens a
**PR against the app repo**, which only takes effect after a full store
release. The BFF already has `/v1/data/stops/manifest` (302 → GitHub
Releases asset) and a weekly `stops-dataset.yml` build workflow.

**Target (decided 2026-06-11):** data updates stop flowing through app
releases entirely. Generation stays on **GitHub Actions** (free — no
server cron); distribution moves to BFF APIs:

```
GitHub Actions (weekly)                 App (on startup, throttled)
  build NSW_STOPS.pb vN ──► GitHub        GET /v1/data/stops/manifest
  build ROUTES.pb     vM    Releases  ◄── version > stored version?
                                          └─ yes → download .pb → verify
                                             checksum → NswStopsManager
                                             import → store new version
```

The manifest response (or a cheap `HEAD`-able version header/ETag)
carries `{version, url, sha256, byteSize}` per dataset — the app
compares against `KEY_NSW_STOPS_VERSION` / `KEY_NSW_BUS_ROUTES_VERSION`
and fetches only on change. The krail-config→app-PR pipeline retires
once this is at 100%.

**Design (locked):** stops **search stays local** in the app — the BFF
only distributes versioned datasets. The BFF stays a thin redirecting
manifest; **GitHub Releases stays the artifact store** (free bandwidth,
versioned history, zero new infra cost).

Remaining work:

1. **BFF:** set `STOPS_MANIFEST_URL` / `ROUTES_MANIFEST_URL` on DO to
   the GitHub Releases asset URLs (manifest endpoints currently 404
   while unset). Confirm the weekly workflow publishes both datasets.
2. **App:** on startup (Wi-Fi/unmetered preferred), `GET` the manifest;
   compare its version against the stored
   `KEY_NSW_STOPS_VERSION` / `KEY_NSW_BUS_ROUTES_VERSION`; if newer,
   download the `.pb`, verify its checksum from the manifest, then run
   the existing import path (`NswStopsManager` / `NswBusRoutesManager`
   already parse + insert transactionally — reuse, don't rewrite).
3. **Keep bundling** a baseline `.pb` in the app forever: first launch
   must work offline, and the download becomes a delta-freshness
   mechanism, not a dependency.
4. Rollout behind a Firebase RC flag like everything else
   (`bff_use_for_stops`), kill-switch compatible.

Payoff: stop/route data updates decouple from app releases (today a
data refresh = full store release), and the app binary can eventually
shrink if the bundled baseline is refreshed less often.

---

## 3 · Push notifications: FCM over SNS

**Today:** "info tiles" are in-app messages driven by Firebase Remote
Config JSON (`INFO_TILES` flag) — visible only when the user opens the
app. No push infrastructure exists (no FCM SDK in the app).

**Recommendation: Firebase Cloud Messaging (FCM), not AWS SNS.**

- Firebase is already integrated (Remote Config, Analytics,
  Crashlytics) — same console, same project, no new vendor.
- SNS would mean a new AWS account: another billing surface with **no
  hard spending cap**, against a ~A$15/mo budget, for zero functional
  gain at this scale.
- FCM is free at any realistic KRAIL volume and delivers to iOS via
  APNs through the same API.

Phased, cheapest-first:

**Phase 1 — console-sent campaigns (no server code, A$0).**
Add the FCM SDK to the app (Android first; iOS needs an APNs key in
Firebase). Subscribe all installs to a public topic, e.g. `nsw-alerts`.
Send broadcasts (service disruptions, "new version" nudges) from the
Firebase console by hand. Info tiles stay as the richer in-app surface;
a push can simply deep-link to one. No PII: topic subscription needs no
account or device registry on your side.

**Phase 2 — BFF-triggered pushes (later, only if needed).**
A small authenticated admin route (or a GitHub Actions job) calls the
FCM HTTP v1 API with a service-account credential to send
topic messages programmatically — e.g. auto-alert when NSW publishes a
major disruption via GTFS-RT alerts the BFF already proxies. Requires
care: the service-account JSON is a new secret (DO encrypted env), and
the trigger endpoint must not be publicly callable. Skip until phase 1
proves demand.

**Non-goals:** per-user targeted pushes (would create a device-token
registry = first real PII + a database — conflicts with the no-PII,
stateless design).

---

## 4 · Smaller items

- **`X-Device-Id` policy** (audit F3): kept dormant. If ever used for
  per-device rate limiting or support debugging, never log/persist the
  raw value — salted hash only.
- **Dual origin token** (`CF_ORIGIN_TOKEN_PRIMARY`/`_SECONDARY`):
  removes the ~30 s rotation mismatch window. Low priority; rotate
  during low traffic until then.
- **Delete dead `stopFinder()` code in the app** during key-endgame
  cleanup — see §1.
- **Metrics visibility**: Dropwizard metrics currently land in logs
  every 10 s. Cheap first step: a `grep`-able weekly review per
  FIRST_DEPLOY.md §7. A dashboard is a nice-to-have, not a need.
- **Quarterly hygiene**: review Dependabot/CodeQL findings, confirm
  billing ≈ flat, re-run the audit's pre-deploy gate after any infra
  change.
