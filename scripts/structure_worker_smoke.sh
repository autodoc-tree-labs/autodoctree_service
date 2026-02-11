#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${STRUCTURE_WORKER_BASE_URL:-http://localhost:18081}"

log() {
  printf '[structure-worker-smoke] %s\n' "$1"
}

fail() {
  printf '[structure-worker-smoke][ERROR] %s\n' "$1" >&2
  exit 1
}

log "Checking structure worker runtime: ${BASE_URL}"
attempt=0
until curl -fsS "${BASE_URL}/health" >/tmp/structure_worker_health.json 2>/tmp/structure_worker_health.err; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 20 ]; then
    fail "Structure worker is unreachable. Start with: docker compose --profile structure up -d structure-worker"
  fi
  sleep 1
done

if ! rg -q '"status"\s*:\s*"ok"' /tmp/structure_worker_health.json; then
  fail "Health response is invalid"
fi

log "Calling /v1/structure/infer"
if ! curl -fsS "${BASE_URL}/v1/structure/infer" \
  -H 'Content-Type: application/json' \
  -d '{"documents":[{"id":"d1","title":"회계"},{"id":"d2","title":"결산"},{"id":"d3","title":"축구"}],"edges":[{"left":"d1","right":"d2","weight":0.9},{"left":"d1","right":"d3","weight":0.2}],"max_depth":3}' >/tmp/structure_worker_infer.json 2>/tmp/structure_worker_infer.err; then
  fail "Infer request failed"
fi

if ! rg -q '"clusters"' /tmp/structure_worker_infer.json; then
  fail "Infer response is invalid"
fi

log "Smoke check completed successfully"
