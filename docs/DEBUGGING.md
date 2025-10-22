# Debugging Guide

Advanced debugging techniques for KRAIL-BFF development and troubleshooting.

## Table of Contents

- [Logging](#logging)
- [Common Issues](#common-issues)
- [Debugging Tools](#debugging-tools)
- [Performance Analysis](#performance-analysis)
- [Network Debugging](#network-debugging)

## Logging

### Log Levels

The application uses SLF4J with Logback for structured JSON logging.

| Level | Use Case | Example |
|-------|----------|---------|
| `DEBUG` | Development, detailed tracing | Request/response details, parameter values |
| `INFO` | Production, normal operations | Server started, API calls completed |
| `WARN` | Recoverable issues | Circuit breaker opened, retries |
| `ERROR` | Errors requiring attention | API failures, exceptions |

### Enable Debug Logging

**For entire application:**
```bash
LOG_LEVEL=DEBUG ./gradlew :server:run
```

**For specific logger:**

Edit `server/src/main/resources/logback.xml`:

```xml
<!-- Debug only NSW client -->
<logger name="app.krail.bff.client.nsw" level="DEBUG"/>

<!-- Debug only routes -->
<logger name="app.krail.bff.routes" level="DEBUG"/>

<!-- Debug Ktor HTTP client -->
<logger name="io.ktor.client" level="DEBUG"/>
```

### Log Format

All logs are in JSON format for easy parsing:

```json
{
  "@timestamp": "2025-10-22T15:41:05.955586+11:00",
  "@version": "1",
  "message": "Requesting trip from NSW API",
  "logger_name": "app.krail.bff.client.nsw.NswClientImpl",
  "thread_name": "DefaultDispatcher-worker-1",
  "level": "DEBUG",
  "level_value": 10000,
  "correlationId": "abc-123-def-456"
}
```

### Filtering Logs

**Using grep:**
```bash
# Only errors
./gradlew :server:run 2>&1 | grep '"level":"ERROR"'

# Only NSW client logs
./gradlew :server:run 2>&1 | grep 'NswClientImpl'

# Specific correlation ID
./gradlew :server:run 2>&1 | grep 'abc-123-def-456'
```

**Using jq:**
```bash
# Pretty print errors
./gradlew :server:run 2>&1 | grep '^{' | jq 'select(.level == "ERROR")'

# Get all messages from NSW client
./gradlew :server:run 2>&1 | grep '^{' | jq 'select(.logger_name | contains("NswClient")) | .message'

# Show timing info
./gradlew :server:run 2>&1 | grep '^{' | jq 'select(.message | contains("duration"))'
```

### Log Correlation

Every request has a unique correlation ID:

```bash
# Track a single request through all logs
CORRELATION_ID=$(curl -s -D - http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328 | grep -i x-request-id | cut -d' ' -f2)
echo "Correlation ID: $CORRELATION_ID"

# Find all logs for this request
./gradlew :server:run 2>&1 | grep "$CORRELATION_ID"
```

## Common Issues

### 1. NSW API Authentication Errors

**Symptom:**
```json
{
  "level": "ERROR",
  "message": "Failed to fetch trip from NSW API",
  "stack_trace": "401 Unauthorized"
}
```

**Solution:**
```bash
# Check if API key is set
echo $NSW_API_KEY

# Verify it's not empty
if [ -z "$NSW_API_KEY" ]; then
  echo "API key not set!"
fi

# Set the key
export NSW_API_KEY="your-actual-key"
```

**Debug the API key header:**
```bash
# Enable HTTP client debugging
LOG_LEVEL=DEBUG ./gradlew :server:run 2>&1 | grep -i authorization
```

### 2. Circuit Breaker Open

**Symptom:**
```json
{
  "level": "WARN",
  "message": "Circuit breaker opened - failure threshold reached: 3"
}
```

**What it means:**
- The NSW API failed 3 times consecutively
- Requests are now being rejected to prevent further failures
- It will auto-reset after 60 seconds

**Debug:**
```bash
# Check health endpoint
curl http://localhost:8080/health

# Monitor circuit breaker resets
./gradlew :server:run 2>&1 | grep -i "circuit breaker"
```

**Temporary fix:**
```bash
# Restart the server to reset the circuit breaker
# Or wait 60 seconds for automatic reset
```

### 3. No Journeys Found

**Symptom:**
```json
{
  "journeys": [],
  "systemMessages": [...]
}
```

**Checklist:**
- ✅ Are both stop IDs valid?
- ✅ Is the route possible between those stops?
- ✅ Are you excluding all transport modes?
- ✅ Is the date/time valid and in the future?

**Debug:**
```bash
# Test with known working stops
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328"

# Check excluded modes
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328&excludedModes="
```

### 4. Slow Response Times

**Symptom:**
Requests taking longer than expected.

**Debug:**
```bash
# Measure response time
time curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328"

# Check metrics
curl http://localhost:8080/metrics | jq '.timers."nsw.trip.duration"'
```

**Check logs for timing:**
```bash
./gradlew :server:run 2>&1 | grep '"nsw.trip.duration"'
```

## Debugging Tools

### 1. IntelliJ IDEA Debugger

**Setup:**
1. Open `server/src/main/kotlin/app/krail/bff/Application.kt`
2. Click the green play button in the gutter
3. Select "Debug 'Application'"

**Set breakpoints:**
- In `NswClient.getTrip()` to inspect API calls
- In route handlers to inspect incoming requests
- In error handlers to catch exceptions

**Evaluate expressions:**
- Right-click → "Evaluate Expression" (Alt+F8)
- Inspect variables in the Variables panel

### 2. HTTP Debugging with Charles Proxy

**Setup:**
1. Install [Charles Proxy](https://www.charlesproxy.com/)
2. Configure JVM to use proxy:
   ```bash
   export JAVA_OPTS="-Dhttp.proxyHost=localhost -Dhttp.proxyPort=8888 -Dhttps.proxyHost=localhost -Dhttps.proxyPort=8888"
   ./gradlew :server:run
   ```

**View:**
- All outgoing HTTP requests to NSW API
- Request/response headers and bodies
- Timing information

### 3. cURL Verbose Mode

```bash
# See full HTTP exchange
curl -v "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328"

# Include response headers
curl -i "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328"

# Measure timing
curl -w "@curl-format.txt" -o /dev/null -s "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328"
```

**Create `curl-format.txt`:**
```
time_namelookup:  %{time_namelookup}s\n
time_connect:     %{time_connect}s\n
time_appconnect:  %{time_appconnect}s\n
time_pretransfer: %{time_pretransfer}s\n
time_redirect:    %{time_redirect}s\n
time_starttransfer: %{time_starttransfer}s\n
time_total:       %{time_total}s\n
```

### 4. JProfiler / VisualVM

**For performance profiling:**

```bash
# Enable JMX
export JAVA_OPTS="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9010 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
./gradlew :server:run
```

Connect VisualVM to `localhost:9010`

## Performance Analysis

### Metrics Endpoint

```bash
# View all metrics
curl http://localhost:8080/metrics | jq

# NSW API call metrics
curl http://localhost:8080/metrics | jq '.timers."nsw.trip.duration"'

# Success/error counters
curl http://localhost:8080/metrics | jq '.counters | with_entries(select(.key | contains("nsw")))'
```

### Load Testing with wrk

```bash
# Install wrk
brew install wrk

# Run load test
wrk -t4 -c100 -d30s "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328"
```

### Memory Analysis

```bash
# Enable GC logging
export JAVA_OPTS="-Xlog:gc*:file=gc.log"
./gradlew :server:run

# Monitor with jstat
jstat -gc <pid> 1000
```

## Network Debugging

### Wireshark

**Capture HTTP traffic:**
1. Start Wireshark
2. Filter: `tcp.port == 8080 || tcp.port == 443`
3. Make API request
4. Analyze packets

### tcpdump

```bash
# Capture traffic on port 8080
sudo tcpdump -i lo0 -A -s 0 'tcp port 8080'

# Save to file
sudo tcpdump -i lo0 -w capture.pcap 'tcp port 8080'
```

### mitmproxy

**For HTTPS inspection:**
```bash
# Install
brew install mitmproxy

# Run
mitmproxy -p 8888

# Configure app to use proxy
export JAVA_OPTS="-Dhttp.proxyHost=localhost -Dhttp.proxyPort=8888"
```

## Debugging Checklist

When something goes wrong:

- [ ] Check environment variables are set
- [ ] Verify API key is valid
- [ ] Look at recent error logs
- [ ] Check circuit breaker status
- [ ] Verify network connectivity
- [ ] Test with known working parameters
- [ ] Check metrics for anomalies
- [ ] Review recent code changes
- [ ] Run tests to verify basic functionality
- [ ] Check disk space and memory

## Getting Help

1. **Check logs first** - Most issues are logged
2. **Enable DEBUG logging** - Get detailed information
3. **Isolate the problem** - Narrow down to specific component
4. **Check documentation** - Review API docs
5. **Search issues** - Look for similar problems
6. **Ask for help** - Provide logs and context

## Next Steps

- [Local Development Guide](LOCAL_DEVELOPMENT.md) - Setup and configuration
- [Trip Planning API Guide](TRIP_PLANNING_API.md) - API reference
- [Ktor Debugging](https://ktor.io/docs/development-mode.html) - Official Ktor docs
# Local Development Guide

This guide covers everything you need to develop and test the KRAIL-BFF server locally.

## Quick Start

### Prerequisites

- JDK 17 or higher
- NSW Transport API Key ([Get one here](https://opendata.transport.nsw.gov.au/))

### First Time Setup

1. **Clone the repository** (if you haven't already)
   ```bash
   cd /Users/ksharma/code/apps/KRAIL-BFF
   ```

2. **Set your NSW API Key**
   ```bash
   export NSW_API_KEY="your-api-key-here"
   ```

3. **Build the project**
   ```bash
   ./gradlew build
   ```

4. **Run the server**
   ```bash
   ./gradlew :server:run
   ```

The server will start on `http://localhost:8080`

## Running the Server

### Development Mode (with Debug Logging)

Get detailed logs including API requests/responses:

```bash
LOG_LEVEL=DEBUG ./gradlew :server:run
```

### Production Mode (INFO Logging)

Only important events and errors:

```bash
./gradlew :server:run
```

### Custom Port

```bash
./gradlew :server:run -PrunPort=9090
```

### With Custom Configuration

Edit `server/src/main/resources/application.yaml` or use environment variables (see Configuration section below).

## Development Workflow

### Making Code Changes

The server supports hot reload in development mode. After making changes:

1. **Stop the server** (Ctrl+C)
2. **Rebuild and run**
   ```bash
   ./gradlew :server:run
   ```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run tests with detailed output
./gradlew test --info

# Run specific test class
./gradlew :server:test --tests "app.krail.bff.TripPlanningTest"

# Run tests in continuous mode
./gradlew test --continuous
```

### Viewing Test Reports

After running tests, open:
```
server/build/reports/tests/test/index.html
```

## Configuration

### Environment Variables

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `NSW_API_KEY` | Your NSW Transport API key | - | ✅ Yes |
| `NSW_BASE_URL` | NSW Transport API base URL | `https://api.transport.nsw.gov.au` | No |
| `LOG_LEVEL` | Logging level (DEBUG, INFO, WARN, ERROR) | `INFO` | No |
| `NSW_CONNECT_TIMEOUT_MS` | HTTP connection timeout | `10000` | No |
| `NSW_READ_TIMEOUT_MS` | HTTP read timeout | `10000` | No |
| `NSW_BREAKER_FAILURE_THRESHOLD` | Circuit breaker failure count | `3` | No |
| `NSW_BREAKER_RESET_TIMEOUT_MS` | Circuit breaker reset time | `60000` | No |

### Configuration File

Edit `server/src/main/resources/application.yaml`:

```yaml
ktor:
    application:
        modules:
            - app.krail.bff.ApplicationKt.module
    deployment:
        port: 8080

nsw:
    baseUrl: "https://api.transport.nsw.gov.au"
    apiKey: ""  # Set via NSW_API_KEY environment variable
    connectTimeoutMs: 10000
    readTimeoutMs: 10000
    breakerFailureThreshold: 3
    breakerResetTimeoutMs: 60000
```

## Debugging

### Enable Debug Logging

```bash
LOG_LEVEL=DEBUG ./gradlew :server:run
```

### What You'll See in Debug Mode

1. **HTTP Client Requests**
   ```json
   {
     "message": "Requesting trip from NSW API - origin: 10101100, destination: 10101328, depArr: dep, date: null, time: null, excludedModes: []",
     "logger_name": "app.krail.bff.client.nsw.NswClientImpl",
     "level": "DEBUG"
   }
   ```

2. **Response Summaries**
   ```json
   {
     "message": "Trip API response received - journeys count: 3, has error: false",
     "logger_name": "app.krail.bff.client.nsw.NswClientImpl",
     "level": "DEBUG"
   }
   ```

3. **Circuit Breaker Events**
   ```json
   {
     "message": "Circuit breaker opened - failure threshold reached: 3",
     "logger_name": "app.krail.bff.client.nsw.NswClientImpl",
     "level": "WARN"
   }
   ```

### Using IntelliJ IDEA Debugger

1. Open the project in IntelliJ IDEA
2. Set breakpoints in your code
3. Run the server in debug mode:
   - Click on the green play button next to `Application.kt`
   - Select "Debug 'Application'"

### Viewing Logs

Logs are output in JSON format. To make them more readable:

#### Using jq (recommended)

```bash
# Install jq if you don't have it
brew install jq

# Pipe logs through jq
LOG_LEVEL=DEBUG ./gradlew :server:run 2>&1 | grep '^{' | jq
```

#### Filter Specific Logs

```bash
# Only NSW client logs
./gradlew :server:run 2>&1 | grep 'NswClientImpl' | jq

# Only errors
./gradlew :server:run 2>&1 | grep '"level":"ERROR"' | jq

# Only trip planning requests
./gradlew :server:run 2>&1 | grep 'Requesting trip' | jq
```

## Project Structure

```
KRAIL-BFF/
├── server/                          # Main server module
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/
│   │   │   │   └── app/krail/bff/
│   │   │   │       ├── Application.kt        # Entry point
│   │   │   │       ├── client/              # External API clients
│   │   │   │       │   └── nsw/
│   │   │   │       │       └── NswClient.kt # NSW Transport API client
│   │   │   │       ├── config/              # Configuration classes
│   │   │   │       ├── di/                  # Dependency injection
│   │   │   │       ├── model/               # Data models
│   │   │   │       ├── plugins/             # Ktor plugins
│   │   │   │       └── routes/              # API routes
│   │   │   └── resources/
│   │   │       ├── application.yaml         # Configuration
│   │   │       └── logback.xml             # Logging configuration
│   │   └── test/
│   │       └── kotlin/                      # Tests
│   └── build.gradle.kts                     # Build configuration
├── docs/                                    # Documentation
│   ├── LOCAL_DEVELOPMENT.md                # This file
│   ├── TRIP_PLANNING_API.md                # Trip planning API guide
│   ├── DEBUGGING.md                        # Debugging guide
│   └── ROADMAP.md                          # Project roadmap
├── gradle/                                  # Gradle wrapper
├── build.gradle.kts                        # Root build file
└── settings.gradle.kts                     # Gradle settings
```

## Common Tasks

### Clean Build

```bash
./gradlew clean build
```

### Check for Dependency Updates

```bash
./gradlew dependencyUpdates
```

### Generate Code Coverage Report

```bash
./gradlew test jacocoTestReport
# Report: build/reports/jacoco/test/html/index.html
```

### Format Code

```bash
./gradlew ktlintFormat
```

### Build Fat JAR

```bash
./gradlew :server:buildFatJar
# Output: server/build/libs/server-all.jar

# Run the JAR
java -jar server/build/libs/server-all.jar
```

## Troubleshooting

### Port Already in Use

If you see `Address already in use`:

```bash
# Find process using port 8080
lsof -ti:8080

# Kill the process
kill -9 $(lsof -ti:8080)

# Or use a different port
./gradlew :server:run -PrunPort=9090
```

### API Key Not Set

If you see authentication errors:

```bash
# Check if the variable is set
echo $NSW_API_KEY

# Set it in your shell profile for persistence
echo 'export NSW_API_KEY="your-key-here"' >> ~/.zshrc
source ~/.zshrc
```

### Build Failures

```bash
# Clean and rebuild
./gradlew clean build --refresh-dependencies

# Check Gradle version
./gradlew --version

# Update Gradle wrapper
./gradlew wrapper --gradle-version=8.5
```

### Tests Failing

```bash
# Run tests with stack traces
./gradlew test --stacktrace

# Run a specific test with debug output
./gradlew :server:test --tests "TripPlanningTest" --debug
```

## Next Steps

- [Trip Planning API Guide](TRIP_PLANNING_API.md) - Testing the trip planning endpoints
- [Debugging Guide](DEBUGGING.md) - Advanced debugging techniques
- [ROADMAP](ROADMAP.md) - Project roadmap and future features

## Getting Help

- Check the [Ktor Documentation](https://ktor.io/docs/)
- Review the [NSW Transport API Documentation](https://opendata.transport.nsw.gov.au/documentation)
- Search issues in the project repository

