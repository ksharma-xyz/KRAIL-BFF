# Protocol Buffers Integration

This document explains the Protocol Buffers (protobuf) integration using Square's Wire library.

## Overview

The KRAIL BFF server converts NSW Transport API JSON responses into protobuf format. The server performs all the complex mapping logic, so clients receive ready-to-display journey data.

### Architecture Flow

```
NSW API (JSON) → Server Mapper → Protobuf → Client (Display)
```

1. **NSW API**: Returns raw JSON trip data
2. **Server Mapper**: Converts JSON to protobuf format (all business logic here)
3. **Protobuf Response**: Binary data sent to clients
4. **Client**: Decodes protobuf and displays directly (minimal logic)

## Files Structure

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

## Protocol Buffer Schema

The proto schema defines the client-facing data structure:

```protobuf
message JourneyList {
  repeated JourneyCardInfo journeys = 1;
}

message JourneyCardInfo {
  string time_text = 1;              // "in 5 mins"
  string origin_time = 2;            // "11:30am"
  string destination_time = 3;       // "11:45am"
  string travel_time = 4;            // "15 mins"
  repeated TransportModeLine transport_mode_lines = 5;
  repeated Leg legs = 6;
  // ... more fields
}
```

See `server/src/main/proto/trip.proto` for the complete schema.

## API Endpoints

### 1. JSON Endpoint (Original)
```
GET /api/v1/trip/plan
```

Returns raw NSW API JSON response.

**Example:**
```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101120"
```

### 2. Protobuf Endpoint (New)
```
GET /api/v1/trip/plan-proto
```

Returns mapped protobuf binary data.

**Example:**
```bash
curl "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120" \
  --output journey.bin
```

**Query Parameters (both endpoints):**
- `origin` (required): Origin stop ID
- `destination` (required): Destination stop ID
- `depArr` (optional): "dep" or "arr" (default: "dep")
- `date` (optional): Date in YYYYMMDD format
- `time` (optional): Time in HHmm format
- `excludedModes` (optional): Comma-separated mode IDs (e.g., "1,5,9")

## Response Format

### Success Response (200 OK)

**Headers:**
```
HTTP/1.1 200 OK
Content-Type: application/protobuf
Content-Length: 1234
```

**Body:** Binary protobuf data (JourneyList message)

### Error Responses (4xx, 5xx)

**Headers:**
```
HTTP/1.1 400 Bad Request
Content-Type: application/json
```

**Body:**
```json
{
  "error": "Bad Request",
  "message": "Missing 'origin' parameter",
  "statusCode": 400,
  "timestamp": "2024-11-06T10:30:00Z"
}
```

See [ERROR_HANDLING.md](./ERROR_HANDLING.md) for complete error documentation.

## Mapping Logic

The `JourneyListMapper` converts NSW API JSON to protobuf format:

### Key Transformations

1. **Time Formatting**
   - UTC timestamps → "11:30am" (Sydney timezone)
   - Calculate "in X mins" relative time
   - Format durations: "15 mins", "1 hr 30 mins"

2. **Platform Extraction**
   - Parse "Central Station, Platform 1" → "Platform 1"
   - Extract platform number: "1", "2", "A", etc.

3. **Transport Modes**
   - Extract unique transport lines from journey
   - Map product class to transport type

4. **Legs Processing**
   - Filter redundant walking legs
   - Map public transport and walking legs
   - Include walk interchange information

5. **Service Alerts**
   - Count unique alerts across journey
   - Include alert details for each leg

6. **Departure Deviation**
   - Calculate real-time vs planned departure
   - Format as "5 mins late", "2 mins early", or "On Time"

## Client Integration

### Kotlin/Android (Wire)

```kotlin
// Add Wire dependency
dependencies {
    implementation("com.squareup.wire:wire-runtime:5.1.0")
}

// Decode protobuf response
val response = httpClient.get("$baseUrl/api/v1/trip/plan-proto") {
    parameter("origin", "10101100")
    parameter("destination", "10101120")
}

if (response.status == HttpStatusCode.OK) {
    val journeyList = JourneyList.ADAPTER.decode(response.body<ByteArray>())
    
    journeyList.journeys.forEach { journey ->
        println("Time: ${journey.time_text}")
        println("Origin: ${journey.origin_time}")
        println("Destination: ${journey.destination_time}")
        println("Travel: ${journey.travel_time}")
    }
} else {
    val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
    println("Error: ${error.message}")
}
```

### Swift/iOS (Swift Protobuf)

