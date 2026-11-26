#!/usr/bin/env bash
# Quick functional demo with curl (gateway URL as $1, default localhost:8080).
set -euo pipefail
BASE="${1:-http://localhost:8080}"
EMAIL="demo-$(date +%s)@demo.local"
PASS="DemoPass!123"

echo "== 1. gateway health =="
curl -s "$BASE/health" | head -c 300; echo

echo "== 2. register user $EMAIL =="
curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"displayName\":\"Demo User\"}"; echo

echo "== 3. login =="
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["token"])')
echo "token: ${TOKEN:0:40}..."

echo "== 4. ensure queue 'default' exists =="
QUEUE_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/queues/default/stats" \
  -H "Authorization: Bearer $TOKEN")
if [[ "$QUEUE_STATUS" == "404" ]]; then
  CREATE_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/queues" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"name":"default","description":"demo","maxConcurrency":16,"maxAttempts":5}')
  if [[ "$CREATE_STATUS" != "200" && "$CREATE_STATUS" != "409" ]]; then
    echo "Could not create queue 'default' (HTTP $CREATE_STATUS)" >&2
    exit 1
  fi
elif [[ "$QUEUE_STATUS" != "200" ]]; then
  echo "Could not inspect queue 'default' (HTTP $QUEUE_STATUS)" >&2
  exit 1
fi

echo "== 5. enqueue 3 messages (one with fail:true to show retry/DLQ) =="
for i in 1 2 3; do
  curl -s -X POST "$BASE/api/queues/default/messages" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: demo-$i" \
    -d "{\"payload\":\"{\\\"workMs\\\":80,\\\"n\\\":$i}\",\"priority\":$i}"; echo
done

echo "== 6. rate-limit headers on a normal request =="
curl -s -D - -o /dev/null "$BASE/api/queues/default/stats" -H "Authorization: Bearer $TOKEN" | grep -i x-ratelimit || true

echo "== 7. stats (worker should be draining the queue) =="
sleep 3
curl -s "$BASE/api/queues/default/stats" -H "Authorization: Bearer $TOKEN"; echo

echo "== 8. monitor dashboard =="
curl -s "$BASE/monitor/load"; echo
echo "Open $BASE/ in a browser for the live dashboard."
echo "Run scripts/attack_sim.py to see the per-IP limiter defeat an attack."
