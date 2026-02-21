#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OPENSEARCH_URL:-http://localhost:59200}"
ALIAS_NAME="${OPENSEARCH_INDEX_ALIAS:-docs-active}"
TEMPLATE_NAME="${OPENSEARCH_TEMPLATE_NAME:-docs-template-v2}"
WS_ID="${WS_ID:-${1:-}}"

AUTH_ARGS=()
if [[ -n "${OPENSEARCH_USERNAME:-}" && -n "${OPENSEARCH_PASSWORD:-}" ]]; then
  AUTH_ARGS=(-u "${OPENSEARCH_USERNAME}:${OPENSEARCH_PASSWORD}")
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required" >&2
  exit 1
fi

request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  if [[ -z "$body" ]]; then
    curl -sS "${AUTH_ARGS[@]}" -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json'
  else
    curl -sS "${AUTH_ARGS[@]}" -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json' -d "$body"
  fi
}

echo "[diag] OpenSearch URL: $BASE_URL"
echo "[diag] Alias: $ALIAS_NAME"
echo "[diag] Template: $TEMPLATE_NAME"

echo
printf '[1/7] _cat/aliases\n'
request GET '/_cat/aliases?format=json' | jq .

echo
printf '[2/7] _index_template (selected)\n'
request GET "/_index_template/$TEMPLATE_NAME" | jq .

echo
printf '[3/7] _cat/plugins\n'
request GET '/_cat/plugins?format=json' | jq .

echo
printf '[4/7] Alias mapping\n'
request GET "/$ALIAS_NAME/_mapping" | jq .

echo
printf '[5/7] Alias settings\n'
request GET "/$ALIAS_NAME/_settings" | jq .

echo
printf '[6/7] Alias total count\n'
request GET "/$ALIAS_NAME/_count" | jq .

if [[ -n "$WS_ID" ]]; then
  echo
  printf '[7/7] Workspace scoped count (workspace_id=%s)\n' "$WS_ID"
  request POST "/$ALIAS_NAME/_count" "{\"query\":{\"term\":{\"workspace_id\":\"$WS_ID\"}}}" | jq .
else
  echo
  echo "[7/7] Workspace scoped count skipped (set WS_ID env or first arg)"
fi
