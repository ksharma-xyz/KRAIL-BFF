#!/bin/bash

# KRAIL-BFF Trip Planning Test Script
# This script starts the server and tests the trip planning API

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Get the directory where the script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Log file location
LOG_FILE="/tmp/krail-server.log"
TEST_LOG_FILE="/tmp/krail-test.log"

echo -e "${BLUE}📝 Logs will be saved to:${NC}"
echo -e "   Server: $LOG_FILE"
echo -e "   Tests:  $TEST_LOG_FILE"
echo "" > "$TEST_LOG_FILE"

log_test() {
    echo "$1" | tee -a "$TEST_LOG_FILE"
}

echo -e "${BLUE}🔍 Checking if port 8080 is already in use...${NC}"
if lsof -ti:8080 > /dev/null 2>&1; then
    echo -e "${YELLOW}⚠️  Port 8080 is already in use!${NC}"
    echo "Killing existing process..."
    kill -9 $(lsof -ti:8080) 2>/dev/null || true
    sleep 2
fi

echo -e "${GREEN}🚀 Starting KRAIL-BFF Server...${NC}"
log_test "=== KRAIL-BFF Test Run - $(date) ==="
log_test ""

# Start the server in the background from the project root
cd "$PROJECT_ROOT"
./gradlew :server:run > "$LOG_FILE" 2>&1 &
SERVER_PID=$!

echo "Server PID: $SERVER_PID"
log_test "Server PID: $SERVER_PID"
echo -e "${BLUE}Waiting for server to start...${NC}"
echo -e "${CYAN}💡 To watch server logs: tail -f $LOG_FILE${NC}"

# Wait for server to be ready (check health endpoint)
MAX_WAIT=60
COUNTER=0
SERVER_READY=false

while [ $COUNTER -lt $MAX_WAIT ]; do
    if curl -s http://localhost:8080/health > /dev/null 2>&1; then
        SERVER_READY=true
        break
    fi
    sleep 1
    COUNTER=$((COUNTER + 1))
    if [ $((COUNTER % 5)) -eq 0 ]; then
        echo "Still waiting... ($COUNTER seconds)"
    fi
done

if [ "$SERVER_READY" = false ]; then
    echo -e "${RED}❌ Server failed to start within $MAX_WAIT seconds${NC}"
    log_test "❌ Server failed to start"
    echo "Last 100 lines of server log:"
    tail -100 "$LOG_FILE"
    kill -9 $SERVER_PID 2>/dev/null || true
    exit 1
fi

echo -e "${GREEN}✅ Server is ready!${NC}"
log_test "✅ Server started successfully"
echo ""

# Show configuration source
echo -e "${CYAN}🔧 Configuration Info:${NC}"
if grep -q "NSW API Key loaded successfully from:" "$LOG_FILE"; then
    config_source=$(grep "NSW API Key loaded successfully from:" "$LOG_FILE" | tail -1 | sed -n 's/.*"NSW API Key loaded successfully from: \([^"]*\)".*/\1/p')
    api_key_length=$(grep "API Key length:" "$LOG_FILE" | tail -1 | sed -n 's/.*"API Key length: \([^"]*\) chars".*/\1/p')
    base_url=$(grep "Base URL:" "$LOG_FILE" | tail -1 | sed -n 's/.*"Base URL: \([^"]*\)".*/\1/p')

    echo -e "   ${GREEN}✓${NC} API Key Source: ${MAGENTA}$config_source${NC}"
    if [ -n "$api_key_length" ]; then
        echo -e "   ${GREEN}✓${NC} API Key Length: $api_key_length chars"
    fi
    if [ -n "$base_url" ]; then
        echo -e "   ${GREEN}✓${NC} Base URL: $base_url"
    fi
else
    echo -e "   ${YELLOW}⚠${NC}  Could not determine configuration source from logs"
fi
echo ""

# Cleanup function
cleanup() {
    echo ""
    echo -e "${YELLOW}🛑 Stopping server (PID: $SERVER_PID)...${NC}"
    kill $SERVER_PID 2>/dev/null || true
    sleep 1
    echo -e "${GREEN}Server stopped.${NC}"
    echo ""
    echo -e "${BLUE}📊 Full logs available at:${NC}"
    echo -e "   Server: $LOG_FILE"
    echo -e "   Tests:  $TEST_LOG_FILE"
}

trap cleanup EXIT

# Run health check
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}✅ Testing Health Endpoint...${NC}"
log_test ""
log_test "=== Health Check ==="

