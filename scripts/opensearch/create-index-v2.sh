#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OPENSEARCH_URL:-http://localhost:59200}"
INDEX_PREFIX="${OPENSEARCH_INDEX_PREFIX:-docs}"
INDEX_VERSION="${OPENSEARCH_INDEX_VERSION:-v2}"
INDEX_NAME="${1:-${INDEX_PREFIX}-${INDEX_VERSION}-$(date +%Y%m%d%H%M%S)}"

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

response="$(curl -sS "${AUTH_ARGS[@]}" -X PUT "$BASE_URL/$INDEX_NAME" -H 'Content-Type: application/json' -d '{}')"

echo "$response" | jq .

if [[ "$(echo "$response" | jq -r '.acknowledged // false')" != "true" ]]; then
  error_type="$(echo "$response" | jq -r '.error.type // ""')"
  if [[ "$error_type" != "resource_already_exists_exception" ]]; then
    echo "[create-index-v2] failed to create index: $INDEX_NAME" >&2
    exit 1
  fi
fi

echo "[create-index-v2] index=$INDEX_NAME"
