# Protobuf Setup Complete ✅

## What Was Done

Successfully integrated Protocol Buffers using Square's Wire library to convert NSW Trip API responses into ready-to-display journey data.

## Key Components

### 1. Proto Schema (`server/src/main/proto/trip.proto`)
Defines the client-facing data structure:
- `JourneyList` - List of journey options
- `JourneyCardInfo` - Single journey with all display fields
- `TransportLeg` / `WalkingLeg` - Journey segments
- `Stop`, `ServiceAlert`, `DepartureDeviation` - Supporting types

### 2. Mapper (`JourneyListMapper.kt`)
Converts NSW API JSON to protobuf format:
- Formats times and durations
- Extracts platform information
- Filters transport modes
- Calculates departure deviations
- Maps all legs and stops

### 3. API Endpoint (`/api/v1/trip/plan-proto`)
Returns protobuf binary data on success, JSON on errors:
- ✅ Success (200): Protobuf binary with `Content-Type: application/protobuf`
- ❌ Errors (4xx, 5xx): JSON with error details

## Error Handling Strategy

**You do NOT need protobuf for errors!**

### Response Format:
- **Success (200)**: Binary protobuf data
- **Client errors (400-499)**: JSON error response
- **Server errors (500-599)**: JSON error response

### Example Error Response:
```json
{
  "error": "Bad Request",
  "message": "Missing 'origin' parameter",
  "statusCode": 400,
  "timestamp": "2024-11-06T10:30:00Z"
}
```

### Why JSON for Errors?
1. HTTP status codes already indicate errors
2. JSON is human-readable for debugging
3. Standard practice (even for protobuf APIs)
4. Simpler client implementation

## How to Test

### 1. Start the server
```bash
./gradlew :server:run
```

### 2. Run the test script
```bash
./scripts/test-proto-endpoint.sh
```

### 3. Manual test
```bash
# Test error case (missing parameter)
curl -i "http://localhost:8080/api/v1/trip/plan-proto?destination=10101120"
# Returns: 400 Bad Request (JSON)

# Test success case (Central to Town Hall)
curl "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120" \
  --output journey.bin
# Returns: 200 OK (Binary protobuf)

# Check server logs for detailed journey info
```

## Client Implementation Guide

### Check HTTP Status First
```kotlin
when (response.status.value) {
    200 -> {
        // Success - decode protobuf
        val journeyList = JourneyList.ADAPTER.decode(response.body<ByteArray>())
        // Use journeyList.journeys
    }
    in 400..499 -> {
        // Client error - parse JSON
        val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
        // Show error.message to user
    }
    in 500..599 -> {
        // Server error - parse JSON
        val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
        // Show "Please try again later"
    }
}
```

## Documentation

📖 **Detailed Documentation:**
- `docs/PROTOBUF_INTEGRATION.md` - Complete integration guide
- `docs/ERROR_HANDLING.md` - Error handling reference
- `server/src/main/proto/trip.proto` - Proto schema (API contract)

## Benefits

### For Server
- All business logic in one place
- Easy to update without client changes
- Single source of truth

### For Clients
- Minimal parsing logic
- Type-safe data structures
- 30-50% smaller payload
- Ready-to-display data

### For Team
- Proto serves as API documentation
- Breaking changes caught at compile time
- Consistent across all platforms

## Quick Reference

| Aspect | Success | Error |
|--------|---------|-------|
| Status | 200 | 4xx, 5xx |
| Content-Type | `application/protobuf` | `application/json` |
| Format | Binary protobuf | JSON |
| Parsing | Decode with Wire/protobuf lib | Parse JSON |

## Next Steps

1. **Generate proto files for your client platform:**
   - iOS: Use Swift Protobuf
   - Android: Wire or Protobuf Lite
   - Web: protobuf.js

2. **Implement client decoder:**
   - Check HTTP status first
   - Decode protobuf on 200
   - Parse JSON on errors

3. **Test with real data:**
   - Run test script
   - Check server logs
   - Verify binary protobuf response

## Summary

✅ Protobuf setup complete
✅ Mapper converts JSON to ready-to-display format
✅ Endpoint returns binary protobuf on success
✅ Errors always return JSON (never protobuf)
✅ Server logs show detailed journey information
✅ Documentation complete

**Your server now sends protobuf journey lists to clients, with JSON for all errors!**

