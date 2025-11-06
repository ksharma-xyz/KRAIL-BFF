---
layout: default
title: KRAIL BFF Documentation
---

# KRAIL BFF Documentation

Backend For Frontend service for NSW Transport API with Protocol Buffers support.

## 🚀 Quick Start

```bash
# 1. Setup
cp local.properties.template local.properties
# Add your NSW Transport API key to local.properties

# 2. Run server
./gradlew :server:run

# 3. Test
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101120"

# 4. Compare JSON vs Protobuf
./scripts/check-size.sh
```

---

## 📚 Guides

| Guide | Description |
|-------|-------------|
| [**Protobuf Integration**](guides/PROTOBUF.md) | **Protocol Buffers (83% smaller responses!)** |
| [Local Development](guides/LOCAL_DEVELOPMENT.md) | Setup and run locally |
| [Testing](guides/TESTING.md) | Testing guide |
| [Debugging](guides/DEBUGGING.md) | Troubleshooting |

---

## 🔌 API Endpoints

**Base URL:** `http://localhost:8080`

| Endpoint | Format | Size | Description |
|----------|--------|------|-------------|
| `GET /api/v1/trip/plan` | JSON | 96 KB | Trip planning |
| `GET /api/v1/trip/plan-proto` | **Protobuf** | **16 KB** | **Trip planning (83% smaller!)** |
| `GET /health` | JSON | - | Health check |

**Documentation:**
- [Trip Planning API](api/TRIP_PLANNING_API.md) - Detailed endpoint documentation
- [Error Handling](api/ERROR_HANDLING.md) - Error codes and responses

---

## ⚙️ Configuration

| Document | Description |
|----------|-------------|
| [Configuration](reference/CONFIGURATION.md) | Environment variables and setup |
| [Roadmap](reference/ROADMAP.md) | Future features |

---

## 🎯 Key Features

✅ **83% Smaller Responses** - Protobuf (16 KB) vs JSON (96 KB)  
✅ **Type-Safe** - Wire/Protocol Buffers  
✅ **Ready-to-Display** - All formatting done server-side  
✅ **Standard Errors** - JSON error responses for all endpoints

---

## 🧪 Testing

```bash
# Run tests
./gradlew test

# Compare sizes
./scripts/check-size.sh

# Test endpoints
./scripts/test-proto-endpoint.sh
```

See [Testing Guide](guides/TESTING.md) for details.

---

## 📦 Tech Stack

**Language:** Kotlin | **Framework:** Ktor 3.3.1 | **Protobuf:** Wire 5.1.0

---

## 🔗 Links

- [NSW Transport OpenData](https://opendata.transport.nsw.gov.au/)
- [Wire Documentation](https://square.github.io/wire/)
- [Protocol Buffers Guide](https://protobuf.dev/)

