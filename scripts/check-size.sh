#!/bin/bash

# Simple script to check and compare response sizes

BASE_URL="http://localhost:8080"
ORIGIN="${1:-10101100}"
DESTINATION="${2:-10101120}"

echo "Testing: $ORIGIN → $DESTINATION"
echo ""

# Test JSON
echo "1. JSON Response:"
curl -s "${BASE_URL}/api/v1/trip/plan?origin=${ORIGIN}&destination=${DESTINATION}" \
  -o /tmp/trip.json -w "   Size: %{size_download} bytes (%{size_download} / 1024 = %{size_download} KB)\n   Status: %{http_code}\n"

JSON_SIZE=$(stat -f%z /tmp/trip.json 2>/dev/null || stat -c%s /tmp/trip.json 2>/dev/null)
echo "   File: /tmp/trip.json (${JSON_SIZE} bytes = $(echo "scale=2; $JSON_SIZE/1024" | bc 2>/dev/null || echo "?") KB)"

if command -v jq &> /dev/null; then
    JOURNEY_COUNT=$(jq '.journeys | length' /tmp/trip.json 2>/dev/null || echo "?")
    echo "   Journeys: ${JOURNEY_COUNT}"
fi

echo ""

# Test Proto
echo "2. Protobuf Response:"
curl -s "${BASE_URL}/api/v1/trip/plan-proto?origin=${ORIGIN}&destination=${DESTINATION}" \
  -o /tmp/trip.proto -w "   Size: %{size_download} bytes\n   Status: %{http_code}\n"

PROTO_SIZE=$(stat -f%z /tmp/trip.proto 2>/dev/null || stat -c%s /tmp/trip.proto 2>/dev/null)
echo "   File: /tmp/trip.proto (${PROTO_SIZE} bytes = $(echo "scale=2; $PROTO_SIZE/1024" | bc 2>/dev/null || echo "?") KB)"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Summary:"

if [ -n "$JSON_SIZE" ] && [ -n "$PROTO_SIZE" ] && [ "$JSON_SIZE" -gt 0 ] && [ "$PROTO_SIZE" -gt 0 ]; then
    SAVINGS=$((100 - (PROTO_SIZE * 100 / JSON_SIZE)))
    SAVED_BYTES=$((JSON_SIZE - PROTO_SIZE))

    echo "  JSON:     ${JSON_SIZE} bytes ($(echo "scale=1; $JSON_SIZE/1024" | bc) KB)"
    echo "  Protobuf: ${PROTO_SIZE} bytes ($(echo "scale=1; $PROTO_SIZE/1024" | bc) KB)"
    echo "  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Saved:    ${SAVED_BYTES} bytes ($(echo "scale=1; $SAVED_BYTES/1024" | bc) KB)"
    echo "  Reduction: ${SAVINGS}%"
    echo ""
    echo "  🎉 Protobuf is ${SAVINGS}% smaller!"
else
    echo "  ⚠️  Could not calculate sizes"
fi

echo ""
echo "To view the JSON response:"
echo "  cat /tmp/trip.json | jq ."
echo ""
echo "To view journey count:"
echo "  cat /tmp/trip.json | jq '.journeys | length'"

