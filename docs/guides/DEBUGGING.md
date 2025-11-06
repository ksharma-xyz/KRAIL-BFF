# Debugging Guide

## Logging

### Enable Debug Logging

```bash
# Full debug output
LOG_LEVEL=DEBUG ./gradlew :server:run

# Production (default)
./gradlew :server:run
```

### Filter Logs

```bash
# Only errors
./gradlew :server:run 2>&1 | grep '"level":"ERROR"'

# NSW API calls
./gradlew :server:run 2>&1 | grep 'NswClient' | jq

# Pretty print all JSON logs
./gradlew :server:run 2>&1 | grep '^{' | jq
```

### Correlation ID Tracking

Every request gets a unique ID in the `X-Request-Id` header. Use it to trace a request through logs.

```bash
# Get correlation ID from response
curl -i http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020 | grep -i x-request-id

# Search logs for that ID
./gradlew :server:run 2>&1 | grep "abc-123-correlation-id"
```

## Common Issues

### 1. API Authentication Errors

**Symptom:** `401 Unauthorized` or authentication failures

**Fix:**
```bash
# Check API key is set
cat local.properties | grep nsw.apiKey

# Restart server to reload key
# Ctrl+C then ./gradlew :server:run
```

### 2. Circuit Breaker Open

**Symptom:** Log message `Circuit breaker opened - failure threshold reached`

**What it means:** NSW API failed 3 times, requests blocked for 60 seconds

**Fix:** Wait for auto-reset or check NSW API status

### 3. No Journeys Found

**Symptom:** Empty `journeys` array in response

**Check:**
- Stop IDs are valid
- Route exists between stops
- Date/time is in the future
- Not excluding all transport modes

**Test with known working route:**
```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020" | jq
```

### 4. Port Already in Use

**Symptom:** `Address already in use` error

**Fix:**
```bash
# Kill process on port 8080
lsof -ti:8080 | xargs kill -9

# Or use different port
./gradlew :server:run -PrunPort=9090
```

### 5. Slow Response Times

**Check metrics:**
```bash
curl http://localhost:8080/metrics | jq '.timers."nsw.trip.duration"'
```

**Enable DEBUG logging to see timing:**
```bash
LOG_LEVEL=DEBUG ./gradlew :server:run
```

## Debugging Tools

### 1. IntelliJ IDEA

- Open `Application.kt`
- Click debug icon
- Set breakpoints in `NswClient.getTrip()`
- Inspect variables

### 2. cURL Verbose Mode

```bash
# See full HTTP exchange
curl -v "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020"

# Include response headers
curl -i "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020"
```

### 3. Server Logs

When using the test script, logs go to `/tmp/krail-server.log`:

```bash
# Watch logs in real-time
tail -f /tmp/krail-server.log | jq

# Search for errors
grep ERROR /tmp/krail-server.log | jq
```

## Quick Debugging Checklist

When something goes wrong:

- [ ] Check `local.properties` has valid API key
- [ ] Look at recent error logs
- [ ] Test with known working parameters (Central to Circular Quay)
- [ ] Verify server is running: `lsof -ti:8080`
- [ ] Check NSW API status at https://opendata.transport.nsw.gov.au/
- [ ] Enable DEBUG logging for details
- [ ] Check circuit breaker status in logs

## Performance Analysis

```bash
# View metrics
curl http://localhost:8080/metrics | jq

# Health check
curl http://localhost:8080/health

# Check response time
time curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020"
```

## Getting Help

1. Enable DEBUG logging first
2. Check the error message in logs
3. Review [TESTING_QUICK_START.md](TESTING_QUICK_START.md) for common issues
4. Check [CONFIGURATION.md](CONFIGURATION.md) for setup problems
