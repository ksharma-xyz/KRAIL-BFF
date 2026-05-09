# Scripts

This folder contains utility scripts for development and testing.

## Available Scripts

### dev.sh — one-command local environment

The fastest way to spin up a working local setup. Single script that starts the BFF, serves the api-tester on a separate origin (so CORS works), polls until everything's healthy, and prints the URL.

**Usage:**
```bash
./scripts/dev.sh up        # start BFF + api-tester, wait for healthy, print URL
./scripts/dev.sh down      # stop both
./scripts/dev.sh status    # see what's running
./scripts/dev.sh logs      # tail BFF log (Ctrl-C to stop tailing; doesn't kill server)
./scripts/dev.sh restart   # down + up
```

**What `up` does:**

1. Verifies `local.properties` has `nsw.apiKey` set (fails fast with instructions if missing).
2. Auto-adds `bff.cors.origins=http://localhost:8000` if not present (so the api-tester served on `:8000` can talk to the BFF on `:8080`).
3. Kills anything already on ports 8080 / 8000.
4. Starts BFF (`./gradlew :server:run`) in the background. Logs go to `build/dev/bff.log`.
5. Polls `/health` until it responds (up to 3 minutes — first run is slow because gradle compiles).
6. Starts a Python static server for `docs/tools/` on port 8000.
7. Prints **"Open http://localhost:8000/api-tester.html"** with config hints.

**Idempotent.** Running `up` when already up just re-checks and reprints the URL. Safe to spam.

**Stop with `down`** — kills both PIDs cleanly. If something else hijacks the ports later, `down` belts-and-braces by killing whatever's listening.

### test-trip-planning.sh

Automated test script for the Trip Planning API.

**Usage:**
```bash
./test-trip-planning.sh
```

**What it does:**
- Checks if port 8080 is in use and cleans it up
- Starts the server with API key from `local.properties`
- Waits for server to be ready (health check)
- Runs multiple test scenarios:
  - Basic trip planning (Central to Circular Quay)
  - Trip with specific time (Parramatta to Central @ 9 AM)
  - Trip excluding trains (buses/ferries only)
  - Trip with arrival time
- Shows formatted results using jq
- Stops the server when you press Enter

**Requirements:**
- `jq` installed: `brew install jq`
- Valid API key in `local.properties`

**Logs:**
Server logs are written to `/tmp/krail-server.log`

**Example output:**
```
🚀 Starting KRAIL-BFF Server...
Server PID: 12345
✅ Server is ready!

✅ Testing Health Endpoint...
{}

🚆 Testing Trip Planning: Central Station to Circular Quay
{
  "origin": "Central Station",
  "destination": "Circular Quay",
  "transportation": "Eastern Suburbs & Illawarra Line",
  "duration": 600
}
✓ Success
```

## Adding New Scripts

When adding new scripts to this folder:

1. Make them executable: `chmod +x scripts/your-script.sh`
2. Add a description to this README
3. Follow the existing naming convention
4. Include error handling and helpful output

