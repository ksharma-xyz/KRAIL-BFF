# Trip Planning API

## Endpoint

**GET** `/api/v1/trip/plan`

### Parameters

| Parameter | Required | Description | Example |
|-----------|----------|-------------|---------|
| `origin` | ✅ | Origin stop ID | `10101100` |
| `destination` | ✅ | Destination stop ID | `10101328` |
| `depArr` | - | `dep` (default) or `arr` | `dep` |
| `date` | - | YYYYMMDD format | `20251023` |
| `time` | - | HHmm format (24h) | `0900` |
| `excludedModes` | - | Comma-separated mode IDs | `1,5` |

### Transport Modes

| ID | Mode |
|----|------|
| 1 | Train |
| 2 | Metro |
| 4 | Light Rail |
| 5 | Bus |
| 7 | Coach |
| 9 | Ferry |
| 11 | School Bus |

## Examples

```bash
# Basic trip
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020"

# With departure time (9 AM, Oct 23)
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020&date=20251023&time=0900"

# Arrival time mode (arrive by 5 PM)
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020&depArr=arr&time=1700"

# Exclude trains and buses
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020&excludedModes=1,5"

# Pretty print with jq
curl "http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020" | jq
```

## Common Stop IDs

### Sydney CBD
- Central Station: `200060`
- Town Hall: `200070`
- Wynyard: `200080`
- Circular Quay: `200020`
- Kings Cross: `201110`

### Major Stations
- Parramatta: `215020`
- Chatswood: `206710`
- Bondi Junction: `202210`
- North Sydney: `206010`

### Airports
- International: `202030`
- Domestic: `202020`

## Response Structure

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
            "departureTimeEstimated": "2025-10-22T09:16:00Z"
          },
          "destination": {
            "name": "Circular Quay",
            "arrivalTimePlanned": "2025-10-22T09:25:00Z"
          },
          "transportation": {
            "number": "T4",
            "name": "Eastern Suburbs Line",
            "product": { "class": 1, "name": "Train" }
          },
          "duration": 600,
          "distance": 3500
        }
      ]
    }
  ]
}
```

### Key Fields

- `departureTimeEstimated` - Real-time departure (if available)
- `departureTimePlanned` - Scheduled departure
- `duration` - Journey time in seconds
- `distance` - Distance in meters
- `transportation.number` - Route number (e.g., "T4", "333")
- `transportation.product.class` - Transport mode ID

## Error Responses

**400 Bad Request** - Missing parameters
```json
{ "error": "Missing 'origin' parameter" }
```

**401 Unauthorized** - Invalid API key

**500 Internal Server Error** - Server or API error

## Testing Tips

1. Use `jq` for readable output
2. Start with known working routes (Central to Circular Quay)
3. Check stop IDs are valid
4. Date/time must be in the future
5. Use the test script: `./scripts/test-trip-planning.sh`

**Full testing guide:** [TESTING_QUICK_START.md](TESTING_QUICK_START.md)
