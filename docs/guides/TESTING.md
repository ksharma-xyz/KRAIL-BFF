---
layout: default
title: Testing
parent: Guides
nav_order: 3
---

# Testing Guide
{: .no_toc }

Quick and comprehensive testing guide for KRAIL BFF.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Quick Start

### 1. Start the Server

```bash
./gradlew :server:run
```

Wait for: `Application started in X seconds`

### 2. Quick Test

```bash
# Test trip planning (JSON)
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101120"

# Test trip planning (Protobuf)
curl "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120" -o /tmp/trip.bin

# Health check
curl "http://localhost:8080/health"
```

### 3. Compare Sizes

```bash
./scripts/check-size.sh
```

Expected: Protobuf is ~83% smaller than JSON.

---

## Automated Testing

### Run Unit Tests

```bash
./gradlew test
```

### Run Specific Test Class

```bash
./gradlew test --tests "TripPlanningTest"
```

### Run with Coverage

```bash
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

---

## Manual API Testing

### Trip Planning Endpoints

**JSON Response:**
```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101120" | jq .
```

**Protobuf Response:**
```bash
curl "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120" \
  -o journey.bin

# Check size
ls -lh journey.bin
```

**Query Parameters:**
- `origin` (required): Origin stop ID
- `destination` (required): Destination stop ID
- `depArr` (optional): "dep" or "arr" (default: "dep")
- `date` (optional): YYYYMMDD format
- `time` (optional): HHmm format
- `excludedModes` (optional): Comma-separated (e.g., "1,5,9")

### Health Check

```bash
curl "http://localhost:8080/health"
```

Expected:
```json
{
  "status": "UP"
}
```

---

## Test Scripts

### Compare JSON vs Protobuf

```bash
./scripts/check-size.sh
```

Shows size comparison and savings.

### Test All Endpoints

```bash
./scripts/test-proto-endpoint.sh
```

Tests:
- Missing parameters (400 errors)
- Valid requests (200 success)
- Protobuf vs JSON comparison

### Trip Planning Script

```bash
./scripts/test-trip-planning.sh
```

Tests the trip planning API with real data.

---

## Error Testing

### Missing Parameter

```bash
curl -i "http://localhost:8080/api/v1/trip/plan-proto?destination=10101120"
```

Expected: `400 Bad Request` with JSON error.

### Invalid Stop ID

```bash
curl -i "http://localhost:8080/api/v1/trip/plan?origin=invalid&destination=10101120"
```

Expected: Error from NSW API.

---

## Performance Testing

### Response Time

```bash
time curl -s "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120" > /dev/null
```

### Load Testing (with `ab`)

```bash
ab -n 100 -c 10 "http://localhost:8080/health"
```

---

## Debugging Tests

### View Logs

```bash
# Run with debug logging
./gradlew :server:run --debug
```

### Check Server Logs

Server logs show detailed journey information for protobuf responses.

Look for:
```
🚊 PROTOBUF JOURNEY LIST
Number of journeys: X
```

---

## Common Test Stop IDs

| Location | Stop ID |
|----------|---------|
| Central Station | 10101100 |
| Town Hall Station | 10101120 |
| Circular Quay Station | 10101122 |
| Bondi Junction Station | 10101339 |

---

## Troubleshooting

**Server won't start:**
- Check `local.properties` has valid NSW API key
- Ensure port 8080 is not in use: `lsof -i :8080`

**Tests fail:**
- Check internet connection
- Verify NSW API key is valid
- Check NSW OpenData API status

**Empty responses:**
- Verify stop IDs are correct
- Check server logs for errors
- Test NSW API directly

---

## CI/CD Testing

For GitHub Actions or similar:

```yaml
- name: Run tests
  run: ./gradlew test

- name: Upload test results
  uses: actions/upload-artifact@v3
  with:
    name: test-results
    path: build/reports/tests/
```

---

## Next Steps

- [Local Development Setup](LOCAL_DEVELOPMENT)
- [Protobuf Integration](PROTOBUF)
- [Debugging Guide](DEBUGGING)
