# START — local app ↔ BFF end-to-end test

> **Note (2026-06-13):** written pre-merge ("zero PRs merged" framing
> is outdated — everything is on `main` now). The session entry point
> is [PLAN.md](PLAN.md). **This checklist becomes useful again at
> phase A1/F** — wiring the real KRAIL app build to a local BFF before
> the cohort rollout. The steps below still apply then.

> The goal: prove the BFF works end-to-end with the **KRAIL app on your
> machine**, with nothing deployed. Once a real request from a real
> Android/iOS build hits your local BFF and renders a trip in the app
> UI, you have de-risked the whole stack.

Tick boxes as you go. Don't skip steps — each one rules out a different
class of "why doesn't it work." If a step fails, the line below it tells
you what's wrong.

---

## 0 · Prereqs (one-time)

- [ ] **NSW Open Data API key** — issued just for the BFF (not the one
      embedded in the app). Get one at
      https://opendata.transport.nsw.gov.au/. You want a fresh key so
      you can rotate the in-app key separately later.
- [ ] **JDK 17** installed (`java -version`).
- [ ] **`local.properties` exists** in the BFF repo root. If not:
      ```
      cd /Users/ksharma/code/apps/KRAIL-BFF
      printf 'nsw.apiKey=PUT_YOUR_KEY_HERE\nbff.cors.origins=http://localhost:8000\nbff.devPassthrough=true\n' > local.properties
      ```
      The file is gitignored — never gets committed.

---

## 1 · BFF runs cleanly on your machine

- [ ] Run `./scripts/dev.sh up` from the BFF repo root.
      It starts the BFF on `:8080` + the dashboard static server on `:8000`,
      polls until healthy, prints the URL.
- [ ] Open <http://localhost:8000/api-tester.html>. Should load the
      three-pane dashboard with grouped sidebar.
- [ ] In the dashboard, click **Server health → Health probe → Send**.
      Expect `200 OK`, body `{"status":"UP"}`.
      *Failure → BFF didn't start. Run `./scripts/dev.sh logs`.*
- [ ] Click **Trip search → Trip planner — JSON → Send** with the
      pre-filled Town Hall ↔ Central params.
      Expect `200 OK`, a JSON body with a `journeys` array.
      *Failure → NSW key wrong or NSW upstream down. The Highlights
      panel at the top of the response will show the error code.*
- [ ] Click **Compare with NSW** on the same trip endpoint.
      Both columns should be `200 OK` with similar payloads. The summary
      shows the BFF's body-size win.
      *Failure on the NSW column → set `bff.devPassthrough=true` in
      `local.properties` and restart (`./scripts/dev.sh restart`).*

✅ At this point the **BFF works in isolation**. Now wire the app to it.

---

## 2 · Make the BFF reachable from your phone / emulator

The BFF binds to `0.0.0.0:8080` already (Ktor default), so you don't
need to change anything server-side.

- [ ] Find your machine's LAN IP (only needed for physical devices on
      Wi-Fi):
      ```
      ipconfig getifaddr en0    # Wi-Fi
      ipconfig getifaddr en1    # ethernet, if applicable
      ```
      Note that IP — call it `<HOST_IP>` for the rest of this doc.

| Where you're running the app | URL to reach the BFF |
|---|---|
| Android emulator | `http://10.0.2.2:8080` |
| iOS Simulator | `http://localhost:8080` |
| Physical Android (same Wi-Fi) | `http://<HOST_IP>:8080` |
| Physical iOS (same Wi-Fi) | `http://<HOST_IP>:8080` |

- [ ] From your phone's browser (if testing on a physical device), open
      `http://<HOST_IP>:8080/health`. Expect `{"status":"UP"}`.
      *Failure → macOS firewall is blocking port 8080. System Settings →
      Network → Firewall → allow Java / IntelliJ to accept incoming.*

---

## 3 · Point the KRAIL app at your local BFF (one endpoint)

**Strategy:** the BFF mirrors NSW's URL shape for the trip planner —
same path (`/v1/tp/trip`), same query params, same response JSON. So
the cheapest local test is to **swap the base URL constant** for one
endpoint and confirm the app still works. Zero refactor, zero proto,
zero feature flags. Either it works (BFF → NSW round-trip is fine) or
it doesn't.

Open `/Users/ksharma/code/apps/KRAIL/feature/trip-planner/network/src/commonMain/kotlin/xyz/ksharma/krail/trip/planner/network/api/service/RealTripPlanningService.kt`.

