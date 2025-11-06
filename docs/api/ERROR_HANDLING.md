---
layout: default
title: Error Handling
parent: API
nav_order: 2
---

# API Error Handling Strategy
{: .no_toc }

Complete guide to error handling in KRAIL BFF API.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The KRAIL BFF API uses different response formats for success and error cases:

- **Success**: Returns Protocol Buffer (protobuf) binary data with `Content-Type: application/protobuf`
- **Errors**: Returns JSON with error details and appropriate HTTP status codes

## Why Not Use Protobuf for Errors?

1. **HTTP Status Codes**: HTTP already provides a standard way to communicate errors (4xx, 5xx status codes)
2. **Debugging**: JSON errors are human-readable and easier to debug
3. **Standard Practice**: Most APIs use JSON for errors, even when using protobuf for data
4. **Client Simplicity**: Clients check HTTP status first, then parse response body accordingly

## Response Format by Status

### Success (2xx Status Codes)

#### 200 OK - Success with protobuf data
```
HTTP/1.1 200 OK
Content-Type: application/protobuf
Content-Length: 1234

<binary protobuf data>
```

**Client handling:**
```kotlin
if (response.isSuccessful) {
    val journeyList = JourneyList.ADAPTER.decode(response.body.bytes())
    // Use journeyList data
}
```

---

### Client Errors (4xx Status Codes)

#### 400 Bad Request - Invalid parameters
```json
{
  "error": "Bad Request",
  "message": "Missing 'origin' parameter",
  "statusCode": 400,
  "timestamp": "2024-11-06T10:30:00Z"
}
```

**When it happens:**
- Missing required query parameters (origin, destination)
- Invalid parameter format
- Invalid transport mode IDs

#### 401 Unauthorized - Missing or invalid API key
```json
{
  "error": "Unauthorized",
  "message": "Invalid or missing API key",
  "statusCode": 401,
  "timestamp": "2024-11-06T10:30:00Z"
}
```

**When it happens:**
- Missing Authorization header
- Invalid API key
- Expired API key

#### 403 Forbidden - Access denied
```json
{
  "error": "Forbidden",
  "message": "Access denied to this resource",
  "statusCode": 403,
  "timestamp": "2024-11-06T10:30:00Z"
}
```

**When it happens:**
- API key doesn't have permission for this endpoint
- Rate limit exceeded
- IP address blocked

#### 404 Not Found - Resource not found
```json
{
  "error": "Not Found",
  "message": "Stop ID not found: ABC123",
  "statusCode": 404,
  "timestamp": "2024-11-06T10:30:00Z"
}
```

**When it happens:**
- Invalid stop/station ID
- Endpoint doesn't exist

#### 429 Too Many Requests - Rate limit exceeded
```json
{
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Please try again later.",
  "statusCode": 429,
  "timestamp": "2024-11-06T10:30:00Z",
  "details": {
    "retryAfter": "60"
  }
}
```

**When it happens:**
- Too many requests in a time window
- Check `Retry-After` header or `details.retryAfter` field

---

### Server Errors (5xx Status Codes)

#### 500 Internal Server Error - Unexpected server error
```json
{
  "error": "Internal Server Error",
  "message": "Failed to fetch trip data: Unexpected error occurred",
  "statusCode": 500,
  "timestamp": "2024-11-06T10:30:00Z"
}
```

**When it happens:**
- Unexpected exceptions in server code
- Parsing errors
- Database connection failures
- Memory issues

#### 502 Bad Gateway - Upstream API error
```json
{
  "error": "Bad Gateway",
  "message": "NSW Transport API error: Service unavailable",
  "statusCode": 502,
  "timestamp": "2024-11-06T10:30:00Z"
}
```

**When it happens:**
- NSW Transport API returns 4xx or 5xx status
- NSW Transport API is down
- NSW Transport API returns invalid data

#### 503 Service Unavailable - Service temporarily down
```json
{
  "error": "Service Unavailable",
  "message": "Service is temporarily unavailable. Please try again later.",
  "statusCode": 503,
  "timestamp": "2024-11-06T10:30:00Z",
  "details": {
    "retryAfter": "300"
  }
}
```

**When it happens:**
- Server is in maintenance mode
- Server is overloaded
- Circuit breaker is open (after multiple upstream failures)

#### 504 Gateway Timeout - Upstream timeout
```json
{
  "error": "Gateway Timeout",
  "message": "Timeout waiting for NSW Transport API response",
  "statusCode": 504,
  "timestamp": "2024-11-06T10:30:00Z"
}
```

**When it happens:**
- NSW Transport API takes too long to respond
- Network timeout
- Upstream service is slow

---

## Client Implementation Guide

### Kotlin/Android Example

