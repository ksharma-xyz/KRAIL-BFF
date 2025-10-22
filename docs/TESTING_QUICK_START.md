# Testing the NSW Transport API Integration

## Setup

1. **Set your NSW API Key** (required):
   ```bash
   export NSW_API_KEY="your-api-key-here"
   ```

2. **Optional: Enable Debug Logging** (to see detailed request/response logs):
   ```bash
   export LOG_LEVEL="DEBUG"
   ```

## Running the Server

### Development Mode (with debug logging)
```bash
cd /Users/ksharma/code/apps/KRAIL-BFF
LOG_LEVEL=DEBUG ./gradlew :server:run
```

### Production Mode (INFO level logging)
```bash
./gradlew :server:run
```

## Testing the Trip Planning API

### Example 1: Basic Trip from Central to Circular Quay
```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328"
```

### Example 2: Trip with Departure Time
```bash
# Date format: YYYYMMDD, Time format: HHmm
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328&date=20251023&time=0900"
```

### Example 3: Arrival Time Mode
```bash
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328&depArr=arr&time=1700"
```

### Example 4: Exclude Transport Modes
```bash
# Exclude Train (1) and Bus (5)
curl "http://localhost:8080/api/v1/trip/plan?origin=10101100&destination=10101328&excludedModes=1,5"
```

### Transport Mode IDs
- **1**: Train
- **2**: Metro
- **4**: Light Rail
- **5**: Bus
- **7**: Coach
- **9**: Ferry
- **11**: School Bus

## Common Stop IDs for Testing

- **Central Station**: 10101100
- **Circular Quay**: 10101328
- **Town Hall**: 10101121
- **Wynyard**: 10101122
- **Martin Place**: 10101123
- **Parramatta**: 10102027
- **Bondi Junction**: 10101331

## Viewing Logs

The server uses structured JSON logging. When `LOG_LEVEL=DEBUG` is set, you'll see:

1. **Request Details**: Origin, destination, parameters
2. **Response Summary**: Number of journeys found, any errors
3. **API Errors**: If the NSW API returns an error
4. **Metrics**: Success/error counters and timing information

### Example Debug Log Entry
```json
{
  "@timestamp": "2025-10-22T15:41:05.955586+11:00",
  "message": "Requesting trip from NSW API - origin: 10101100, destination: 10101328, depArr: dep, date: null, time: null, excludedModes: []",
  "logger_name": "app.krail.bff.client.nsw.NswClientImpl",
  "level": "DEBUG"
}
```

## Configuration

All configuration can be set via environment variables or `application.yaml`:

### Environment Variables
- `NSW_API_KEY`: Your NSW Transport API key (required)
- `NSW_BASE_URL`: API base URL (default: https://api.transport.nsw.gov.au)
- `LOG_LEVEL`: Logging level - DEBUG for dev, INFO for prod (default: INFO)
- `NSW_CONNECT_TIMEOUT_MS`: Connection timeout (default: 10000)
- `NSW_READ_TIMEOUT_MS`: Read timeout (default: 10000)

## Logging Best Practices

The implementation follows industry best practices:

1. **Structured JSON Logging**: All logs are in JSON format for easy parsing and analysis
2. **Environment-Based Log Levels**:
    - **Production (INFO)**: Only logs warnings, errors, and important events
    - **Development (DEBUG)**: Includes detailed request/response information
3. **No Sensitive Data**: API keys and sensitive information are not logged
4. **Response Summarization**: Large responses are summarized (journey count, error status) rather than logged in full
5. **Metrics Tracking**: Success/error rates and timing are tracked via Dropwizard metrics

## Troubleshooting

### No API Key Error
If you see authentication errors, make sure `NSW_API_KEY` is set:
```bash
export NSW_API_KEY="your-key-here"
```

### Circuit Breaker Open
If the NSW API is down, the circuit breaker will open after 3 failures. It will automatically reset after 60 seconds.

### No Journeys Found
Check that:
- Stop IDs are valid
- Date/time format is correct (YYYYMMDD / HHmm)
- The route exists between the specified stops

