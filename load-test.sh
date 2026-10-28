#!/bin/bash
# SmartDialer Load Test Script (Bash / cURL)
BASE_URL=${1:-"http://localhost:8080"}
ITERATIONS=${2:-50}

echo "=========================================================="
echo "  SmartDialer Load & Concurrency Benchmark (Bash)"
echo "  Target: $BASE_URL | Iterations: $ITERATIONS"
echo "=========================================================="

# Check health
HEALTH=$(curl -s "$BASE_URL/api/providers/health")
echo "[OK] Providers response: $HEALTH"

# Create Campaign
CAMP_RESP=$(curl -s -X POST "$BASE_URL/api/campaigns" \
  -H "Content-Type: application/json" \
  -d '{"name": "Bash-Benchmark", "dialingMode": "PREDICTIVE", "activeProvider": "MOCK_PROVIDER_A", "targetAnswerRate": 0.35}')
CAMP_ID=$(echo $CAMP_RESP | grep -o '"id":[0-9]*' | head -1 | cut -d':' -f2)

echo "[SETUP] Created Campaign #$CAMP_ID"

# Seed Agents & Borrowers
echo "[SETUP] Seeding agents and borrowers..."
for i in {1..20}; do
  curl -s -X POST "$BASE_URL/api/agents" -H "Content-Type: application/json" \
    -d "{\"agentExternalId\": \"sh-agent-$i\", \"name\": \"Agent $i\", \"state\": \"AVAILABLE\"}" > /dev/null
  curl -s -X POST "$BASE_URL/api/campaigns/$CAMP_ID/borrowers" -H "Content-Type: application/json" \
    -d "{\"borrowerExternalId\": \"sh-b-$i\", \"name\": \"Borrower $i\", \"phoneNumber\": \"+155500000$i\", \"priority\": 1}" > /dev/null
done

echo "[RUN] Executing $ITERATIONS dial cycles..."
START_TIME=$(date +%s)
SUCCESS=0

for i in $(seq 1 $ITERATIONS); do
  RESP=$(curl -s -X POST "$BASE_URL/api/campaigns/$CAMP_ID/dial-cycle")
  if [[ $RESP == *"safetyDecision"* ]]; then
    SUCCESS=$((SUCCESS + 1))
  fi
done

END_TIME=$(date +%s)
DIFF=$((END_TIME - START_TIME))
[ $DIFF -eq 0 ] && DIFF=1
RPS=$((SUCCESS / DIFF))

echo "=========================================================="
echo "  Total Cycles: $SUCCESS in ${DIFF}s (${RPS} req/s)"
echo "=========================================================="
