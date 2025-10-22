# Quick Testing Guide

## Run the Server

```bash
# Option 1: Auto-load API key from local.properties
./gradlew :server:run

# Option 2: Set API key manually
export NSW_API_KEY="your-key"
./gradlew :server:run
```

Wait for: `Application started in X seconds`

## Test API Calls

```bash
# Basic trip
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020" | jq

# With time (9:00 AM)
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020&time=0900" | jq

# Exclude trains
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020&excludedModes=1" | jq
```

## Common Stop IDs

| Location | ID |
|----------|-----|
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

## Transport Mode IDs

Use with `excludedModes` parameter:
- `1` = Train
- `4` = Light Rail
- `5` = Bus
- `7` = Coach
- `9` = Ferry

## Automated Testing

```bash
./scripts/test-trip-planning.sh
```

## Troubleshooting

**"Connection refused"** → Server not started  
**"No journeys found"** → Check stop IDs or route exists  
**"401 Unauthorized"** → Check API key in local.properties

**See full docs:** [TRIP_PLANNING_API.md](TRIP_PLANNING_API.md)
