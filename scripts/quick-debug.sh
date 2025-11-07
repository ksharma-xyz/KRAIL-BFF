#!/bin/bash

# Quick endpoint test for KRAIL BFF
# Usage: ./scripts/quick-debug.sh

BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "=========================================="
echo "🔍 KRAIL BFF Quick Test"
echo "=========================================="
echo ""

# Test 1: Legacy Android endpoint (protobuf)
echo "1️⃣  Testing: /v1/tp/trip (Legacy Android - Protobuf)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
curl -s -i "$BASE_URL/v1/tp/trip?name_origin=200070&name_destination=200060&depArrMacro=dep&excludedMeans=checkbox" \
  | head -20
echo ""
echo ""

# Test 2: New JSON endpoint
echo "2️⃣  Testing: /api/v1/trip/plan (JSON)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
curl -s "$BASE_URL/api/v1/trip/plan?origin=200070&destination=200060" \
  | jq '{version, journey_count: (.journeys | length)}'
echo ""

# Test 3: New protobuf endpoint
echo "3️⃣  Testing: /api/v1/trip/plan-proto (Protobuf)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
curl -s -i "$BASE_URL/api/v1/trip/plan-proto?origin=200070&destination=200060" \
  | head -15
echo ""
echo ""

# Test 4: Size comparison
echo "4️⃣  Size Comparison"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
JSON_SIZE=$(curl -s -w "%{size_download}" -o /dev/null "$BASE_URL/api/v1/trip/plan?origin=200070&destination=200060")
PROTO_SIZE=$(curl -s -w "%{size_download}" -o /dev/null "$BASE_URL/api/v1/trip/plan-proto?origin=200070&destination=200060")

echo "JSON:     $JSON_SIZE bytes"
echo "Protobuf: $PROTO_SIZE bytes"

if [ "$JSON_SIZE" -gt 0 ] && [ "$PROTO_SIZE" -gt 0 ]; then
    SAVINGS=$((JSON_SIZE - PROTO_SIZE))
    PERCENT=$(awk "BEGIN {printf \"%.1f\", ($SAVINGS/$JSON_SIZE) * 100}")
    echo "Savings:  $SAVINGS bytes ($PERCENT% smaller) ✅"
fi
echo ""

# Test 5: Error handling
echo "5️⃣  Testing Error Handling (missing params)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
curl -s "$BASE_URL/v1/tp/trip" | jq
echo ""

echo "=========================================="
echo "✅ Tests Complete!"
echo "=========================================="
echo ""
echo "💡 Quick Tips:"
echo "   • Server is working if you see HTTP 200 and protobuf data above"
echo "   • Android app gets protobuf (binary) not JSON"
echo "   • Use 'jq' to view JSON responses nicely"
echo ""
echo "📚 Debugging Docs:"
echo "   ./docs/guides/DEBUGGING.md"
echo "   https://ksharma-xyz.github.io/KRAIL-BFF/guides/DEBUGGING.html"

