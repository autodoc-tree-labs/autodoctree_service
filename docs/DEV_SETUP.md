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

### 2-1. Test workspace 시드(SQL + Shell)
`Test` 워크스페이스와 다양한 주제 문서를 SQL로 주입합니다.

```bash
./scripts/seed_test_workspace.sh
```

- SQL 파일: `scripts/sql/seed_test_workspace.sql`
- 문서 모드 검증용 부모-자식(`parent_document_id`) 데이터와 노드 모드 검증용 다주제 문서가 함께 생성됩니다.
- 스크립트는 idempotent하게 동작합니다.

### 2-2. Bulk dataset 시드 (1000건+)
분류/검색/트리 튜닝용 대량 데이터를 생성합니다.

```bash
# 기본: child 문서 1200건 + 카테고리 root + 첨부 메타데이터
./scripts/seed_bulk_workspace_dataset.sh
```

환경변수로 볼륨과 대상 워크스페이스를 조절할 수 있습니다.

```bash
SEED_WORKSPACE_ID=8f70d9bb-9e5b-4c48-b6a6-9f2755899c11 \
SEED_WORKSPACE_NAME=Test \
SEED_OWNER_EMAIL=owner@autodoc.local \
SEED_DOC_COUNT=1800 \
SEED_ATTACHMENT_RATIO=45 \
./scripts/seed_bulk_workspace_dataset.sh
```

- SQL 파일: `scripts/sql/seed_bulk_workspace_dataset.sql`
- `SEED_DOC_COUNT`는 child 문서 개수입니다(루트 문서는 카테고리별로 추가 생성).
- `SEED_ATTACHMENT_RATIO`는 child 문서 중 첨부 메타데이터를 생성할 비율(0~100)입니다.
- 스크립트는 upsert 기반으로 재실행 가능하며, `DocumentSaved` outbox를 `PENDING`으로 맞춥니다.

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

Block editor 모드 전환:
```bash
VITE_FEATURE_BLOCK_EDITOR=true pnpm -C web-user dev --port 5174
```
- `true`(기본): TipTap 기반 Block Editor(EditorV2)
- `false`: 기존 Markdown textarea(EditorV1)

### 3-1. web-user IA / 라우트
`web-user`는 Workspace-first AppShell을 사용합니다.
- Sidebar: Workspace switcher, Quick actions, Favorites, Pages 트리(드래그 이동), Views
- Main: 문서 중심 편집/탐색

즐겨찾기 API:
- `GET /api/v1/documents/favorites`
- `POST /api/v1/documents/{documentId}/favorite`
- `DELETE /api/v1/documents/{documentId}/favorite`

주요 라우트:
- `/w/:workspaceId` (기본 Documents)
- `/w/:workspaceId/doc/:docId` (문서 편집)
- `/w/:workspaceId/doc/:docId/details` (문서 상세/파이프라인)
- `/w/:workspaceId/view/documents`
- `/w/:workspaceId/view/tree`
- `/w/:workspaceId/view/questions`
- `/w/:workspaceId/view/trash`

단축키:
- `Cmd/Ctrl + K`: Command Palette
- `Cmd/Ctrl + S`: 문서 저장(문서 편집 화면)

Block Editor V2 요약:
- `blocks_json` 기반 편집 + 서버에서 `body_markdown`/`body_text` 동기화
- Slash menu(`/`)로 블록 삽입 (추천/기본/미디어/데이터 섹션)
- Slash 검색: 한글/영문 fuzzy + 별칭(`/h1`, `/todo`, `/table`, `/toc`)
- 지원 블록: heading/list/todo/toggle/callout/quote/divider/code/table/toc/image/file
- 문서 상단에 `created_by`, `updated_by`, `created_at`, `updated_at` 표시

Attachment upload policy:
- API: `/api/v1/attachments/presign` -> PUT upload -> `/api/v1/attachments/complete`
- size: `> 0`, `<= 50MB`
- content_type allowlist:
  - `image/*`
  - `application/pdf`
  - `application/msword`
  - `application/vnd.openxmlformats-officedocument.wordprocessingml.document`
  - `text/plain`, `text/markdown`, `text/csv`
  - `application/octet-stream`

워크스페이스 선택 규칙:
- 마지막 사용 workspace(`autodoc.user.last-workspace.v1`) 자동 복원
- 저장값이 없으면 첫 workspace 자동 선택

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
- `TREE_NEIGHBOR_MIN_SIMILARITY` (권장 `0.55`)
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
- `TREE_FUSION_LEXICAL_GATE` (권장 `0.25`)
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
- `OPENSEARCH_TEMPLATE_NAME` (권장 `docs-template-v2`)
- `OPENSEARCH_INDEX_PREFIX` (권장 `docs`)
- `OPENSEARCH_INDEX_VERSION` (권장 `v2`)
- `OPENSEARCH_VECTOR_FIELD` (권장 `doc_embedding`)
- `SEARCH_HYBRID_RRF_K` (권장 `60`)
- `SEARCH_HYBRID_CANDIDATE_SIZE` (권장 `100`)
- `SEARCH_HYBRID_KNN_TOP_K` (권장 `100`)
- `SEARCH_INDEX_SYNC_SAMPLE_RATE` (권장 `0.05`)

