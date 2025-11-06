#!/bin/bash

# Test script for the proto endpoint
# This demonstrates how the server responds with protobuf on success and JSON on errors

BASE_URL="http://localhost:8080"
ENDPOINT="/api/v1/trip/plan-proto"

echo "========================================"
echo "KRAIL BFF Proto Endpoint Test"
echo "========================================"
echo ""

# Test 1: Missing origin parameter (should return 400 JSON error)
echo "Test 1: Missing 'origin' parameter"
echo "Expected: 400 Bad Request (JSON)"
echo "----------------------------------------"
curl -i "${BASE_URL}${ENDPOINT}?destination=10101120" 2>/dev/null | head -20
echo ""
echo ""

# Test 2: Missing destination parameter (should return 400 JSON error)
echo "Test 2: Missing 'destination' parameter"
echo "Expected: 400 Bad Request (JSON)"
echo "----------------------------------------"
curl -i "${BASE_URL}${ENDPOINT}?origin=10101100" 2>/dev/null | head -20
echo ""
echo ""

# Test 3: Valid request (should return 200 with protobuf binary)
echo "Test 3: Valid request with both parameters"
echo "Expected: 200 OK (Content-Type: application/protobuf)"
echo "----------------------------------------"
curl -i "${BASE_URL}${ENDPOINT}?origin=10101100&destination=10101120" 2>/dev/null | head -20
echo ""
echo "Note: Binary protobuf data shown above (will look garbled)"
echo ""
echo ""

# Test 4: Check server logs for detailed journey information
echo "Test 4: Making request and checking server logs"
echo "Expected: Server logs should show parsed journey details"
echo "----------------------------------------"
echo "Making request..."
curl -s "${BASE_URL}${ENDPOINT}?origin=10101100&destination=10101120" > /tmp/journey_response.bin
echo "✅ Response saved to /tmp/journey_response.bin ($(wc -c < /tmp/journey_response.bin) bytes)"
echo ""
echo "👀 Check your server logs for detailed journey information!"
echo ""

echo "========================================"
echo "Tests Complete"
echo "========================================"
echo ""
echo "Summary:"
echo "  - Errors (4xx, 5xx) → JSON responses"
echo "  - Success (200) → Protobuf binary data"
echo "  - Server logs → Detailed journey info"
echo ""
echo "See docs/ERROR_HANDLING.md for complete documentation"

