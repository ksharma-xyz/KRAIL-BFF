---
layout: default
title: Protobuf Integration
parent: Guides
nav_order: 1
---

# Protocol Buffers (Protobuf) Guide
{: .no_toc }

Complete guide for the Protocol Buffers integration using Square's Wire library.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

- [Overview](#overview)
- [Quick Start](#quick-start)
- [Size Comparison](#size-comparison)
- [API Endpoints](#api-endpoints)
- [Error Handling](#error-handling)
- [Client Integration](#client-integration)
- [Development](#development)
- [Testing](#testing)

---

## Overview

The KRAIL BFF server converts NSW Transport API JSON responses into Protocol Buffer format. The server performs all complex mapping logic, so clients receive ready-to-display journey data.

### Architecture Flow

```
NSW API (JSON) → Server Mapper → Protobuf → Client (Display)
```

### Benefits

| Benefit | Description |
|---------|-------------|
| **83% Smaller** | Protobuf is 16 KB vs JSON 96 KB (77 KB saved per request) |
| **2-3x Faster** | Binary format parses faster than JSON |
| **Type-Safe** | Compile-time safety with generated code |
| **Ready to Display** | All formatting done server-side |
| **Easy to Evolve** | Backward compatible schema changes |

---

## Quick Start

### 1. Check Both Endpoints

Run the comparison script:
```bash
./scripts/check-size.sh
```

**Expected output:**
```
Summary:
  JSON:     96111 bytes (93.8 KB)
  Protobuf: 16381 bytes (15.9 KB)
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Saved:    79730 bytes (77.8 KB)
  Reduction: 83%

  🎉 Protobuf is 83% smaller!
```

### 2. Test the Endpoints

**JSON endpoint:**
```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101120"
```

**Protobuf endpoint:**
```bash
curl "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120" \
  --output journey.bin
```

### 3. Check Server Logs

The server logs detailed journey information for debugging:
```
🚊 PROTOBUF JOURNEY LIST
════════════════════════════════════════════════════════════════════════════════
Number of journeys: 12

--- Journey #1 ---
  Time Text: in 2 mins
  Origin Time: 3:45pm (2024-11-06T04:45:00Z)
  Destination Time: 3:59pm (2024-11-06T04:59:00Z)
  Travel Time: 14 mins
  Platform: Platform 23
  Transport Modes: T4 (type=1), T8 (type=1)
  Number of Legs: 3
  Service Alerts: 0
```

---

## Size Comparison

### Real Results

For a typical trip request (Central to Town Hall, 12 journeys):

| Metric | JSON | Protobuf | Savings |
|--------|------|----------|---------|
| **Size** | 96.1 KB | 16.0 KB | 77.8 KB |
| **Reduction** | Baseline | **83% smaller** | - |
| **Parse Time** | ~10-15ms | ~3-5ms | 2-3x faster |

### How to Check Sizes

**Quick check:**
```bash
./scripts/check-size.sh
```

**Manual check:**
```bash
# JSON
curl -s "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101120" \
  -o /tmp/trip.json
ls -lh /tmp/trip.json

# Protobuf
curl -s "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120" \
  -o /tmp/trip.proto
ls -lh /tmp/trip.proto
```

### Why is Protobuf Smaller?

1. **Binary Format**: More compact than text-based JSON
2. **No Field Names**: Uses field numbers instead of names
3. **Efficient Encoding**: Variable-length integers, optimized encoding
4. **No Whitespace**: No pretty-printing or indentation

**Example:**

JSON (100 bytes):
```json
{
  "time_text": "in 5 mins",
  "origin_time": "11:30am",
  "destination_time": "11:45am"
}
```

Protobuf (35 bytes - 65% smaller):
```
\x0a\x09in 5 mins\x12\x0711:30am\x1a\x0711:45am
```

---

## API Endpoints

### 1. JSON Endpoint (Original)

```
GET /api/v1/trip/plan
```

Returns raw NSW API JSON response.

**Query Parameters:**
- `origin` (required): Origin stop ID
- `destination` (required): Destination stop ID  
- `depArr` (optional): "dep" or "arr" (default: "dep")
- `date` (optional): Date in YYYYMMDD format
- `time` (optional): Time in HHmm format
- `excludedModes` (optional): Comma-separated mode IDs (e.g., "1,5,9")

**Example:**
```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101120"
```

### 2. Protobuf Endpoint (New)

```
GET /api/v1/trip/plan-proto
```

Returns mapped protobuf binary data (same parameters as above).

**Success Response (200 OK):**
```
HTTP/1.1 200 OK
Content-Type: application/protobuf
Content-Length: 16381

<binary protobuf data>
```

**Example:**
```bash
curl "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120" \
  --output journey.bin
```

---

## Error Handling

### Important: Errors Return JSON, NOT Protobuf!

**Success (200)**: Binary protobuf  
**Errors (4xx, 5xx)**: JSON error response

### Why JSON for Errors?

1. ✅ HTTP status codes already indicate errors
2. ✅ JSON is human-readable for debugging
3. ✅ Industry standard practice
4. ✅ Simpler client implementation

### Error Response Format

```json
{
  "error": "Bad Request",
  "message": "Missing 'origin' parameter",
  "statusCode": 400,
  "timestamp": "2024-11-06T10:30:00Z"
}
```

### Common Error Codes

| Code | Type | Example | Client Action |
|------|------|---------|---------------|
| **400** | Bad Request | Missing parameter | Show validation error |
| **401** | Unauthorized | Invalid API key | Re-authenticate |
| **403** | Forbidden | Rate limited | Wait and retry |
| **404** | Not Found | Invalid stop ID | Show "not found" |
| **429** | Too Many Requests | Rate limit hit | Wait `retryAfter` seconds |
| **500** | Server Error | Unexpected error | Show "try again later" |
| **502** | Bad Gateway | NSW API down | Show "service unavailable" |
| **504** | Gateway Timeout | NSW API timeout | Retry with backoff |

### Client Implementation Pattern

```kotlin
val response = httpClient.get("$baseUrl/api/v1/trip/plan-proto") {
    parameter("origin", "10101100")
    parameter("destination", "10101120")
}

when (response.status.value) {
    200 -> {
        // Success - decode protobuf
        val journeyList = JourneyList.ADAPTER.decode(response.body<ByteArray>())
        displayJourneys(journeyList)
    }
    in 400..499 -> {
        // Client error - parse JSON and show to user
        val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
        showError(error.message)
    }
    in 500..599 -> {
        // Server error - parse JSON and retry
        val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
        showError("Please try again later")
    }
}
```

---

## Client Integration

### Kotlin/Android (Wire)

**1. Add Wire dependency:**
```kotlin
dependencies {
    implementation("com.squareup.wire:wire-runtime:5.1.0")
}
```

**2. Copy proto file to your project:**
```
app/src/main/proto/trip.proto
```

**3. Generate code:**
Wire will auto-generate Kotlin classes from the proto file.

**4. Decode protobuf:**
```kotlin
val response = httpClient.get("$baseUrl/api/v1/trip/plan-proto") {
    parameter("origin", "10101100")
    parameter("destination", "10101120")
}

if (response.status == HttpStatusCode.OK) {
    val journeyList = JourneyList.ADAPTER.decode(response.body<ByteArray>())
    
    journeyList.journeys.forEach { journey ->
        // Data is ready to display!
        println("Departs: ${journey.time_text}")
        println("From: ${journey.origin_time}")
        println("To: ${journey.destination_time}")
        println("Duration: ${journey.travel_time}")
        journey.platform_text?.let { println("Platform: $it") }
    }
} else {
    val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
    showError(error.message)
}
```

### Swift/iOS (Swift Protobuf)

**1. Add dependency via SPM:**
```swift
dependencies: [
    .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.27.0")
]
```

**2. Generate Swift code from proto:**
```bash
protoc --swift_out=. trip.proto
```

**3. Decode protobuf:**
```swift
let url = URL(string: "\(baseURL)/api/v1/trip/plan-proto?origin=10101100&destination=10101120")!
let (data, response) = try await URLSession.shared.data(from: url)

guard let httpResponse = response as? HTTPURLResponse else {
    throw NetworkError.invalidResponse
}

if httpResponse.statusCode == 200 {
    let journeyList = try JourneyList(serializedData: data)
    
    for journey in journeyList.journeys {
        // Data is ready to display!
        print("Departs: \(journey.timeText)")
        print("From: \(journey.originTime)")
        print("To: \(journey.destinationTime)")
        print("Duration: \(journey.travelTime)")
    }
} else {
    let error = try JSONDecoder().decode(ErrorResponse.self, from: data)
    showError(error.message)
}
```

### JavaScript/TypeScript (protobuf.js)

**1. Install:**
```bash
npm install protobufjs
```

**2. Generate code:**
```bash
pbjs -t static-module -w es6 trip.proto -o trip.js
pbts -o trip.d.ts trip.js
```

**3. Decode protobuf:**
```typescript
import { JourneyList } from './generated/trip';

const response = await fetch(
    `${baseUrl}/api/v1/trip/plan-proto?origin=10101100&destination=10101120`
);

if (response.ok) {
    const buffer = await response.arrayBuffer();
    const journeyList = JourneyList.decode(new Uint8Array(buffer));
    
    journeyList.journeys.forEach(journey => {
        // Data is ready to display!
        console.log(`Departs: ${journey.timeText}`);
        console.log(`From: ${journey.originTime}`);
        console.log(`To: ${journey.destinationTime}`);
        console.log(`Duration: ${journey.travelTime}`);
    });
} else {
    const error = await response.json();
    showError(error.message);
}
```

---

## Development

### Project Structure

```
server/
├── src/main/proto/
│   └── trip.proto                 # Protocol buffer schema
├── src/main/kotlin/app/krail/bff/
│   ├── mapper/
│   │   └── JourneyListMapper.kt   # JSON → Proto mapper
│   ├── routes/
│   │   └── TripRoutes.kt          # API endpoints
│   └── model/
│       ├── TripModels.kt          # JSON models (NSW API)
│       └── ErrorResponse.kt       # Error response model
└── build/generated/source/wire/   # Auto-generated Wire classes
```

### Protocol Buffer Schema

The proto schema defines the client-facing data structure.

**Key messages:**
- `JourneyList` - List of journey options
- `JourneyCardInfo` - Single journey with all display fields
- `TransportLeg` / `WalkingLeg` - Journey segments
- `Stop`, `ServiceAlert`, `DepartureDeviation` - Supporting types

**See:** `server/src/main/proto/trip.proto`

### Mapping Logic

The `JourneyListMapper` converts NSW API JSON to protobuf format:

**Key transformations:**
1. **Time Formatting**: UTC → "11:30am" (Sydney timezone)
2. **Relative Time**: Calculate "in 5 mins"
3. **Platform Extraction**: Parse "Central Station, Platform 1" → "Platform 1"
4. **Duration Formatting**: Seconds → "15 mins", "1 hr 30 mins"
5. **Transport Modes**: Extract unique lines from journey
6. **Leg Processing**: Filter redundant walking legs
7. **Service Alerts**: Count and map alerts
8. **Departure Deviation**: Calculate real-time vs planned ("5 mins late")

### Modify the Proto Schema

**1. Edit proto file:**
```bash
vim server/src/main/proto/trip.proto
```

**2. Regenerate Wire classes:**
```bash
./gradlew :server:generateMainProtos
```

**3. Update mapper:**
```bash
vim server/src/main/kotlin/app/krail/bff/mapper/JourneyListMapper.kt
```

**4. Recompile:**
```bash
./gradlew :server:compileKotlin
```

### Add New Fields Example

**1. Update proto:**
```protobuf
message JourneyCardInfo {
  // ...existing fields...
  bool is_express = 14;
  optional string trip_code = 15;
}
```

**2. Regenerate:**
```bash
./gradlew :server:generateMainProtos
```

**3. Update mapper:**
```kotlin
return JourneyCardInfo(
    // ...existing fields...
    is_express = journey.legs?.any { it.isExpress() } ?: false,
    trip_code = journey.legs?.firstOrNull()?.transportation?.properties?.tripCode
)
```

---

## Testing

### Run All Tests

```bash
# Size comparison
./scripts/check-size.sh

# Complete test suite
./scripts/test-proto-endpoint.sh

# Detailed comparison
./scripts/compare-json-vs-proto.sh
```

### Manual Testing

**1. Test error (missing parameter):**
```bash
curl -i "http://localhost:8080/api/v1/trip/plan-proto?destination=10101120"
# Expected: 400 Bad Request (JSON)
```

**2. Test success:**
```bash
curl "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120" \
  --output journey.bin

ls -lh journey.bin
# Expected: ~16 KB binary file
```

**3. Check server logs:**
Server will log detailed journey information for debugging.

### Troubleshooting

**Proto generation fails:**
```bash
./gradlew :server:clean :server:generateMainProtos
```

**Compilation errors after proto change:**
```bash
./gradlew :server:generateMainProtos :server:compileKotlin
```

**Client can't parse protobuf:**
- Verify Content-Type is `application/protobuf`
- Check HTTP status is 200 (errors are JSON)
- Ensure client uses same proto version

**Empty journey list:**
- Check server logs for mapping errors
- Verify NSW API returns valid data
- Test JSON endpoint first: `/api/v1/trip/plan`

---

## Resources

- [Wire Documentation](https://square.github.io/wire/)
- [Protocol Buffers Guide](https://protobuf.dev/)
- [NSW Transport API Docs](https://opendata.transport.nsw.gov.au/)
- [Error Handling Reference](../api/ERROR_HANDLING.md)

---

## Summary

✅ **Setup Complete**: Wire plugin configured, proto schema created, mapper implemented  
✅ **83% Smaller**: Protobuf (16 KB) vs JSON (96 KB)  
✅ **Error Handling**: JSON for errors, Protobuf for success  
✅ **Ready to Display**: All formatting done server-side  
✅ **Type-Safe**: Compile-time safety with generated code  
✅ **Easy to Maintain**: All business logic in one place (server)  

**The server now sends ready-to-display journey data in an efficient binary format!**

