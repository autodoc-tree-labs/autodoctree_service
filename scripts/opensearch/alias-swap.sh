#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OPENSEARCH_URL:-http://localhost:59200}"
ALIAS_NAME="${1:-${OPENSEARCH_INDEX_ALIAS:-docs-active}}"
TARGET_INDEX="${2:-}"

if [[ -z "$TARGET_INDEX" ]]; then
  echo "Usage: $0 <alias_name> <target_index>" >&2
  exit 1
fi

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

alias_state_with_http="$(curl -sS "${AUTH_ARGS[@]}" -w '\n%{http_code}' "$BASE_URL/_alias/$ALIAS_NAME")"
alias_body="$(echo "$alias_state_with_http" | sed '$d')"
alias_http="$(echo "$alias_state_with_http" | tail -n1)"

existing='[]'
if [[ "$alias_http" == "200" ]]; then
  existing="$(echo "$alias_body" | jq -c 'keys')"
elif [[ "$alias_http" != "404" ]]; then
  echo "[alias-swap] failed to read alias state: HTTP $alias_http" >&2
  echo "$alias_body" >&2
  exit 1
fi

actions="$(jq -n --arg alias "$ALIAS_NAME" --arg target "$TARGET_INDEX" --argjson existing "$existing" '
  (
    [ $existing[] | {remove: {index: ., alias: $alias}} ]
    + [ {add: {index: $target, alias: $alias, is_write_index: true}} ]
  )
')"
payload="$(jq -n --argjson actions "$actions" '{actions: $actions}')"

response="$(curl -sS "${AUTH_ARGS[@]}" -X POST "$BASE_URL/_aliases" -H 'Content-Type: application/json' -d "$payload")"

echo "$response" | jq .
if [[ "$(echo "$response" | jq -r '.acknowledged // false')" != "true" ]]; then
  echo "[alias-swap] alias swap failed" >&2
  exit 1
fi

echo "[alias-swap] alias=$ALIAS_NAME target=$TARGET_INDEX"
