# Trip Planning API Guide

Complete guide to testing and using the Trip Planning API endpoints.

## Overview

The Trip Planning API allows you to plan journeys across the NSW transport network including trains, buses, ferries, and light rail.

**Base URL**: `http://localhost:8080` (local development)

## Endpoints

### GET /api/v1/trip/plan

Plan a trip between two locations.

#### Query Parameters

| Parameter | Type | Required | Description | Example |
|-----------|------|----------|-------------|---------|
| `origin` | string | ✅ Yes | Origin stop/station ID | `10101100` |
| `destination` | string | ✅ Yes | Destination stop/station ID | `10101328` |
| `depArr` | string | No | Departure (`dep`) or arrival (`arr`) time mode | `dep` (default) |
| `date` | string | No | Date in YYYYMMDD format | `20251023` |
| `time` | string | No | Time in HHmm format | `0900` |
| `excludedModes` | string | No | Comma-separated transport mode IDs to exclude | `1,5` |

#### Transport Mode IDs

| ID | Mode | Description |
|----|------|-------------|
| `1` | Train | Sydney Trains, Intercity Trains, Regional Trains |
| `2` | Metro | Sydney Metro |
| `4` | Light Rail | Sydney Light Rail, Newcastle Light Rail |
| `5` | Bus | Sydney Buses, Regional Buses |
| `7` | Coach | Regional Coaches |
| `9` | Ferry | Sydney Ferries, Newcastle Ferries, Private Ferries |
| `11` | School Bus | School Buses |

## Examples

### Basic Trip Planning

Get trips from Central Station to Circular Quay:

```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328"
```

**Response** (abbreviated):
```json
{
  "version": "10.2.1.42",
  "journeys": [
    {
      "legs": [
        {
          "origin": {
            "name": "Central Station",
            "departureTimePlanned": "2025-10-22T09:15:00Z",
            "departureTimeEstimated": "2025-10-22T09:15:00Z"
          },
          "destination": {
            "name": "Circular Quay",
            "arrivalTimePlanned": "2025-10-22T09:25:00Z",
            "arrivalTimeEstimated": "2025-10-22T09:25:00Z"
          },
          "transportation": {
            "number": "T4",
            "name": "Eastern Suburbs & Illawarra Line",
            "product": {
              "class": 1,
              "name": "Train"
            }
          },
          "duration": 600,
          "distance": 3500
        }
      ]
    }
  ]
}
```

### Trip with Specific Departure Time

Plan a trip departing at 9:00 AM on October 23, 2025:

```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328&date=20251023&time=0900"
```

### Trip with Arrival Time

Plan a trip arriving at 5:00 PM:

```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328&depArr=arr&time=1700"
```

### Exclude Transport Modes

Get trips without trains or buses:

```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328&excludedModes=1,5"
```

### Pretty Print JSON Response

Using `jq`:

```bash
curl -s "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328" | jq
```

## Common Stop IDs

### Sydney CBD

| Location | Stop ID |
|----------|---------|
| Central Station | `10101100` |
| Town Hall | `10101121` |
| Wynyard | `10101122` |
| Martin Place | `10101123` |
| Circular Quay | `10101328` |
| Kings Cross | `10101331` |
| Bondi Junction | `10101350` |

### Major Stations

| Location | Stop ID |
|----------|---------|
| Parramatta | `10102027` |
| Chatswood | `10101339` |
| Strathfield | `10101266` |
| Redfern | `10101107` |
| North Sydney | `10101341` |

### Airports

| Location | Stop ID |
|----------|---------|
| International Airport | `10101318` |
| Domestic Airport | `10101317` |

## Response Fields

### Journey Object

```typescript
{
  legs: [
    {
      origin: StopSequence,           // Starting point
      destination: StopSequence,      // Ending point
      transportation: Transportation, // Vehicle information
      duration: number,               // Journey time in seconds
      distance: number,               // Distance in meters
      stopSequence: [StopSequence],   // All stops on this leg
      isRealtimeControlled: boolean,  // Real-time data available
      hints: [Hint],                  // Additional information
      infos: [Info],                  // Service alerts
      footPathInfo: [FootPathInfo],   // Walking directions
      interchange: Interchange        // Connection details
    }
  ]
}
```

