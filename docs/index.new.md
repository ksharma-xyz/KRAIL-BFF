---
layout: default
title: Home
nav_order: 1
description: "Backend For Frontend service for NSW Transport API with Protocol Buffers support - 83% smaller responses"
permalink: /
---

# KRAIL BFF Documentation
{: .fs-9 }

Backend For Frontend service for NSW Transport API with Protocol Buffers support.
{: .fs-6 .fw-300 }

[Get Started](#quick-start){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[View on GitHub](https://github.com/ksharma-xyz/KRAIL-BFF){: .btn .fs-5 .mb-4 .mb-md-0 }

---

## 🎯 Key Features

{: .highlight }
**83% Smaller Responses** - Protobuf (16 KB) vs JSON (96 KB)

- ✅ **Type-Safe** - Wire/Protocol Buffers with compile-time safety
- ✅ **Ready-to-Display** - All formatting done server-side
- ✅ **Fast** - 2-3x faster parsing than JSON
- ✅ **Auto-Deploy** - GitHub Actions workflow included

---

## 🚀 Quick Start

```bash
# 1. Setup
cp local.properties.template local.properties
# Add your NSW Transport API key to local.properties

# 2. Run server
./gradlew :server:run

# 3. Test JSON endpoint
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101120"

# 4. Test Protobuf endpoint (83% smaller!)
curl "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120" \
  -o journey.bin

# 5. Compare sizes
./scripts/check-size.sh
```

---

## 📚 Documentation

| Section | Description |
|:--------|:------------|
| **[Protobuf Integration](guides/PROTOBUF.html)** | Complete guide to Protocol Buffers (83% smaller!) |
| [Local Development](guides/LOCAL_DEVELOPMENT.html) | Setup and run locally |
| [Testing](guides/TESTING.html) | Testing guide and scripts |
| [Debugging](guides/DEBUGGING.html) | Troubleshooting tips |

---

## 🔌 API Endpoints

**Base URL:** `http://localhost:8080`

| Endpoint | Format | Size | Description |
|:---------|:-------|:-----|:------------|
| `GET /api/v1/trip/plan` | JSON | 96 KB | Trip planning |
| `GET /api/v1/trip/plan-proto` | **Protobuf** | **16 KB** | **Trip planning (83% smaller!)** |
| `GET /health` | JSON | - | Health check |

**API Documentation:**
- [Trip Planning API](api/TRIP_PLANNING_API.html) - Detailed endpoint documentation
- [Error Handling](api/ERROR_HANDLING.html) - Error codes and responses

---

## ⚙️ Configuration

- [Configuration Reference](reference/CONFIGURATION.html) - Environment variables and setup
- [Roadmap](reference/ROADMAP.html) - Future features

---

## 🧪 Quick Test

```bash
# Run tests
./gradlew test

# Compare JSON vs Protobuf sizes
./scripts/check-size.sh

# Test all endpoints
./scripts/test-proto-endpoint.sh
```

---

## 📦 Tech Stack

**Language:** Kotlin • **Framework:** Ktor 3.3.1 • **Protobuf:** Wire 5.1.0

---

## 🔗 Resources

- [NSW Transport OpenData](https://opendata.transport.nsw.gov.au/)
- [Wire Documentation](https://square.github.io/wire/)
- [Protocol Buffers Guide](https://protobuf.dev/)

