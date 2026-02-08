#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVICE_DIR="$ROOT_DIR/services"

FILTERED_ARGS=()
SKIP_NEXT=false
for ARG in "$@"; do
  if [ "$SKIP_NEXT" = true ]; then
    SKIP_NEXT=false
    continue
  fi

  if [ "$ARG" = "-p" ] || [ "$ARG" = "--project-dir" ]; then
    SKIP_NEXT=true
    continue
  fi

  FILTERED_ARGS+=("$ARG")
done

exec "$SERVICE_DIR/gradlew" -p "$SERVICE_DIR" "${FILTERED_ARGS[@]}"
