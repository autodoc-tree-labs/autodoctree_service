#!/usr/bin/env bash
set -euo pipefail

MANIFEST_PATH="${MODEL_MANIFEST_PATH:-models/manifest.json}"

python3 scripts/model_manifest_verify.py --manifest "${MANIFEST_PATH}" --workspace .
