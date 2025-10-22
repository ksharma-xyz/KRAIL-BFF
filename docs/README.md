# KRAIL-BFF Documentation

Quick access to everything you need to develop and test the KRAIL-BFF service.

## 📖 Documentation

| Guide | Purpose |
|-------|---------|
| **[Local Development](LOCAL_DEVELOPMENT.md)** | Setup, run, and develop locally |
| **[Configuration](CONFIGURATION.md)** | API keys and environment setup |
| **[Trip Planning API](TRIP_PLANNING_API.md)** | API endpoints and examples |
| **[Debugging](DEBUGGING.md)** | Troubleshooting and logs |

## ⚡ Quick Start

```bash
# 1. Setup (one-time)
cp local.properties.template local.properties
# Edit local.properties with your NSW Transport API key

# 2. Run
./gradlew :server:run

# 3. Test (new terminal)
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020" | jq
```

Or use the automated test script:
```bash
./scripts/test-trip-planning.sh
```

## 🎯 By Role

**Developers:** [Local Development](LOCAL_DEVELOPMENT.md) → [Configuration](CONFIGURATION.md)  
**QA/Testers:** [Trip Planning API](TRIP_PLANNING_API.md) → [Debugging](DEBUGGING.md)  
**DevOps:** [Configuration](CONFIGURATION.md) → [Debugging](DEBUGGING.md)

## 📝 Project Info

- **[Roadmap](ROADMAP.md)** - Feature roadmap
- **[OpenAPI Spec](openapi/documentation.yaml)** - API specification
