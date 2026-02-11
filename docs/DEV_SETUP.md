# DEV_SETUP

## Prerequisites
- Java 17
- Node 20+
- pnpm 10+
- Docker Desktop

## 1. Start local infra
```bash
docker compose up -d
docker compose ps
```

If default ports are already in use, run with overrides:
```bash
POSTGRES_PORT=55432 OPENSEARCH_PORT=59201 REDIS_PORT=56380 MINIO_PORT=59010 MINIO_CONSOLE_PORT=59011 docker compose up -d
```

Services:
- Postgres: `localhost:5432`
- OpenSearch: `localhost:59200`
- Redis: `localhost:56379`
- MinIO API: `localhost:59000`
- MinIO Console: `localhost:59001`

Optional observability stack (Prometheus + Grafana):
```bash
docker compose --profile observability up -d prometheus grafana
docker compose ps prometheus grafana
```
- Prometheus: `localhost:59090`
- Grafana: `localhost:53000` (`admin` / `admin`)
- Dashboards:
  - `Tree Rebuild Overview`
  - `Edge Graph Health`
  - `Unsorted & Questions Queue`

## 1-1. Optional local LLM (Ollama, offline embedding + generate)
Start only Ollama with compose profile:
```bash
docker compose --profile llm up -d ollama
docker compose ps ollama
```

Pull and verify both models:
```bash
docker compose --profile llm exec ollama ollama pull bge-m3
docker compose --profile llm exec ollama ollama pull llama3.1:8b-instruct
docker compose --profile llm exec ollama ollama list
```

If host port `11434` is already used, override with `OLLAMA_PORT`.

Optional one-shot preload service:
```bash
docker compose --profile llm --profile llm-init up ollama-init
```

## 1-2. Optional local reranker (Stage-B edge validation)
Start reranker service with compose profile:
```bash
docker compose --profile ml up -d reranker-api
docker compose ps reranker-api
```

Smoke check:
```bash
./scripts/reranker_smoke.sh
```

If host port `18080` is already used, override with `RERANKER_PORT`.

## 1-3. Optional local structure worker (hSBM import fallback)
Start structure worker service with compose profile:
```bash
docker compose --profile structure up -d structure-worker
docker compose ps structure-worker
```

Smoke check:
```bash
./scripts/structure_worker_smoke.sh
```

If host port `18081` is already used, override with `STRUCTURE_WORKER_PORT`.

## 2. Backend (IntelliJ)
1. Open `services/` as Gradle project.
2. Run `doc-api` main class: `com.autodoctree.api.DocApiApplicationKt`
3. Env:
- `SPRING_PROFILES_ACTIVE=local`
- `DOC_API_JWT_SECRET=dev-secret-change-me`
- MinIO를 기본 포트 외로 바꿨다면 `S3_ENDPOINT`를 같은 주소로 지정 (예: `http://localhost:59010`)
- Ollama 임베딩 사용 시:
  - `FEATURE_EMBEDDING_OLLAMA=true`
  - `EMBEDDING_PROVIDER=ollama`
  - `EMBEDDING_OLLAMA_BASE_URL=http://localhost:11434`
  - `EMBEDDING_OLLAMA_MODEL=bge-m3`
  - `LLM_PROVIDER=ollama`
  - `LLM_OLLAMA_BASE_URL=http://localhost:11434`
  - `LLM_OLLAMA_MODEL=llama3.1:8b-instruct`
  - `FEATURE_LLM_LABELING=true`
  - `FEATURE_LLM_EXPLAIN=true`

Quick test:
```bash
curl -s http://localhost:8080/api/v1/health
```

Ollama smoke:
```bash
./scripts/llm_smoke.sh
```

## 3. Frontend
```bash
pnpm -w install
pnpm -C web-admin dev --port 5173
pnpm -C web-user dev --port 5174
```

## 4. HTTP smoke
Use IntelliJ HTTP Client files in `tools/http/`:
- `auth_and_doc_flow.http`
- `tenant_negative.http`