### StopSequence Object

```typescript
{
  name: string,                    // Full location name
  disassembledName: string,        // Short name
  id: string,                      // Stop ID
  type: string,                    // Location type (stop, platform, etc.)
  departureTimePlanned: string,    // Scheduled departure (ISO 8601)
  departureTimeEstimated: string,  // Real-time departure (ISO 8601)
  arrivalTimePlanned: string,      // Scheduled arrival (ISO 8601)
  arrivalTimeEstimated: string,    // Real-time arrival (ISO 8601)
  properties: {
    wheelchairAccess: string,      // Accessibility info
    platform: string,              // Platform number
    occupancy: string              // Vehicle occupancy (MANY_SEATS, STANDING_ONLY, etc.)
  }
}
```

### Transportation Object

```typescript
{
  number: string,          // Route number (e.g., "T4", "333")
  name: string,           // Full route name
  disassembledName: string, // Short route name
  description: string,    // Route description
  product: {
    class: number,        // Transport mode ID
    name: string,         // Transport mode name
    iconID: number       // Icon identifier
  },
  destination: {
    name: string,         // Route destination
    id: string           // Destination ID
  },
  operator: {
    name: string         // Operating company
  }
}
```

## Testing Scenarios

### Morning Commute

```bash
# From Parramatta to Central, arriving by 9:00 AM
curl "http://localhost:8080/api/v1/trip/plan?origin=10102027&destination=10101100&depArr=arr&time=0900"
```

### Airport Trip

```bash
# From Central to International Airport
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101318"
```

### Ferry Only

```bash
# Circular Quay to Manly (exclude all except ferry)
curl "http://localhost:8080/api/v1/trip/plan?origin=10101328&destination=10103000&excludedModes=1,2,4,5,7,11"
```

### Accessible Journey

Check the `wheelchairAccess` property in the response for accessibility information:

```bash
curl -s "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328" | \
  jq '.journeys[0].legs[].stopSequence[].properties.wheelchairAccess'
```

## Error Handling

### 400 Bad Request

Missing required parameters:

```json
{
  "error": "Missing 'origin' parameter"
}
```

### 500 Internal Server Error

API or server errors:

```json
{
  "correlationId": "123e4567-e89b-12d3-a456-426614174000",
  "timestamp": "2025-10-22T09:15:00.000Z",
  "path": "/api/v1/trip/plan",
  "message": "Internal server error"
}
```

### NSW API Error

When the NSW Transport API returns an error:

```json
{
  "error": {
    "message": "No journeys found",
    "versions": {
      "controller": "10.2.1.42"
    }
  }
}
```

## Best Practices

### Rate Limiting

- Be mindful of API rate limits
- Cache responses when appropriate
- Use the circuit breaker (automatically enabled)

### Date/Time Handling

- Always use YYYYMMDD format for dates
- Always use HHmm (24-hour) format for times
- Times are in local Sydney time (AEDT/AEST)

### Stop ID Discovery

To find stop IDs:
1. Use the NSW Transport website
2. Use the TripView app
3. Use the stop_finder API endpoint (if implemented)

### Testing Multiple Scenarios

Create a test script:

```bash
#!/bin/bash

BASE_URL="http://localhost:8080/api/v1/trip/plan"

# Test cases
echo "Test 1: Basic trip"
curl -s "$BASE_URL?origin=10101100&destination=10101328" | jq '.journeys | length'

echo "Test 2: With time"
curl -s "$BASE_URL?origin=10101100&destination=10101328&time=0900" | jq '.journeys | length'

echo "Test 3: Exclude trains"
curl -s "$BASE_URL?origin=10101100&destination=10101328&excludedModes=1" | jq '.journeys | length'
```

## Next Steps

- [Local Development Guide](LOCAL_DEVELOPMENT.md) - Setup and run locally
- [Debugging Guide](DEBUGGING.md) - Debug API issues
- [NSW Transport API Docs](https://opendata.transport.nsw.gov.au/documentation) - Official documentation