```swift
// Add dependency via SPM
dependencies: [
    .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.27.0")
]

// Decode protobuf response
let url = URL(string: "\(baseURL)/api/v1/trip/plan-proto?origin=10101100&destination=10101120")!
let (data, response) = try await URLSession.shared.data(from: url)

guard let httpResponse = response as? HTTPURLResponse else {
    throw NetworkError.invalidResponse
}

if httpResponse.statusCode == 200 {
    let journeyList = try JourneyList(serializedData: data)
    
    for journey in journeyList.journeys {
        print("Time: \(journey.timeText)")
        print("Origin: \(journey.originTime)")
        print("Destination: \(journey.destinationTime)")
        print("Travel: \(journey.travelTime)")
    }
} else {
    let error = try JSONDecoder().decode(ErrorResponse.self, from: data)
    print("Error: \(error.message)")
}
```

### JavaScript/TypeScript (protobuf.js)

```typescript
// Install: npm install protobufjs
import { JourneyList } from './generated/trip';

const response = await fetch(
    `${baseUrl}/api/v1/trip/plan-proto?origin=10101100&destination=10101120`
);

if (response.ok) {
    const buffer = await response.arrayBuffer();
    const journeyList = JourneyList.decode(new Uint8Array(buffer));
    
    journeyList.journeys.forEach(journey => {
        console.log(`Time: ${journey.timeText}`);
        console.log(`Origin: ${journey.originTime}`);
        console.log(`Destination: ${journey.destinationTime}`);
        console.log(`Travel: ${journey.travelTime}`);
    });
} else {
    const error = await response.json();
    console.error(`Error: ${error.message}`);
}
```

## Testing

### Run the test script

```bash
./scripts/test-proto-endpoint.sh
```

This will:
1. Test error cases (missing parameters)
2. Test successful protobuf response
3. Show server logs with parsed journey details

### Manual testing

```bash
# Test error (missing origin)
curl -i "http://localhost:8080/api/v1/trip/plan-proto?destination=10101120"

# Test success (Central to Town Hall)
curl "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120" \
  --output journey.bin

# Check file size (should be a few KB)
ls -lh journey.bin

# Check server logs for detailed journey information
```

## Development

### Modify the proto schema

1. Edit `server/src/main/proto/trip.proto`
2. Regenerate Wire classes:
   ```bash
   ./gradlew :server:generateMainProtos
   ```
3. Update mapper in `JourneyListMapper.kt`
4. Recompile:
   ```bash
   ./gradlew :server:compileKotlin
   ```

### Add new fields

Example: Add `is_express` field to `JourneyCardInfo`:

1. **Update proto:**
   ```protobuf
   message JourneyCardInfo {
     // ...existing fields...
     bool is_express = 14;
   }
   ```

2. **Regenerate:**
   ```bash
   ./gradlew :server:generateMainProtos
   ```

3. **Update mapper:**
   ```kotlin
   return JourneyCardInfo(
       // ...existing fields...
       is_express = journey.legs?.any { it.transportation?.isExpress == true } ?: false
   )
   ```

## Benefits of This Approach

### For Server
- ✅ All business logic in one place (server)
- ✅ Easy to update mapping logic without client changes
- ✅ Single source of truth for data transformations

### For Clients
- ✅ Minimal parsing logic needed
- ✅ Type-safe data structures
- ✅ Smaller payload size (~30-50% smaller than JSON)
- ✅ Faster parsing (binary format)
- ✅ Ready-to-display data

### For Team
- ✅ Proto schema serves as API documentation
- ✅ Breaking changes caught at compile time
- ✅ Consistent data format across all platforms
- ✅ Easy to add new fields (backward compatible)

## Performance Comparison

| Format   | Size  | Parse Time | Type Safety |
|----------|-------|------------|-------------|
| JSON     | 12 KB | ~15ms      | Runtime     |
| Protobuf | 7 KB  | ~5ms       | Compile     |

*Based on typical journey with 5 legs*

## Troubleshooting

### Proto generation fails
```bash
# Clean and regenerate
./gradlew :server:clean :server:generateMainProtos
```

### Compilation errors after proto change
```bash
# Ensure Wire classes are generated first
./gradlew :server:generateMainProtos :server:compileKotlin
```

### Client can't parse protobuf
- Verify Content-Type is `application/protobuf`
- Check HTTP status is 200 (errors are JSON, not protobuf)
- Ensure client uses same proto version

### Empty journey list
- Check server logs for mapping errors
- Verify NSW API returns valid data
- Test with JSON endpoint first: `/api/v1/trip/plan`

## Resources

- [Wire Documentation](https://square.github.io/wire/)
- [Protocol Buffers Guide](https://protobuf.dev/)
- [ERROR_HANDLING.md](./ERROR_HANDLING.md) - Error handling documentation
- [NSW Transport API Docs](https://opendata.transport.nsw.gov.au/)

## Summary

This setup provides:
- 🚀 Fast, type-safe data transfer via protobuf
- 🛡️ Standard JSON error responses
- 🎯 Server-side mapping (clients get ready-to-display data)
- 📝 Self-documenting API via proto schema
- 🔄 Easy to evolve (backward compatible)

