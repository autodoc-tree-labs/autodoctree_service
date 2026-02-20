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
- `security.log-sanitizer-enabled`
- `security.log-max-string-length`
- `TREE_NEIGHBOR_TOP_K` (권장 `5`)
- `TREE_NEIGHBOR_NORMALIZE` (권장 `true`)
- `TREE_NEIGHBOR_MIN_SIMILARITY` (권장 `0.65`)
- `TREE_NEIGHBOR_MIN_SIMILARITY_AUTO` / `TREE_NEIGHBOR_MIN_SIMILARITY_AUTO_MARGIN`
- `TREE_NEIGHBOR_MUTUAL_KNN`
- `TREE_NEIGHBOR_SHARED_NEIGHBOR_JACCARD_MIN` (권장 `0.10`)
- `TREE_NEIGHBOR_EDGE_BUDGET`
- `TREE_NEIGHBOR_DEGREE_CAP` / `TREE_NEIGHBOR_BRIDGE_PRUNE_POLICY`
- `TREE_CLUSTER_MERGE_MIN_AFFINITY` (권장 `0.55`)
- `TREE_CLUSTER_SPLIT_RETRY_WITH_HIGHER_RESOLUTION`
- `TREE_CLUSTER_SPLIT_RETRY_RESOLUTION_MULTIPLIER`
- `TREE_CONSENSUS_ENABLED`
- `TREE_CONSENSUS_THRESHOLD`
- `TREE_ASSIGN_AUTO_THRESHOLD`
- `TREE_ASSIGN_RECOMMEND_THRESHOLD`
- `TREE_ASSIGN_QUARANTINE_ENABLED`
- `TREE_TEMPLATE_ISOLATION_ENABLED`
- `TREE_TEMPLATE_SCORE_THRESHOLD`
- `TREE_TEMPLATE_BOILERPLATE_RATIO_THRESHOLD`
- `TREE_TEMPLATE_NGRAM_REPEAT_THRESHOLD`
- `TREE_TEMPLATE_FINGERPRINT_MIN_DOCS`
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

### Tree Rebuild 품질 기본값 (운영 권장)
- normalize된 cosine(`TREE_NEIGHBOR_NORMALIZE=true`) 환경에서는 무관 문서 baseline이 `0.5` 근처가 될 수 있습니다.
- 이때 `TREE_NEIGHBOR_MIN_SIMILARITY=0.25`는 사실상 대부분 edge를 통과시켜 과연결/혼합을 유발합니다.
- 운영에서는 normalize=true일 때 `minSimilarity`를 보통 `0.6~0.8` 범위에서 시작해 조정합니다.
- 권장 기본값:
  - `TREE_NEIGHBOR_NORMALIZE=true`
  - `TREE_NEIGHBOR_MIN_SIMILARITY=0.65`
  - `TREE_NEIGHBOR_TOP_K=5`
  - `TREE_NEIGHBOR_MUTUAL_KNN=true`
  - `TREE_NEIGHBOR_SHARED_NEIGHBOR_JACCARD_MIN=0.10`
  - `TREE_CLUSTER_MERGE_MIN_AFFINITY=0.55`

`tree_rebuild_summary`에서 운영자가 먼저 볼 값:
- `similarity_distributions.fused_sim.p95/p99` (임계값 대비 분포 위치)
- `edge_statistics.edges_after_top_k` / `edges_filtered_by_*` (필터 단계별 제거량)
- `graph_statistics.undirected_degree.p99/max` (과연결 탐지 핵심; p99가 튀면 브릿지 과다 가능성 높음)

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

매핑/템플릿 변경 후 reindex + alias swap:
```bash
./scripts/opensearch_reindex_alias_swap.sh docs-active docs-v1-000002
```

기본 연결값:
- `OPENSEARCH_URL=http://localhost:59200`
- `OPENSEARCH_INDEX_ALIAS=docs-active`
- `OPENSEARCH_USERNAME` / `OPENSEARCH_PASSWORD` (필요 시)

## 5-2. Offline model packaging bootstrap (I-1001)
모델 파일은 `models/manifest.json`에 고정 경로/버전/체크섬으로 관리합니다.

1) 모델 파일 반입(예시 경로):
- `models/ollama/bge-m3.tar`
- `models/ollama/llama3.1-8b-instruct.tar`
- `models/reranker/model.onnx`
- (옵션) `models/clip/model.safetensors`

2) 체크섬 lock:
```bash
python3 scripts/model_manifest_lock.py --manifest models/manifest.json
```

3) 무결성 검증:
```bash
python3 scripts/model_manifest_verify.py --manifest models/manifest.json
```

4) reranker compose 실행(사전검증 포함):
```bash
docker compose --profile ml up -d reranker-api
```
`reranker-api`는 `model-preflight`가 성공해야 시작됩니다.

## 6. Security notes
- Do not log document body, extracted text, chunk text, or presigned URLs.
- Tenant-scoped endpoints require `X-Workspace-Id`.
- `SECURITY_LOG_SANITIZER_ENABLED=true` / `SECURITY_LOG_MAX_STRING_LENGTH=240`로 감사 로그 payload sanitization을 기본 강제한다.

## Command Palette / Advanced Search (new)
- Open palette: `Cmd+K` (mac) / `Ctrl+K` (win/linux).
- API: `GET /api/v1/search` now supports advanced params and optional debug metadata.
- History API: `GET/POST /api/v1/search/history`.
- Workspace invites (dev mock token flow):
  - create invite `POST /api/v1/workspaces/{workspaceId}/invites`
  - accept invite `POST /api/v1/workspaces/invites/accept`
