#!/bin/bash

# Script to compare JSON vs Protobuf response sizes

BASE_URL="http://localhost:8080"
ORIGIN="10101100"
DESTINATION="10101120"

echo "========================================"
echo "JSON vs Protobuf Size Comparison"
echo "========================================"
echo ""
echo "Testing endpoints..."
echo "Origin: $ORIGIN"
echo "Destination: $DESTINATION"
echo ""

# Test JSON endpoint
echo "1. Testing JSON endpoint..."
echo "   GET /api/v1/trip/plan"
JSON_RESPONSE=$(curl -s -w "\n%{http_code}\n%{size_download}" \
  "${BASE_URL}/api/v1/trip/plan?origin=${ORIGIN}&destination=${DESTINATION}")

JSON_HTTP_CODE=$(echo "$JSON_RESPONSE" | tail -2 | head -1)
JSON_SIZE=$(echo "$JSON_RESPONSE" | tail -1)
JSON_BODY=$(echo "$JSON_RESPONSE" | head -n -2)

if [ "$JSON_HTTP_CODE" = "200" ]; then
    echo "   ✅ Status: $JSON_HTTP_CODE OK"
    echo "   📦 Size: $JSON_SIZE bytes ($(echo "scale=2; $JSON_SIZE/1024" | bc) KB)"

    # Save to file
    echo "$JSON_BODY" > /tmp/trip_response.json
    JSON_FILE_SIZE=$(wc -c < /tmp/trip_response.json)
    echo "   💾 File: /tmp/trip_response.json ($JSON_FILE_SIZE bytes)"

    # Count journeys
    JOURNEY_COUNT=$(echo "$JSON_BODY" | grep -o '"legs"' | wc -l)
    echo "   🚊 Journeys: $JOURNEY_COUNT"
else
    echo "   ❌ Status: $JSON_HTTP_CODE"
    echo "$JSON_BODY"
fi

echo ""

# Test Protobuf endpoint
echo "2. Testing Protobuf endpoint..."
echo "   GET /api/v1/trip/plan-proto"
curl -s -w "\n%{http_code}\n%{size_download}" \
  "${BASE_URL}/api/v1/trip/plan-proto?origin=${ORIGIN}&destination=${DESTINATION}" \
  -o /tmp/trip_response.proto > /tmp/proto_stats.txt

PROTO_HTTP_CODE=$(tail -2 /tmp/proto_stats.txt | head -1)
PROTO_SIZE=$(tail -1 /tmp/proto_stats.txt)

if [ "$PROTO_HTTP_CODE" = "200" ]; then
    echo "   ✅ Status: $PROTO_HTTP_CODE OK"
    echo "   📦 Size: $PROTO_SIZE bytes ($(echo "scale=2; $PROTO_SIZE/1024" | bc) KB)"

    # Get actual file size
    PROTO_FILE_SIZE=$(wc -c < /tmp/trip_response.proto)
    echo "   💾 File: /tmp/trip_response.proto ($PROTO_FILE_SIZE bytes)"

    # Check content type
    CONTENT_TYPE=$(curl -s -I "${BASE_URL}/api/v1/trip/plan-proto?origin=${ORIGIN}&destination=${DESTINATION}" | grep -i "content-type" | cut -d' ' -f2 | tr -d '\r')
    echo "   📄 Content-Type: $CONTENT_TYPE"
else
    echo "   ❌ Status: $PROTO_HTTP_CODE"
    cat /tmp/trip_response.proto
fi

echo ""
echo "========================================"
echo "Comparison Summary"
echo "========================================"

if [ "$JSON_HTTP_CODE" = "200" ] && [ "$PROTO_HTTP_CODE" = "200" ]; then
    echo ""
    printf "%-20s %15s %15s\n" "Metric" "JSON" "Protobuf"
    echo "----------------------------------------"
    printf "%-20s %15s %15s\n" "Size (bytes)" "$JSON_SIZE" "$PROTO_SIZE"
    printf "%-20s %15s %15s\n" "Size (KB)" "$(echo "scale=2; $JSON_SIZE/1024" | bc)" "$(echo "scale=2; $PROTO_SIZE/1024" | bc)"

    # Calculate savings
    SAVINGS=$(echo "scale=2; 100 - ($PROTO_SIZE * 100 / $JSON_SIZE)" | bc)
    SAVED_BYTES=$(($JSON_SIZE - $PROTO_SIZE))

    echo "----------------------------------------"
    printf "%-20s %15s bytes\n" "Bytes Saved" "$SAVED_BYTES"
    printf "%-20s %15s%%\n" "Size Reduction" "$SAVINGS"

    echo ""
    echo "📊 Protobuf is ${SAVINGS}% smaller than JSON!"
    echo "💾 Saved: $SAVED_BYTES bytes ($(echo "scale=2; $SAVED_BYTES/1024" | bc) KB)"

    echo ""
    echo "Files saved:"
    echo "  JSON:     /tmp/trip_response.json ($(wc -c < /tmp/trip_response.json) bytes)"
    echo "  Protobuf: /tmp/trip_response.proto ($(wc -c < /tmp/trip_response.proto) bytes)"
    echo ""
    echo "View JSON:"
    echo "  cat /tmp/trip_response.json | jq '.journeys | length'"
    echo "  cat /tmp/trip_response.json | jq '.journeys[0]' | head -20"
else
    echo ""
    echo "❌ One or both requests failed. Check the output above."
fi

echo ""
echo "========================================"