## 5. Feature flags
Default local flags are in `services/doc-api/src/main/resources/application-local.yml`.
Important flags:
- `feature.auto-tree`
- `feature.explain`
- `feature.hybrid-search`
- `feature.nori-tokenizer`
- `feature.label-quality-filter`
- `feature.community-clustering`
- `feature.feedback-routing-v2`
- `feature.user-rules-v1`
- `feature.admin-tree-debug`
- `security.os-tenant-assert`
- `TREE_NEIGHBOR_MIN_SIMILARITY` (기본 `0.8`, 값이 높을수록 트리 노드가 더 잘 분리됨)
- `TREE_NEIGHBOR_MUTUAL_KNN_REQUIRED`
- `TREE_NEIGHBOR_SNN_THRESHOLD`
- `TREE_NEIGHBOR_EDGE_BUDGET`
- `TREE_CONSENSUS_ENABLED`
- `TREE_CONSENSUS_THRESHOLD`
- `TREE_ASSIGN_AUTO_THRESHOLD`
- `TREE_ASSIGN_RECOMMEND_THRESHOLD`
- `TREE_ASSIGN_QUARANTINE_ENABLED`
- `TREE_ASSIGN_RERANKER_ENABLED`
- `TREE_STRUCTURE_WORKER_ENABLED`
- `TREE_MULTIVIEW_ENABLED`
- `TREE_CONCEPT_ENABLED`
- `TREE_CONCEPT_ASSIGN_THRESHOLD`
- `TREE_CONCEPT_MIN_DOCS`
- `TREE_CONCEPT_UPDATE_ALPHA`
- `TREE_OPTIMIZER_ENABLED`
- `TREE_OPTIMIZER_MAX_ITERATIONS`
- `TREE_OPTIMIZER_CHANGE_COST_LAMBDA`
- `TREE_OPTIMIZER_CANNOT_VIOLATION_MU`
- `TREE_OPTIMIZER_SIZE_PENALTY_NU`
- `TREE_OPTIMIZER_MIN_IMPROVEMENT`
- `TREE_RERANKER_PER_DOC_BUDGET`
- `TREE_RERANKER_PASS_THRESHOLD`
- `TREE_QUESTION_MAX_OPEN`
- `TREE_QUESTION_TTL_HOURS`
- `TREE_QUESTION_GENERATE_BATCH_SIZE`
- `RERANKER_ENABLED`
- `RERANKER_BASE_URL`
- `RERANKER_TIMEOUT_MS`
- `RERANKER_BATCH_SIZE`
- `RERANKER_MAX_RETRIES`
- `RERANKER_RETRY_BACKOFF_MS`
- `STRUCTURE_WORKER_ENABLED`
- `STRUCTURE_WORKER_BASE_URL`
- `STRUCTURE_WORKER_TIMEOUT_MS`
- `STRUCTURE_WORKER_MAX_RETRIES`
- `STRUCTURE_WORKER_RETRY_BACKOFF_MS`
- `STRUCTURE_WORKER_MAX_DEPTH`
- `TREE_EMBEDDING_DOCUMENT_WEIGHT` / `TREE_EMBEDDING_SUMMARY_WEIGHT` / `TREE_EMBEDDING_SECTION_WEIGHT`
  - 채널별 임베딩 결합 가중치(TITLE, BODY_SUMMARY, SECTION_CENTROID)

관리자 트리 디버그/룰 기능을 로컬에서 켜려면:
```bash
FEATURE_USER_RULES_V1=true FEATURE_ADMIN_TREE_DEBUG=true ./gradlew -p services :doc-api:bootRun
```

## 5-1. OpenSearch ko_nori analyzer check
`_index_template`는 서버 시작 시 자동 갱신됩니다. analyzer 확인:
```bash
curl -s -X POST http://localhost:59200/_analyze \
  -H 'Content-Type: application/json' \
  -d '{"analyzer":"ko_nori","text":"사랑하는 문서를 자동 분류합니다"}'
```

템플릿 변경 후 재인덱싱이 필요하면:
```bash
curl -s -X DELETE http://localhost:59200/docs-active
```
그 뒤 `doc-api` 재시작 시 템플릿/인덱스/alias가 다시 bootstrap 됩니다.

## 6. Security notes
- Do not log document body, extracted text, chunk text, or presigned URLs.
- Tenant-scoped endpoints require `X-Workspace-Id`.