```kotlin
suspend fun getTripJourneys(origin: String, destination: String): Result<JourneyList> {
    return try {
        val response = httpClient.get("$baseUrl/api/v1/trip/plan-proto") {
            parameter("origin", origin)
            parameter("destination", destination)
        }

        when (response.status.value) {
            200 -> {
                // Success - parse protobuf
                val journeyList = JourneyList.ADAPTER.decode(response.body<ByteArray>())
                Result.success(journeyList)
            }
            in 400..499 -> {
                // Client error - parse JSON error
                val errorResponse = response.body<ErrorResponse>()
                Result.failure(ClientException(errorResponse))
            }
            in 500..599 -> {
                // Server error - parse JSON error
                val errorResponse = response.body<ErrorResponse>()
                Result.failure(ServerException(errorResponse))
            }
            else -> {
                Result.failure(UnknownException("Unexpected status: ${response.status}"))
            }
        }
    } catch (e: Exception) {
        Result.failure(NetworkException(e))
    }
}
```

### Swift/iOS Example

```swift
func getTripJourneys(origin: String, destination: String) async throws -> JourneyList {
    let url = URL(string: "\(baseURL)/api/v1/trip/plan-proto?origin=\(origin)&destination=\(destination)")!
    let (data, response) = try await URLSession.shared.data(from: url)
    
    guard let httpResponse = response as? HTTPURLResponse else {
        throw NetworkError.invalidResponse
    }
    
    switch httpResponse.statusCode {
    case 200:
        // Success - decode protobuf
        return try JourneyList(serializedData: data)
        
    case 400...499:
        // Client error - decode JSON
        let errorResponse = try JSONDecoder().decode(ErrorResponse.self, from: data)
        throw APIError.clientError(errorResponse)
        
    case 500...599:
        // Server error - decode JSON
        let errorResponse = try JSONDecoder().decode(ErrorResponse.self, from: data)
        throw APIError.serverError(errorResponse)
        
    default:
        throw APIError.unknown(statusCode: httpResponse.statusCode)
    }
}
```

### JavaScript/TypeScript Example

```typescript
async function getTripJourneys(origin: string, destination: string): Promise<JourneyList> {
    const response = await fetch(
        `${baseUrl}/api/v1/trip/plan-proto?origin=${origin}&destination=${destination}`
    );
    
    if (response.ok) {
        // Success - decode protobuf
        const buffer = await response.arrayBuffer();
        return JourneyList.decode(new Uint8Array(buffer));
    } else {
        // Error - parse JSON
        const errorResponse: ErrorResponse = await response.json();
        
        if (response.status >= 400 && response.status < 500) {
            throw new ClientError(errorResponse);
        } else if (response.status >= 500) {
            throw new ServerError(errorResponse);
        } else {
            throw new UnknownError(errorResponse);
        }
    }
}
```

---

## Best Practices

### For Clients

1. **Always check HTTP status code first** before parsing the response body
2. **Parse as JSON for all non-2xx responses** (errors)
3. **Parse as protobuf only for 200 OK** (success)
4. **Implement retry logic** for 5xx errors and 429 (Too Many Requests)
5. **Show user-friendly messages** based on error types:
   - 4xx: Show validation errors to user
   - 5xx: Show "Please try again later" message
   - 502/504: Show "Service temporarily unavailable"

### Error Message Display

- **400-404**: Display `message` field directly to user (it's actionable)
- **429**: Display retry message with time from `details.retryAfter`
- **500-504**: Display generic "Something went wrong" message
- **502**: Display "Service temporarily unavailable, please try again"

### Retry Strategy

```
| Status Code | Retry? | Strategy                          |
|-------------|--------|-----------------------------------|
| 400-404     | No     | Fix request parameters            |
| 429         | Yes    | Wait for retryAfter seconds       |
| 500         | Yes    | Exponential backoff (max 3 times) |
| 502-504     | Yes    | Exponential backoff (max 3 times) |
```

---

## Testing Error Scenarios

### Test invalid parameters
```bash
curl -i "http://localhost:8080/api/v1/trip/plan-proto"
# Expected: 400 Bad Request (missing origin)
```

### Test successful request
```bash
curl -i "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120"
# Expected: 200 OK with protobuf binary data
```

### Test upstream API error
```bash
# Set invalid NSW_API_KEY in local.properties
curl -i "http://localhost:8080/api/v1/trip/plan-proto?origin=10101100&destination=10101120"
# Expected: 502 Bad Gateway
```

---

## Summary

✅ **DO** use protobuf for successful data responses (200 OK)
✅ **DO** use JSON for all error responses (4xx, 5xx)
✅ **DO** include detailed error messages and status codes
✅ **DO** use standard HTTP status codes correctly

❌ **DON'T** use protobuf for errors
❌ **DON'T** return 200 OK with an error message inside
❌ **DON'T** expose internal error details in production

