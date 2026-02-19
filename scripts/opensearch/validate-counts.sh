#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OPENSEARCH_URL:-http://localhost:59200}"
SOURCE_INDEX="${1:-${OPENSEARCH_SOURCE_INDEX:-docs-active}}"
TARGET_INDEX="${2:-${OPENSEARCH_TARGET_INDEX:-}}"

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

agg_payload='{"size":0,"aggs":{"by_workspace":{"terms":{"field":"workspace_id","size":2000}}}}'

source_resp="$(curl -sS "${AUTH_ARGS[@]}" -X POST "$BASE_URL/$SOURCE_INDEX/_search" -H 'Content-Type: application/json' -d "$agg_payload")"
target_resp="$(curl -sS "${AUTH_ARGS[@]}" -X POST "$BASE_URL/$TARGET_INDEX/_search" -H 'Content-Type: application/json' -d "$agg_payload")"

echo "[validate-counts] source=$SOURCE_INDEX"
echo "$source_resp" | jq '{total:.hits.total.value, buckets:.aggregations.by_workspace.buckets}'

echo "[validate-counts] target=$TARGET_INDEX"
echo "$target_resp" | jq '{total:.hits.total.value, buckets:.aggregations.by_workspace.buckets}'

source_by_ws="$(echo "$source_resp" | jq -c '.aggregations.by_workspace.buckets | map({key:.key,count:.doc_count})')"
target_by_ws="$(echo "$target_resp" | jq -c '.aggregations.by_workspace.buckets | map({key:.key,count:.doc_count})')"

comparison="$(jq -n --argjson src "$source_by_ws" --argjson dst "$target_by_ws" '
  def to_map(xs): reduce xs[] as $x ({}; .[$x.key] = $x.count);
  (to_map($src)) as $s
  | (to_map($dst)) as $d
  | ((($s|keys) + ($d|keys)) | unique) as $keys
  | [ $keys[] | {workspace_id: ., source_count: ($s[.] // 0), target_count: ($d[.] // 0), gap: (($d[.] // 0) - ($s[.] // 0))} ]
')"

echo "[validate-counts] workspace diff"
echo "$comparison" | jq .
