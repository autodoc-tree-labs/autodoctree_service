#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify offline model manifest integrity.")
    parser.add_argument("--manifest", default="models/manifest.json", help="manifest path")
    parser.add_argument("--workspace", default=".", help="workspace root path")
    args = parser.parse_args()

    workspace = Path(args.workspace).resolve()
    manifest_path = (workspace / args.manifest).resolve()

    if not manifest_path.exists():
        print(f"[error] manifest not found: {manifest_path}")
        return 1

    manifest = load_manifest(manifest_path)
    models = manifest.get("models", [])
    if not isinstance(models, list):
        print("[error] manifest.models must be a list")
        return 1

    errors: list[str] = []
    checked = 0

    for entry in models:
        if not isinstance(entry, dict):
            errors.append("model entry is not an object")
            continue

        model_id = str(entry.get("id", "(unknown)"))
        rel_path = str(entry.get("path", "")).strip()
        required = bool(entry.get("required", True))
        expected_sha = str(entry.get("sha256", "")).strip().lower()
        expected_size = int(entry.get("size_bytes", 0) or 0)

        if not rel_path:
            errors.append(f"{model_id}: missing path")
            continue

        file_path = (workspace / rel_path).resolve()
        if not file_path.exists() or not file_path.is_file():
            if required:
                errors.append(f"{model_id}: missing file '{rel_path}'")
            continue

        if not SHA256_RE.fullmatch(expected_sha):
            errors.append(
                f"{model_id}: checksum not locked for '{rel_path}' (run scripts/model_manifest_lock.py)"
            )
            continue

        actual_size = file_path.stat().st_size
        if expected_size > 0 and actual_size != expected_size:
            errors.append(
                f"{model_id}: size mismatch for '{rel_path}' (expected {expected_size}, got {actual_size})"
            )
            continue

        actual_sha = sha256_file(file_path)
        if actual_sha != expected_sha:
            errors.append(
                f"{model_id}: sha256 mismatch for '{rel_path}'"
            )
            continue

        checked += 1
        print(f"[ok] {model_id} ({rel_path})")

    if errors:
        print("[error] model manifest verification failed:")
        for item in errors:
            print(f"  - {item}")
        return 1

    print(f"[ok] manifest verified: {checked} model(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