- [ ] Replace this line in `trip()`:
      ```kotlin
      httpClient.get("$NSW_TRANSPORT_BASE_URL/v1/tp/trip") {
      ```
      With:
      ```kotlin
      httpClient.get("http://10.0.2.2:8080/v1/tp/trip") {   // ← LOCAL BFF (Android emulator)
      ```
      (For iOS sim use `http://localhost:8080`. For a physical device
      use `http://<HOST_IP>:8080`.)

- [ ] **Allow cleartext for the local BFF.** Local HTTP requires
      explicit opt-in:

  - Android: `androidApp/src/main/res/xml/network_security_config.xml`
    add `<domain includeSubdomains="false">10.0.2.2</domain>` (or
    `<HOST_IP>`) inside `<domain-config cleartextTrafficPermitted="true">`.
    Reference it from `AndroidManifest.xml` `application` tag if not already.
  - iOS: `iosApp/iosApp/Info.plist` add the standard
    `NSAppTransportSecurity → NSExceptionDomains` exception for
    `localhost` (or your `<HOST_IP>`).

- [ ] Build + run the app.
- [ ] Search a trip in the app (e.g. Town Hall → Central).
- [ ] In the BFF terminal (`./scripts/dev.sh logs`) you should see a
      log line for `GET /v1/tp/trip`.
      *No log line → app didn't reach the BFF. Walk back through 2.*
- [ ] The trip results screen should render normally with real journeys.
      *Renders with errors → check BFF logs for the upstream error.*

✅ **The KRAIL app is now talking to the local BFF.** This is the
de-risk moment. If this works, the deploy + integration plan in
`STATUS.md` is just plumbing, not unknowns.

---

## 4 · Repeat the swap for one more endpoint (optional but valuable)

The same trick works for stop_finder, departures, parking, etc. — the
BFF mirrors NSW's path shape on every pass-through endpoint. Pick one
that matters to you (departures is good — you'll see it on Home).

- [ ] Find the existing NSW call in
      `feature/<x>/network/.../Real<X>Service.kt`.
- [ ] Swap `NSW_TRANSPORT_BASE_URL` for the local BFF URL on that one
      endpoint.
- [ ] Run, verify the relevant screen.

When two unrelated endpoints work end-to-end through the local BFF, you
have very high confidence the production deploy will work too.

---

## 5 · Revert the local-only changes (before committing anything in KRAIL)

- [ ] `git diff` in the KRAIL repo — should show the URL swaps and
      ATS / cleartext exceptions you made.
- [ ] `git checkout -- <files>` to revert. **Don't commit these to
      KRAIL.** When you do the real integration (per
      `BFF_ADOPTION_GUIDE.md`), the URL becomes a `BuildConfig` value
      driven from `local.properties`, not a hardcoded constant — and
      it sits behind a Firebase RC flag, not a hard swap.

---

## 6 · Decide: are you ready to merge the stack?

If 1, 3, and 4 all ticked green, **yes**. Move on to the steps in
[`STATUS.md`](STATUS.md):

1. Merge PRs #46 → #61 in order.
2. Deploy to DigitalOcean (`DEPLOYMENT.md`).
3. Real KRAIL integration: extract `krail-api-proto` repo, then per-endpoint
   migration with feature flags ([`BFF_ADOPTION_GUIDE.md`](docs/reference/BFF_ADOPTION_GUIDE.md)).

If any step failed, **don't merge yet** — fix it locally first, push
the fix to whichever PR in the stack is responsible, and re-run 1–3.

---

## Quick reference

| Thing | Command / location |
|---|---|
| Start BFF + dashboard | `./scripts/dev.sh up` |
| Stop both | `./scripts/dev.sh down` |
| Restart | `./scripts/dev.sh restart` |
| Tail BFF log | `./scripts/dev.sh logs` |
| Dashboard | <http://localhost:8000/api-tester.html> |
| Health probe | <http://localhost:8080/health> |
| BFF source of NSW key | `local.properties` → `nsw.apiKey` |
| Long-form deploy guide | [`docs/reference/DEPLOYMENT.md`](docs/reference/DEPLOYMENT.md) |
| Long-form integration guide | [`docs/reference/BFF_ADOPTION_GUIDE.md`](docs/reference/BFF_ADOPTION_GUIDE.md) |
| Project status overview | [`STATUS.md`](STATUS.md) |
| Test-everything runbook | [`docs/reference/TESTING_PLAYBOOK.md`](docs/reference/TESTING_PLAYBOOK.md) |
| KRAIL app trip-planner service | `/Users/ksharma/code/apps/KRAIL/feature/trip-planner/network/src/commonMain/kotlin/xyz/ksharma/krail/trip/planner/network/api/service/RealTripPlanningService.kt` |
