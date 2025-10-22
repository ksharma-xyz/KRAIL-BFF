# Scripts

This folder contains utility scripts for development and testing.

## Available Scripts

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

