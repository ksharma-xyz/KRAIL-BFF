# Testing Your Trip Planning Service

Your API key is set up in `local.properties` and ready to use! Here are the ways to test your service:

## Method 1: Quick Manual Test (Recommended)

### Step 1: Start the Server

```bash
cd /Users/ksharma/code/apps/KRAIL-BFF
./gradlew :server:run
```

Wait for the message: `Application started in X seconds`

### Step 2: Open a New Terminal and Test

```bash
# Test 1: Basic trip (Central to Circular Quay)
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020" | jq

# Test 2: With specific time
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020&time=0900" | jq

# Test 3: Exclude trains
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020&excludedModes=1" | jq

# Test 4: Parramatta to Central
curl "http://localhost:8080/api/v1/trip/plan?origin=215020&destination=200060" | jq
```

## Method 2: Automated Test Script

I've created a test script for you:

```bash
cd /Users/ksharma/code/apps/KRAIL-BFF
./scripts/test-trip-planning.sh
```

This will:
- Start the server automatically
- Wait for it to be ready
- Run multiple test scenarios
- Show you the results

## Method 3: With Debug Logging

To see detailed logs including API requests/responses:

```bash
LOG_LEVEL=DEBUG ./gradlew :server:run
```

Then in another terminal:
```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020" | jq
```

## What to Expect

### Successful Response Example:

```json
{
  "version": "10.2.1.42",
  "journeys": [
    {
      "legs": [
        {
          "origin": {
            "name": "Central Station",
            "departureTimePlanned": "2025-10-22T09:15:00Z",
            "departureTimeEstimated": "2025-10-22T09:16:00Z"
          },
          "destination": {
            "name": "Circular Quay",
            "arrivalTimePlanned": "2025-10-22T09:25:00Z"
          },
          "transportation": {
            "number": "T4",
            "name": "Eastern Suburbs & Illawarra Line",
            "product": {
              "class": 1,
              "name": "Train"
            }
          },
          "duration": 600,
          "distance": 3500
        }
      ]
    }
  ]
}
```

## Common Stop IDs for Testing

| Location | Stop ID |
|----------|---------|
| Central Station | 200060 |
| Circular Quay | 200020 |
| Town Hall | 200070 |
| Wynyard | 200080 |
| Kings Cross | 201110 |
| Parramatta | 215020 |
| Chatswood | 206710 |
| Bondi Junction | 202210 |
| North Sydney | 206010 |
| Airport International | 202030 |
| Airport Domestic | 202020 |

## Troubleshooting

### "Connection refused"
- Server not started yet, wait a bit longer
- Check if server is running: `lsof -ti:8080`

### "No journeys found"
- Check stop IDs are correct
- Verify date/time is in the future
- Try a known working route (Central to Circular Quay)

### "401 Unauthorized"
- API key issue - check `local.properties` has the correct key
- Restart the server to reload the key

### See All Logs
```bash
# In the server terminal, you'll see JSON logs
# Look for these messages:
# - "Requesting trip from NSW API" (DEBUG level)
# - "Trip API response received" (DEBUG level)
# - Any errors will show in ERROR level
```

## Next Steps

Once testing is successful:
- Check [docs/TRIP_PLANNING_API.md](docs/TRIP_PLANNING_API.md) for complete API reference
- See [docs/DEBUGGING.md](docs/DEBUGGING.md) for debugging tips
- Review [docs/LOCAL_DEVELOPMENT.md](docs/LOCAL_DEVELOPMENT.md) for more development info
