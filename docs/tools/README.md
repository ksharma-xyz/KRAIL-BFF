# Browser API Tester

Interactive HTML tool for testing KRAIL BFF endpoints in your browser.

## 📍 Location

`docs/tools/api-tester.html`

## 🚀 Quick Start

### Option 1: Direct File Access (Simplest)

```bash
# From project root
open docs/tools/api-tester.html
```

**Requirements:**
- Server must be running: `./gradlew :server:run`
- CORS is enabled (already configured)

### Option 2: Serve via HTTP Server

If direct file access doesn't work, serve it via a local HTTP server:

```bash
# Python 3
cd docs/tools
python3 -m http.server 8000

# Then open: http://localhost:8000/api-tester.html
```

## 🎯 What It Does

The API Tester provides an interactive UI to:

- ✅ Test trip planning endpoints with real data
- ✅ View response statistics (size, time, journey count)
- ✅ Display journey details in beautiful cards
- ✅ See formatted JSON responses
- ✅ Debug connection issues with helpful error messages

## 📊 Features

### Visual Stats
- Journey count
- Response size (KB)
- Response time (ms)

### Journey Cards
- Transport mode and line
- Origin and destination stops
- Departure and arrival times
- Platform information

### Error Handling
- Clear error messages
- Troubleshooting steps
- Connection diagnostics

## 🧪 Testing Locally

### 1. Start the Server

```bash
./gradlew :server:run
```

**Wait for:** `Application started in X.XXX seconds`

### 2. Open the Tester

```bash
open docs/tools/api-tester.html
```

### 3. Expected Result

You should see:

```
✅ Success

┌──────────┬──────────┬──────────┐
│    7     │  20.4 KB │  245ms   │
│ Journeys │   Size   │   Time   │
└──────────┴──────────┴──────────┘

📋 Journeys
┌─────────────────────────────┐
│ Journey 1: T8               │
│ From: Central Station       │
│ To: Town Hall Station       │
│ Departs: 5:52 PM           │
│ Arrives: 5:54 PM           │
└─────────────────────────────┘
```

## 🔧 Customizing Tests

You can modify the test parameters in the UI:

- **Origin Stop ID**: Default `200060` (Central Station)
- **Destination Stop ID**: Default `200070` (Town Hall Station)
- **Endpoint URL**: Automatically set to `http://localhost:8080/v1/tp/trip`

### Common Stop IDs

#### Sydney CBD
- Central Station: `200060`
- Town Hall: `200070`
- Wynyard: `200080`
- Circular Quay: `200020`
- Kings Cross: `201110`

#### Major Stations
- Parramatta: `215020`
- Chatswood: `206710`
- Bondi Junction: `202210`
- North Sydney: `206010`

#### Airports
- International: `202030`
- Domestic: `202020`

## 🐛 Troubleshooting

### Error: "Failed to fetch"

**Possible causes:**

1. **Server not running**
   ```bash
   # Check if server is up
   curl http://localhost:8080/health
   
   # Should return: {"status":"UP"}
   ```
   
   **Fix:** Start the server with `./gradlew :server:run`

2. **CORS not configured**
   - CORS is already configured in the server
   - Make sure you've restarted the server after the recent updates
   
   **Fix:** Restart server:
   ```bash
   # Kill existing server
   lsof -ti:8080 | xargs kill -9
   
   # Start fresh
   ./gradlew :server:run
   ```

3. **Port mismatch**
   - Ensure server is on port 8080
   - Check the endpoint URL in the tester

### Error: "Access-Control-Allow-Origin"

This means CORS headers are missing. The server should have CORS configured.

**Verify CORS is working:**

```bash
curl -v -X OPTIONS http://localhost:8080/v1/tp/trip \
  -H "Origin: http://localhost:63342" \
  -H "Access-Control-Request-Method: GET" \
  2>&1 | grep -i "Access-Control"
```

**Should show:**
```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS, PATCH
```

**If missing:** Restart the server to load the latest CORS configuration.

### Browser Console Errors

Open browser developer tools (F12 or Cmd+Option+I) and check the Console tab for detailed error messages.

## 📝 Technical Details

### Endpoint Being Tested

```
GET http://localhost:8080/v1/tp/trip
```

**Parameters:**
- `name_origin`: Origin stop ID
- `name_destination`: Destination stop ID
- `depArrMacro`: "dep" (departure time) or "arr" (arrival time)
- `excludedMeans`: "checkbox" (no exclusions)

**Response Format:** JSON (~20 KB)

### CORS Configuration

The server is configured to allow:
- All origins (`anyHost()`)
- Methods: GET, POST, PUT, DELETE, OPTIONS, PATCH
- Headers: Content-Type, Authorization, Accept, X-Request-Id
- Credentials: Allowed
- Preflight cache: 1 hour

**Security Note:** In production, restrict `anyHost()` to specific domains.

## 🎨 UI Components

### Header
- Title and description
- Status badge (Ready/Loading/Success/Error)

### Form Inputs
- Origin stop ID (editable)
- Destination stop ID (editable)
- Endpoint URL (read-only)

### Stats Panel
- Journey count
- Response size
- Response time

### Response Box
- Raw JSON response (formatted)
- Error messages with troubleshooting steps

### Journey Cards
- Visual representation of each journey
- Transport mode, times, and stops

## 🚀 Auto-Test Feature

The page automatically runs a test when loaded, providing instant feedback on server status.

**To disable auto-test:** Comment out this line in the HTML:

```javascript
// window.addEventListener('load', () => {
//     setTimeout(testEndpoint, 500);
// });
```

## 📚 Related Documentation

- [API Documentation](../api/TRIP_PLANNING_API.md)
- [Local Development Guide](../guides/LOCAL_DEVELOPMENT.md)
- [Debugging Guide](../guides/DEBUGGING.md)
- [Android Integration](../guides/ANDROID_INTEGRATION.md)

## 🔗 Endpoints

All endpoints are accessible from the browser:

```
✅ GET /health                              → Health check
✅ GET /v1/tp/trip                         → JSON (20 KB) - Legacy
✅ GET /api/v1/trip/plan                   → JSON (23 KB) - New
⚠️  GET /api/v1/trip/plan-proto             → Protobuf (7 KB) - Binary data
```

**Note:** Protobuf endpoints return binary data which browsers can't display directly. Use the JSON endpoints for browser testing.

## 💡 Tips

1. **Keep server running** - The tester needs a live server
2. **Use JSON endpoints** - Protobuf endpoints return binary data
3. **Check browser console** - For detailed error messages
4. **Test with curl first** - To verify server is responding
5. **Refresh after server restart** - To get latest changes

## 🎯 Success Indicators

✅ Status badge shows "Success" (green)  
✅ Stats panel displays journey count  
✅ Journey cards appear below  
✅ Response time is reasonable (<1s)  
✅ No errors in browser console  

---

**Happy Testing!** 🚂

