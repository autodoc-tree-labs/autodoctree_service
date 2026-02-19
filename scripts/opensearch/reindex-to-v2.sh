#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OPENSEARCH_URL:-http://localhost:59200}"
SOURCE_INDEX="${1:-${OPENSEARCH_SOURCE_INDEX:-docs-active}}"
TARGET_INDEX="${2:-}"

if [[ -z "$TARGET_INDEX" ]]; then
  echo "Usage: $0 <source_index_or_alias> <target_index>" >&2
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

payload="$(jq -n --arg src "$SOURCE_INDEX" --arg dst "$TARGET_INDEX" '{source:{index:$src},dest:{index:$dst,op_type:"index"}}')"
response="$(curl -sS "${AUTH_ARGS[@]}" -X POST "$BASE_URL/_reindex?wait_for_completion=true&refresh=true" -H 'Content-Type: application/json' -d "$payload")"

echo "$response" | jq .

failures="$(echo "$response" | jq -r '.failures | length // 0')"
if [[ "$failures" != "0" ]]; then
  echo "[reindex-to-v2] failures detected" >&2
  exit 1
fi

echo "[reindex-to-v2] source=$SOURCE_INDEX target=$TARGET_INDEX"