health_response=$(curl -s http://localhost:8080/health)
log_test "Response: $health_response"

if [ -z "$health_response" ] || [ "$health_response" = "{}" ]; then
    echo -e "${YELLOW}⚠️  Health endpoint returned empty response (expected for this app)${NC}"
    echo "$health_response"
else
    echo "$health_response" | jq '.'
fi
echo ""

# Test trip planning API - ONE TEST ONLY
TEST_NAME="Central Station to Circular Quay"
TEST_URL="http://localhost:8080/api/v1/trip/plan?origin=200060&destination=200020"

echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━���━${NC}"
echo -e "${BLUE}🚆 Testing: $TEST_NAME${NC}"
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━���━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
log_test ""
log_test "=== $TEST_NAME ==="
log_test "URL: $TEST_URL"

echo -e "${CYAN}📤 Request URL:${NC}"
echo "   $TEST_URL"
echo ""

# Make the request
echo -e "${CYAN}🔄 Making API call...${NC}"
http_code=$(curl -s -o /tmp/api_response.json -w "%{http_code}" "$TEST_URL" 2>&1)
response=$(cat /tmp/api_response.json)

log_test "HTTP Status: $http_code"
log_test "Response Length: ${#response} bytes"

echo -e "${CYAN}📥 HTTP Status Code: ${NC}${MAGENTA}$http_code${NC}"
echo ""

# Check HTTP status
if [ "$http_code" != "200" ]; then
    echo -e "${RED}❌ Request failed with HTTP $http_code${NC}"
    echo -e "${YELLOW}Response Body:${NC}"
    echo "$response" | jq '.' 2>/dev/null || echo "$response"
    log_test "Result: FAILED (HTTP $http_code)"
    log_test "Response: $response"

    echo ""
    echo -e "${CYAN}💡 Checking server logs for NSW API details...${NC}"
    echo -e "${YELLOW}Last 50 lines of server log:${NC}"
    tail -50 "$LOG_FILE"

    exit 1
fi

# Validate JSON
if ! echo "$response" | jq -e . >/dev/null 2>&1; then
    echo -e "${RED}❌ Invalid JSON response${NC}"
    echo -e "${YELLOW}Raw Response (first 100 chars):${NC}"
    echo "$response" | head -c 100
    log_test "Result: FAILED (Invalid JSON)"
    exit 1
fi

echo -e "${GREEN}✓ Valid JSON response${NC}"
echo ""

# Analyze the response
journey_count=$(echo "$response" | jq '.journeys | length' 2>/dev/null || echo "0")
has_error=$(echo "$response" | jq '.error != null' 2>/dev/null || echo "false")

# Log summary to file (not the full response)
log_test "Journey Count: $journey_count"
log_test "Has Error: $has_error"

# Show response summary on console
echo -e "${CYAN}📊 Response Analysis:${NC}"
echo "   Journey Count: $journey_count"
echo "   Has Error: $has_error"
echo ""

# Only show full response if there's an error or no journeys
if [ "$journey_count" = "0" ] || [ "$journey_count" = "null" ] || [ "$has_error" = "true" ]; then
    echo -e "${MAGENTA}📦 Full API Response (showing because of error/no journeys):${NC}"
    echo "$response" | jq '.'
    echo ""
fi

if [ "$journey_count" = "0" ] || [ "$journey_count" = "null" ]; then
    echo ""
    echo -e "${YELLOW}⚠️  WARNING: No journeys found in response!${NC}"
    log_test "Result: SUCCESS (HTTP 200) but NO JOURNEYS"

    # Check for error in response
    error_msg=$(echo "$response" | jq -r '.error.message // "no error message"' 2>/dev/null)
    if [ "$error_msg" != "no error message" ] && [ "$error_msg" != "null" ]; then
        echo -e "${RED}   Error Message: $error_msg${NC}"
    fi

    echo ""
    echo -e "${CYAN}💡 Checking server logs for NSW API response details...${NC}"
    echo -e "${YELLOW}=== Server Log (last 100 lines) ===${NC}"
    tail -100 "$LOG_FILE" | grep -E "(🚀|📍|🔑|📥|📦|✅|⚠️|❌)" --color=never || tail -100 "$LOG_FILE"
else
    echo ""
    echo -e "${GREEN}✅ SUCCESS! Found $journey_count journey(s)${NC}"
    log_test "Result: SUCCESS ($journey_count journeys)"

    # Show first journey details
    echo ""
    echo -e "${MAGENTA}🎯 First Journey Details:${NC}"
    echo "$response" | jq '.journeys[0].legs[0] | {
        origin: .origin.name,
        destination: .destination.name,
        transportation: .transportation.name,
        departure: .origin.departureTimeEstimated,
        arrival: .destination.arrivalTimeEstimated,
        duration: .duration
    }' 2>/dev/null || echo "Could not extract journey details"
fi

echo ""
echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━���━━━━━━━���━${NC}"
echo -e "${GREEN}✅ Test completed!${NC}"
echo ""
echo -e "${CYAN}💡 To see detailed server logs with NSW API calls:${NC}"
echo -e "   tail -100 $LOG_FILE | grep -E '(🚀|📍|🔑|📥|📦|✅|⚠️|❌)'"
echo ""

read -p "Press Enter to stop the server and exit..."
