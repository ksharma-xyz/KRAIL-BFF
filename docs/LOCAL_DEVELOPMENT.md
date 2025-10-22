# Local Development Guide

## First Time Setup

```bash
# 1. Set API key
cp local.properties.template local.properties
# Edit local.properties with your key from https://opendata.transport.nsw.gov.au/

# 2. Build
./gradlew build

# 3. Run
./gradlew :server:run
```

Server starts at `http://localhost:8080`

## Daily Development

```bash
# Run with debug logs
LOG_LEVEL=DEBUG ./gradlew :server:run

# Run tests
./gradlew test

# Custom port
./gradlew :server:run -PrunPort=9090

# Clean build
./gradlew clean build
```

## Configuration

See [CONFIGURATION.md](CONFIGURATION.md) for details.

**Environment Variables:**
- `NSW_API_KEY` - Your API key (required)
- `LOG_LEVEL` - DEBUG, INFO (default), WARN, ERROR

**Config File:** `server/src/main/resources/application.yaml`

**Test it works:**
```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020" | jq
```

## Project Structure

```
server/src/
├── main/kotlin/app/krail/bff/
│   ├── Application.kt           # Entry point
│   ├── client/nsw/             # NSW API client
│   ├── config/                 # Configuration
│   ├── di/                     # Dependency injection
│   ├── model/                  # Data models
│   ├── plugins/                # Ktor plugins
│   └── routes/                 # API endpoints
└── test/kotlin/                # Tests
```

## Common Tasks

```bash
# Run tests continuously
./gradlew test --continuous

# Build fat JAR
./gradlew :server:buildFatJar
# Output: server/build/libs/server-all.jar

# Run JAR
java -jar server/build/libs/server-all.jar

# Check dependencies
./gradlew dependencies
```

## Troubleshooting

**Port in use:**
```bash
lsof -ti:8080 | xargs kill -9
```

**Build fails:**
```bash
./gradlew clean build --refresh-dependencies
```

**Tests fail:**
```bash
./gradlew test --stacktrace
# View report: server/build/reports/tests/test/index.html
```

## IDE Setup

**IntelliJ IDEA:**
1. Open project
2. Gradle will auto-import
3. Run/Debug `Application.kt`

**VS Code:**
1. Install Kotlin extension
2. Open project
3. Use terminal for gradle commands

## Next Steps

- **[Testing Quick Start](TESTING_QUICK_START.md)** - Test the API
- **[Trip Planning API](TRIP_PLANNING_API.md)** - API reference
- **[Debugging](DEBUGGING.md)** - When things go wrong
