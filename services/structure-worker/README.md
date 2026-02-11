# structure-worker

Optional offline structure inference worker (heuristic hSBM fallback).

## Run local
```bash
cd services/structure-worker
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 18081
```

## API
- `GET /health`
- `POST /v1/structure/infer`
