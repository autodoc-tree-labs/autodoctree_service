#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SQL_FILE="$ROOT_DIR/scripts/sql/seed_test_workspace.sql"

POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_DB="${POSTGRES_DB:-autodoc}"
POSTGRES_USER="${POSTGRES_USER:-autodoc}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-autodoc}"

log() {
  printf '[seed-test-workspace] %s\n' "$1"
}

fail() {
  printf '[seed-test-workspace][ERROR] %s\n' "$1" >&2
  exit 1
}

if [[ ! -f "$SQL_FILE" ]]; then
  fail "SQL file not found: $SQL_FILE"
fi

if ! command -v docker >/dev/null 2>&1 && ! command -v psql >/dev/null 2>&1; then
  fail "either docker or psql is required"
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
  fail "failed to connect to Postgres. Run: docker compose up -d postgres"
fi

log "applying SQL seed file: $SQL_FILE"
run_psql -v ON_ERROR_STOP=1 <"$SQL_FILE"

log "summary"
run_psql -P pager=off -c "
SELECT
  w.id AS workspace_id,
  w.name,
  COUNT(d.id) FILTER (WHERE d.deleted = FALSE) AS document_count,
  COUNT(d.id) FILTER (WHERE d.deleted = FALSE AND d.parent_document_id IS NOT NULL) AS child_document_count
FROM workspaces w
LEFT JOIN documents d
  ON d.workspace_id = w.id
WHERE w.name = 'Test'
GROUP BY w.id, w.name
ORDER BY w.created_at DESC
LIMIT 1;
"

log "done"
log "if doc-api worker is running, DocumentSaved events will flow through ingest/embed/index/tree."
