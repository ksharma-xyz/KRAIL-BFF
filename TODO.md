# TODO — everything pending, in order

> Single list of remaining work across BFF + app + website. Tick boxes
> as you go; each item links to the doc with the actual steps.
> Companion to [STATUS.md](STATUS.md) (state) — this is the queue.

## Now (before/at first deploy)

- [ ] **GitHub ruleset gap** (2 min): repo → Settings → Rules → `main`
      → add **Require status checks to pass** → select the `pr.yml`
      build job. Until then red CI doesn't block merging.
- [ ] **Mint BFF-only NSW API key** at
      <https://opendata.transport.nsw.gov.au/> → password manager.
      Blocking ([audit F1](docs/reference/SECURITY_AUDIT_2026-06.md)).
- [ ] **Move `krail.app` DNS to Cloudflare** — registrar nameserver
      switch; GitHub Pages site keeps working unchanged
      ([FIRST_DEPLOY §2](docs/guides/FIRST_DEPLOY.md)). Hosting does
      NOT move — only who answers DNS.
- [ ] **Review + merge the `docs/predeploy-audit-and-roadmap` branch**
      (this one) so the deploy docs are on `main`.
- [ ] **Deploy** — work through
      [FIRST_DEPLOY.md](docs/guides/FIRST_DEPLOY.md) top to bottom
      (DO app, secrets, Cloudflare, smoke suite).
- [ ] **Run the pre-deploy security gate**
      ([audit §5](docs/reference/SECURITY_AUDIT_2026-06.md)) — every
      box ticked before the app points at it.

## Next (after deploy is stable)

- [ ] **App rollout** per
      [BFF_ADOPTION_GUIDE.md](docs/reference/BFF_ADOPTION_GUIDE.md):
      flip `BFF_ROLLOUT_ARMED`, add RC flags, cohorts
      0 → 10 → 50 → 100%.
- [ ] **Response caching in the BFF** — the scale unlock for the NSW
      quota ([ROADMAP §2a](docs/reference/ROADMAP.md)): short-TTL
      caches so one upstream call serves many users. Do before user
      growth, not after.
- [ ] **Dataset distribution APIs** — stops + bus routes via manifest;
      retires the krail-config → app-PR pipeline
      ([ROADMAP §2](docs/reference/ROADMAP.md)).
- [ ] **App-start bootstrap endpoint** — `GET /api/v1/app/bootstrap`:
      one edge-cached call returning dataset versions, festivals,
      min/latest app version; content edited in krail-config; RC keeps
      kill switches ([ROADMAP §2b](docs/reference/ROADMAP.md)).

## Live trip tracking (the unshipped headline feature)

> Full design: [TRACKING_DESIGN.md](docs/reference/TRACKING_DESIGN.md).
> Order matters; O-items are research, T-items are BFF code, A-items are app code.

- [x] **O1–O4 research spikes** (2026-06-12, from live feeds): per-car
      PassLoad ext 1007 CONFIRMED in public feeds (metro fully
      labelled, sydneytrains partial); TfnswVehicleDescriptor ext 1007
      confirmed (vehicle_model: full name on metro/bus, set letter on
      trains); buses = ONE consolidated v1 feed (no fragmentation);
      trip ids byte-identical between Trip Planner and GTFS-R (no
      normalization); local key already has realtime products.
      Remaining: O5 `/.well-known/` on GitHub Pages (A2-time).
- [x] **T0** — track.proto in krail-api-proto (pushed); GTFS-R +
      TfNSW extension protos vendored in BFF; real-feed fixtures +
      GtfsRealtimeDecodeTest pin the feed assumptions.
- [x] **T1** — `POST /api/v1/track/snapshot` live-verified against
      real NSW: FeedCache (15/30 s TTL, single-flight, stale-grace),
      exact trip_id match, status enum incl. EXPIRED, fleet
      (live-descriptor first), tiered occupancy, stop timeline +
      delay. Deferred into T1.5: geometry, stop names, expected
      occupancy. Sydneytrains quirk: no bearing in feed.
- [x] **T1-dash** — docs/tools/track-tester.html: trip picker, poll
      loop obeying suggested_poll_seconds, Leaflet map with staleness
      greying, status pills, car strip, timeline, share-link
      simulator.
- [ ] **SOAK (you, now)** — `./scripts/dev.sh up` →
      http://localhost:8000/track-tester.html → track real trains for
      a few days. Satisfaction gates app work.
- [ ] **T1.5** — tracking-grade stop directory + shapes join. The
      bundled search dataset resolves parents + bus stops only;
      GTFS-R reports PLATFORM ids for trains, and platform→parent
      mapping lives in GTFS `stops.txt` (`parent_station`). Build a
      platform-level directory (id, name, parent, lat/lon) + shapes
      polylines in the existing GitHub Actions dataset job; BFF then
      names every stop and ships first-poll geometry (design §7a).
- [x] **T1 handover doc** —
      [docs/handover/TRACKING_INTEGRATION.md](docs/handover/TRACKING_INTEGRATION.md)
      (includes the app-side `direction_id` proto bug found while
      vendoring — fix in app before metro tracking).
- [ ] **T2** — per-carriage occupancy + live fleet descriptor (after O1).
- [ ] **T3** — buses (after O2) + carriage-layout dataset via GitHub
      Actions (`reaches_platform`).
- [ ] **A1 (app)** — TrackTripViewModel/TripPoller → BFF endpoint
      behind `bff_use_for_track`; later delete client-side GTFS-R code.
- [ ] **A2 (app + website)** — share-link v2 payload (realtimeTripId,
      serviceDate, stop ids/names, version field) + migrate deep links
      to `krail.app/trip` (assetlinks.json + apple-app-site-association
      on KRAIL-WEBSITE).

## Later

- [ ] **NSW key endgame** — `MIN_APP_VERSION` force-update pass, then
      delete the in-app key at the NSW portal
      ([ROADMAP §1](docs/reference/ROADMAP.md)).
- [ ] **Delete dead `stopFinder()`** code in the app repo (verified
      never called) during endgame cleanup.
- [ ] **Push notifications, Phase 1** — FCM SDK in app, topic
      broadcasts from Firebase console
      ([ROADMAP §3](docs/reference/ROADMAP.md)).
- [ ] **Optional: website hosting → Cloudflare Pages.** Not required —
      GitHub Pages behind Cloudflare DNS is fine indefinitely. If ever
      wanted: the site is plain static files, so it's
      Pages-project-connect-repo, build command: none, output dir: `/`,
      add `krail.app` custom domain, delete the GitHub Pages deployment.
      Gains: unlimited free bandwidth, instant cache purge. ~30 min.
- [ ] **Dual origin token** rotation support
      ([ROADMAP §4](docs/reference/ROADMAP.md)).

## Recurring (calendar)

- [ ] Weekly (first month): DO billing ≈ US$5 flat; logs glance;
      NSW daily-budget counter.
- [ ] Quarterly: Dependabot/CodeQL findings; re-run audit gate after
      any infra change.
