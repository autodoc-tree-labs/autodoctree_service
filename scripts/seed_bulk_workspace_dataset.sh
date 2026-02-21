#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SQL_FILE="$ROOT_DIR/scripts/sql/seed_bulk_workspace_dataset.sql"

POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_DB="${POSTGRES_DB:-autodoc}"
POSTGRES_USER="${POSTGRES_USER:-autodoc}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-autodoc}"

SEED_OWNER_EMAIL="${SEED_OWNER_EMAIL:-owner@autodoc.local}"
SEED_WORKSPACE_ID="${SEED_WORKSPACE_ID:-8f70d9bb-9e5b-4c48-b6a6-9f2755899c11}"
SEED_WORKSPACE_NAME="${SEED_WORKSPACE_NAME:-Test}"
SEED_DOC_COUNT="${SEED_DOC_COUNT:-1200}"
SEED_ATTACHMENT_RATIO="${SEED_ATTACHMENT_RATIO:-35}"

log() {
  printf '[seed-bulk-dataset] %s\n' "$1"
}

fail() {
  printf '[seed-bulk-dataset][ERROR] %s\n' "$1" >&2
  exit 1
}

if [[ ! -f "$SQL_FILE" ]]; then
  fail "SQL file not found: $SQL_FILE"
fi

if ! command -v docker >/dev/null 2>&1 && ! command -v psql >/dev/null 2>&1; then
  fail "either docker or psql is required"
fi

if ! [[ "$SEED_DOC_COUNT" =~ ^[0-9]+$ ]]; then
  fail "SEED_DOC_COUNT must be a non-negative integer"
fi

if ! [[ "$SEED_ATTACHMENT_RATIO" =~ ^[0-9]+$ ]]; then
  fail "SEED_ATTACHMENT_RATIO must be an integer between 0 and 100"
fi

USE_DOCKER=0
if command -v docker >/dev/null 2>&1 && docker compose ps postgres >/dev/null 2>&1; then
  USE_DOCKER=1
fi

run_psql() {
  if [[ "$USE_DOCKER" -eq 1 ]]; then
    docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
  else
    PGPASSWORD="$POSTGRES_PASSWORD" psql \
      -h "$POSTGRES_HOST" \
      -p "$POSTGRES_PORT" \
      -U "$POSTGRES_USER" \
      -d "$POSTGRES_DB" \
      "$@"
  fi
}

wait_for_db() {
  local max_try=30
  local try=1
  while [[ "$try" -le "$max_try" ]]; do
    if run_psql -tAc "SELECT 1" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
    try=$((try + 1))
  done
  return 1
}

log "checking database connectivity..."
if ! wait_for_db; then
  fail "failed to connect to Postgres. run: docker compose up -d postgres"
fi

log "config"
log "owner_email=$SEED_OWNER_EMAIL"
log "workspace_id=$SEED_WORKSPACE_ID"
log "workspace_name=$SEED_WORKSPACE_NAME"
log "doc_count=$SEED_DOC_COUNT (child docs, roots added separately)"
log "attachment_ratio=$SEED_ATTACHMENT_RATIO%"

if [[ "$SEED_DOC_COUNT" -lt 1000 ]]; then
  log "warning: SEED_DOC_COUNT is below 1000 (requested target is 1000+)"
fi

log "applying SQL seed file: $SQL_FILE"
run_psql \
  -v ON_ERROR_STOP=1 \
  -v "seed_owner_email=$SEED_OWNER_EMAIL" \
  -v "seed_workspace_id=$SEED_WORKSPACE_ID" \
  -v "seed_workspace_name=$SEED_WORKSPACE_NAME" \
  -v "seed_doc_count=$SEED_DOC_COUNT" \
  -v "seed_attachment_ratio=$SEED_ATTACHMENT_RATIO" \
  <"$SQL_FILE"

log "workspace summary"
run_psql -P pager=off -c "
SELECT
  w.id AS workspace_id,
  w.name,
  COUNT(d.id) FILTER (WHERE d.deleted = FALSE) AS document_count,
  COUNT(d.id) FILTER (WHERE d.deleted = FALSE AND d.parent_document_id IS NOT NULL) AS child_document_count,
  (SELECT COUNT(*) FROM attachments a WHERE a.workspace_id = w.id) AS attachment_count
FROM workspaces w
LEFT JOIN documents d
  ON d.workspace_id = w.id
WHERE w.id = '$SEED_WORKSPACE_ID'
GROUP BY w.id, w.name
LIMIT 1;
"

log "done"
log "run tree rebuild after worker catches up if you want fresh node assignment."
