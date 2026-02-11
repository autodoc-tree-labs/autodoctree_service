# reranker-api

Local offline reranker service for Tree Stage-B edge validation.

## Run local
```bash
cd services/reranker-api
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 18080
```

## API
- `GET /health`
- `POST /v1/rerank/pairs`

Request:
```json
{
  "pairs": [
    {
      "pair_key": "doc-a::doc-b",
      "left_text": "문서 A 요약",
      "right_text": "문서 B 요약"
    }
  ]
}
```
