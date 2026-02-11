#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${RERANKER_BASE_URL:-http://localhost:18080}"

log() {
  printf '[reranker-smoke] %s\n' "$1"
}

fail() {
  printf '[reranker-smoke][ERROR] %s\n' "$1" >&2
  exit 1
}

log "Checking reranker runtime: ${BASE_URL}"
attempt=0
until curl -fsS "${BASE_URL}/health" >/tmp/reranker_health.json 2>/tmp/reranker_health.err; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 20 ]; then
    fail "Reranker is unreachable. Start with: docker compose --profile ml up -d reranker-api"
  fi
  sleep 1
done

if ! rg -q '"status"\s*:\s*"ok"' /tmp/reranker_health.json; then
  fail "Health response is invalid"
fi

log "Calling /v1/rerank/pairs"
if ! curl -fsS "${BASE_URL}/v1/rerank/pairs" \
  -H 'Content-Type: application/json' \
  -d '{"pairs":[{"pair_key":"a::b","left_text":"회계 결산 보고서 승인","right_text":"재무 결산 승인 문서"}]}' >/tmp/reranker_pairs.json 2>/tmp/reranker_pairs.err; then
  fail "Rerank request failed"
fi

if ! rg -q '"items"' /tmp/reranker_pairs.json; then
  fail "Rerank response is invalid"
fi
if ! rg -q '"score"' /tmp/reranker_pairs.json; then
  fail "Rerank response missing score"
fi

log "Smoke check completed successfully"
