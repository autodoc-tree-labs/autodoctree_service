# Offline model packaging

`models/manifest.json` is the source of truth for model file path/version/checksum.

## Required by default
- `models/ollama/bge-m3.tar`
- `models/ollama/llama3.1-8b-instruct.tar`
- `models/reranker/model.onnx`

## Optional
- `models/clip/model.safetensors`

## Lock manifest
```bash
python3 scripts/model_manifest_lock.py --manifest models/manifest.json
```

## Verify manifest
```bash
python3 scripts/model_manifest_verify.py --manifest models/manifest.json
```

The verification step must pass before starting `docker compose --profile ml up -d reranker-api`.
