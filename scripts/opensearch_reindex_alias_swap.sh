#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${OPENSEARCH_URL:-http://localhost:59200}"
ALIAS_NAME="${1:-${OPENSEARCH_INDEX_ALIAS:-docs-active}}"
TARGET_INDEX="${2:-}"

if [[ -z "${TARGET_INDEX}" ]]; then
  echo "Usage: $0 [alias_name] <target_index>"
  echo "Example: $0 docs-active docs-v1-000002"
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required" >&2
  exit 1
fi

AUTH_ARGS=()
if [[ -n "${OPENSEARCH_USERNAME:-}" && -n "${OPENSEARCH_PASSWORD:-}" ]]; then
  AUTH_ARGS=(-u "${OPENSEARCH_USERNAME}:${OPENSEARCH_PASSWORD}")
fi

request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  if [[ -z "${body}" ]]; then
    curl -sS "${AUTH_ARGS[@]}" -X "${method}" "${BASE_URL}${path}" -H 'Content-Type: application/json'
  else
    curl -sS "${AUTH_ARGS[@]}" -X "${method}" "${BASE_URL}${path}" -H 'Content-Type: application/json' -d "${body}"
  fi
}

echo "[1/5] create target index: ${TARGET_INDEX}"
create_response="$(request PUT "/${TARGET_INDEX}" '{}' || true)"
if [[ -n "${create_response}" ]]; then
  create_error_type="$(echo "${create_response}" | jq -r '.error.type // empty')"
  if [[ "${create_error_type}" != "resource_already_exists_exception" && "${create_error_type}" != "" ]]; then
    echo "failed to create index: ${create_response}" >&2
    exit 1
  fi
fi

echo "[2/5] reindex from alias '${ALIAS_NAME}' to '${TARGET_INDEX}'"
reindex_payload="$(jq -cn --arg src "${ALIAS_NAME}" --arg dest "${TARGET_INDEX}" '{source:{index:$src},dest:{index:$dest}}')"
reindex_response="$(request POST '/_reindex?wait_for_completion=true' "${reindex_payload}")"
if [[ "$(echo "${reindex_response}" | jq -r '.failures | length // 0')" != "0" ]]; then
  echo "reindex returned failures: ${reindex_response}" >&2
  exit 1
fi

echo "[3/5] fetch alias state"
alias_http_and_body="$(curl -sS "${AUTH_ARGS[@]}" -w '\n%{http_code}' "${BASE_URL}/_alias/${ALIAS_NAME}")"
alias_body="$(echo "${alias_http_and_body}" | sed '$d')"
alias_http="$(echo "${alias_http_and_body}" | tail -n1)"

existing_indexes_json='[]'
if [[ "${alias_http}" == "200" ]]; then
  existing_indexes_json="$(echo "${alias_body}" | jq -c 'keys')"
elif [[ "${alias_http}" != "404" ]]; then
  echo "failed to read alias state: HTTP ${alias_http} ${alias_body}" >&2
  exit 1
fi

echo "[4/5] swap alias write index"
actions_json="$(jq -cn --arg alias "${ALIAS_NAME}" --arg target "${TARGET_INDEX}" --argjson existing "${existing_indexes_json}" '
  (
    [ $existing[] | select(. != $target) | {add:{index:., alias:$alias, is_write_index:false}} ]
    + [ {add:{index:$target, alias:$alias, is_write_index:true}} ]
  )
')"
alias_payload="$(jq -cn --argjson actions "${actions_json}" '{actions:$actions}')"
alias_swap_response="$(request POST '/_aliases' "${alias_payload}")"
if [[ "$(echo "${alias_swap_response}" | jq -r '.acknowledged // false')" != "true" ]]; then
  echo "alias swap failed: ${alias_swap_response}" >&2
  exit 1
fi

echo "[5/5] verify write alias"
verify_response="$(request GET "/_alias/${ALIAS_NAME}")"
write_index="$(echo "${verify_response}" | jq -r --arg alias "${ALIAS_NAME}" '
  to_entries[]
  | select(.value.aliases[$alias].is_write_index == true)
  | .key
' | head -n1)"

if [[ "${write_index}" != "${TARGET_INDEX}" ]]; then
  echo "unexpected write index: '${write_index}', expected '${TARGET_INDEX}'" >&2
  exit 1
fi

echo "done: alias '${ALIAS_NAME}' now writes to '${TARGET_INDEX}'"