### Tree Rebuild 품질 기본값 (운영 권장)
- normalize된 cosine(`TREE_NEIGHBOR_NORMALIZE=true`) 환경에서는 무관 문서 baseline이 `0.5` 근처가 될 수 있습니다.
- 이때 `TREE_NEIGHBOR_MIN_SIMILARITY=0.25`는 사실상 대부분 edge를 통과시켜 과연결/혼합을 유발합니다.
- 운영에서는 normalize=true일 때 `minSimilarity`를 보통 `0.55~0.75` 범위에서 시작해 조정합니다.
- 권장 기본값:
  - `TREE_NEIGHBOR_NORMALIZE=true`
  - `TREE_NEIGHBOR_MIN_SIMILARITY=0.55`
  - `TREE_NEIGHBOR_TOP_K=5`
  - `TREE_NEIGHBOR_MUTUAL_KNN=true`
  - `TREE_NEIGHBOR_SHARED_NEIGHBOR_JACCARD_MIN=0.10`
  - `TREE_FUSION_LEXICAL_GATE=0.25`
  - `TREE_ASSIGN_AUTO_THRESHOLD=0.58`
  - `TREE_ASSIGN_RECOMMEND_THRESHOLD=0.45`
  - `TREE_CLUSTER_MERGE_MIN_AFFINITY=0.55`

`tree_rebuild_summary`에서 운영자가 먼저 볼 값:
- `similarity_distributions.fused_sim.p95/p99` (임계값 대비 분포 위치)
- `edge_statistics.edges_after_top_k` / `edges_filtered_by_*` (필터 단계별 제거량)
- `graph_statistics.undirected_degree.p99/max` (과연결 탐지 핵심; p99가 튀면 브릿지 과다 가능성 높음)

관리자 트리 디버그/룰 기능을 로컬에서 켜려면:
```bash
FEATURE_USER_RULES_V1=true FEATURE_ADMIN_TREE_DEBUG=true ./gradlew -p services :doc-api:bootRun
```

## 5-1. OpenSearch Search v2 진단
현재 alias/template/plugin/mapping/settings/doc count를 한 번에 확인:
```bash
WS_ID=<workspace-id> ./scripts/opensearch/diagnose-v2.sh
```

수동 점검 커맨드:
```bash
curl -s http://localhost:59200/_cat/aliases?format=json | jq .
curl -s http://localhost:59200/_index_template/docs-template-v2 | jq .
curl -s http://localhost:59200/_cat/plugins?format=json | jq .
curl -s http://localhost:59200/docs-active/_mapping | jq .
curl -s http://localhost:59200/docs-active/_settings | jq .
curl -s -X POST http://localhost:59200/docs-active/_count \
  -H 'Content-Type: application/json' \
  -d '{"query":{"term":{"workspace_id":"<workspace-id>"}}}' | jq .
```

## 5-2. Analyzer smoke (`_analyze`)
`과학의 역사` 형태소/토큰 분해 확인:
```bash
curl -s -X POST http://localhost:59200/_analyze \
  -H 'Content-Type: application/json' \
  -d '{"analyzer":"ko_nori","text":"과학의 역사"}'
```

## 5-3. Blue/Green reindex + alias swap
```bash
./scripts/opensearch/create-template-v2.sh
TARGET_INDEX="$(./scripts/opensearch/create-index-v2.sh | awk -F= '/^\\[create-index-v2\\] index=/{print $2}')"
./scripts/opensearch/reindex-to-v2.sh docs-active "$TARGET_INDEX"
./scripts/opensearch/validate-counts.sh docs-active "$TARGET_INDEX"
./scripts/opensearch/alias-swap.sh docs-active "$TARGET_INDEX"
```

기본 연결값:
- `OPENSEARCH_URL=http://localhost:59200`
- `OPENSEARCH_INDEX_ALIAS=docs-active`
- `OPENSEARCH_USERNAME` / `OPENSEARCH_PASSWORD` (필요 시)

## 5-4. Search smoke (BM25 + Hybrid)
```bash
WS_ID=<workspace-id> ./scripts/search-smoke.sh
```
- BM25: `"과학"` 쿼리 hit > 0 이어야 함
- Hybrid: `debug.vector_used=true` 또는 `debug.top_ranks[].rrf_score` 존재를 확인

## 5-5. Offline model packaging bootstrap (I-1001)
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
