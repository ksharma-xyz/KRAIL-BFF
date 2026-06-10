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
