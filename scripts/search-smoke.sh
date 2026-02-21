#!/usr/bin/env bash
set -euo pipefail

DOC_API_URL="${DOC_API_URL:-http://localhost:8080/api/v1}"
OS_URL="${OPENSEARCH_URL:-http://localhost:59200}"
ALIAS_NAME="${OPENSEARCH_INDEX_ALIAS:-docs-active}"
WS_ID="${WS_ID:-${1:-}}"
QUERY="${SEARCH_QUERY:-과학}"
AUTH_EMAIL="${AUTH_EMAIL:-owner@autodoc.local}"
AUTH_PASSWORD="${AUTH_PASSWORD:-password}"
API_TOKEN="${API_TOKEN:-}"

OS_AUTH_ARGS=()
if [[ -n "${OPENSEARCH_USERNAME:-}" && -n "${OPENSEARCH_PASSWORD:-}" ]]; then
  OS_AUTH_ARGS=(-u "${OPENSEARCH_USERNAME}:${OPENSEARCH_PASSWORD}")
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required" >&2
  exit 1
fi

if [[ -z "$WS_ID" ]]; then
  echo "WS_ID env (or first arg) is required" >&2
  exit 1
fi

if [[ -z "$API_TOKEN" ]]; then
  login_body="$(jq -n --arg email "$AUTH_EMAIL" --arg password "$AUTH_PASSWORD" '{email:$email,password:$password}')"
  login_resp="$(curl -sS -X POST "$DOC_API_URL/auth/login" -H 'Content-Type: application/json' -d "$login_body")"
  API_TOKEN="$(echo "$login_resp" | jq -r '.access_token // empty')"
  if [[ -z "$API_TOKEN" ]]; then
    echo "[search-smoke] login failed" >&2
    echo "$login_resp" | jq . >&2 || true
    exit 1
  fi
fi

encoded_query="$(jq -nr --arg q "$QUERY" '$q|@uri')"

echo "[search-smoke] alias check: $ALIAS_NAME"
alias_resp="$(curl -sS "${OS_AUTH_ARGS[@]}" "$OS_URL/_alias/$ALIAS_NAME")"
resolved_index="$(echo "$alias_resp" | jq -r 'keys | join(",") // ""')"
if [[ -z "$resolved_index" || "$resolved_index" == "null" ]]; then
  echo "[search-smoke][ERROR] alias not resolved: $ALIAS_NAME" >&2
  exit 1
fi
echo "[search-smoke] resolved index: $resolved_index"

echo "[search-smoke] workspace indexed count"
count_payload="$(jq -n --arg ws "$WS_ID" '{query:{term:{workspace_id:$ws}}}')"
count_resp="$(curl -sS "${OS_AUTH_ARGS[@]}" -X POST "$OS_URL/$ALIAS_NAME/_count" -H 'Content-Type: application/json' -d "$count_payload")"
ws_count="$(echo "$count_resp" | jq -r '.count // 0')"
echo "[search-smoke] workspace_count=$ws_count"

echo "[search-smoke] bm25 search query='$QUERY'"
bm25_resp="$(curl -sS "$DOC_API_URL/search?q=$encoded_query&mode=bm25&debug=true" \
  -H "Authorization: Bearer $API_TOKEN" \
  -H "X-Workspace-Id: $WS_ID")"
bm25_hits="$(echo "$bm25_resp" | jq -r '.items | length')"
if [[ "$bm25_hits" -le 0 ]]; then
  echo "[search-smoke][ERROR] bm25 hit count is 0" >&2
  echo "$bm25_resp" | jq . >&2 || true
  exit 1
fi
echo "[search-smoke] bm25_hits=$bm25_hits"

echo "[search-smoke] hybrid search"
hybrid_resp="$(curl -sS "$DOC_API_URL/search?q=$encoded_query&mode=hybrid&debug=true" \
  -H "Authorization: Bearer $API_TOKEN" \
  -H "X-Workspace-Id: $WS_ID")"
hybrid_hits="$(echo "$hybrid_resp" | jq -r '.items | length')"
vector_used="$(echo "$hybrid_resp" | jq -r '.debug.vector_used // false')"
rrf_count="$(echo "$hybrid_resp" | jq -r '[.debug.top_ranks[]? | select(.rrf_score != null)] | length')"
vector_reason="$(echo "$hybrid_resp" | jq -r '.debug.vector_reason // "unknown"')"

if [[ "$hybrid_hits" -le 0 ]]; then
  echo "[search-smoke][ERROR] hybrid hit count is 0" >&2
  echo "$hybrid_resp" | jq . >&2 || true
  exit 1
fi

if [[ "$vector_used" == "true" || "$rrf_count" -gt 0 ]]; then
  echo "[search-smoke] hybrid ok (vector_used=$vector_used rrf_entries=$rrf_count)"
else
  echo "[search-smoke] hybrid degraded to bm25 (vector_reason=$vector_reason)"
fi

echo "[search-smoke] completed"
