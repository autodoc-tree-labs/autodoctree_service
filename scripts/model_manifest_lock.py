#!/usr/bin/env python3
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
from typing import Any


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def save_manifest(path: Path, payload: dict[str, Any]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Lock model manifest with deterministic checksum + size.")
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

    missing_required: list[str] = []
    updated = 0

    for entry in models:
        if not isinstance(entry, dict):
            print("[error] each model entry must be an object")
            return 1

        model_id = str(entry.get("id", "(unknown)"))
        rel_path = str(entry.get("path", "")).strip()
        required = bool(entry.get("required", True))
        if not rel_path:
            print(f"[error] model '{model_id}' missing path")
            return 1

        file_path = (workspace / rel_path).resolve()
        if not file_path.exists() or not file_path.is_file():
            if required:
                missing_required.append(f"{model_id}: {rel_path}")
            else:
                entry["sha256"] = ""
                entry["size_bytes"] = 0
            continue

        checksum = sha256_file(file_path)
        size = file_path.stat().st_size
        entry["sha256"] = checksum
        entry["size_bytes"] = size
        updated += 1

    manifest["generated_at"] = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    save_manifest(manifest_path, manifest)

    print(f"[ok] manifest updated: {manifest_path}")
    print(f"[ok] locked entries: {updated}")

    if missing_required:
        print("[error] missing required model files:")
        for item in missing_required:
            print(f"  - {item}")
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
