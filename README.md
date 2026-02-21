# AutoDoc Tree (Monorepo)
“저장/업로드만 하면 알아서 트리로 정리되는” 문서 서비스 — **Workspace = Tenant 멀티테넌시**

이 레포는 **모노레포**로 운영합니다.

- `services/` : Kotlin + Spring Boot (API + Workers)
- `web-user/` : 사용자 웹 (Vite + React + TS)
- `web-admin/` : 운영/관리자 웹 (Vite + React + TS)
- `tasks/backlog/` : 티켓 기반 개발(프론트/백엔드/인프라 포함)

## Goals (v1 GA)
- Web에서 문서 작성(Markdown) + 파일 업로드(PDF/DOCX/TXT/MD)
- 비동기 파이프라인: ingest → embedding → index → auto-tree snapshot
- 가상 트리(스냅샷): 2-depth 기본, recommended/apply, lock(고정)
- (옵션) multi-view tree(`topic/project/timeline/version/template`)로 목적별 구조 분리
- (옵션) consensus clustering(co-association strong edge)로 리빌드 흔들림 완화
- (옵션) concept prototype 기반 증분 선배치 + objective optimizer(변경비용 정규화)
- Explain(왜 여기에?) + Drag&Drop 피드백(move/rename) + 개인화 v1(간단 규칙/가중치)
- 멀티테넌시 격리: API/DB/OpenSearch/Redis/S3까지 **fail-closed**
- 운영: 재처리/감사로그(필터/정렬)/관측성/런북

## Ports (local)
- `web-admin`: 5173
- `web-user`: 5174
- `doc-api`: 8080 (권장)
- (선택) OpenSearch: 59200, Redis: 56379, Postgres: 5432, MinIO: 59000/59001, Ollama: 11434 (`--profile llm`), Reranker: 18080 (`--profile ml`), Structure Worker: 18081 (`--profile structure`)

## Quickstart

1) infra
```bash
docker compose up -d
docker compose ps
```

Optional offline embedding/generate runtime (Ollama):
```bash
docker compose --profile llm up -d ollama
docker compose --profile llm exec ollama ollama pull bge-m3
docker compose --profile llm exec ollama ollama pull llama3.1:8b-instruct
docker compose --profile llm exec ollama ollama list
```

Optional one-shot preload profile:
```bash
docker compose --profile llm --profile llm-init up ollama-init
```
임베딩은 `TITLE`, `BODY_SUMMARY`, `SECTION`, `SECTION_CENTROID` 채널로 저장되고 트리 리빌드 시 가중 결합됩니다.

### Search v2 (Multilingual + Hybrid)
- 기본 lexical 검색은 `multi_match` + workspace filter(`workspace_id`) 강제 적용입니다.
- v2 템플릿은 `title/body`에 `ko/std/edge` 멀티필드를 사용합니다.
- 한국어는 nori, 다국어는 ICU(미설치 시 standard+asciifolding fallback)로 분석합니다.
- Hybrid는 BM25 + vector(kNN) 결과를 RRF(`k=60`)로 결합합니다.
- `GET /api/v1/search?...&debug=true`에서 아래 운영 진단이 가능합니다.
  - `workspace_id`, `index_alias`, `resolved_index_name`
  - `workspace_indexed_doc_count`
  - `search_backend`, `lang_detected`, `vector_used`, `vector_reason`
  - `top_ranks[].bm25_rank|knn_rank|rrf_score`

권장 환경값:
- `FEATURE_HYBRID_SEARCH=true`
- `OPENSEARCH_TEMPLATE_NAME=docs-template-v2`
- `OPENSEARCH_INDEX_PREFIX=docs`
- `OPENSEARCH_INDEX_VERSION=v2`
- `OPENSEARCH_VECTOR_FIELD=doc_embedding`
- `SEARCH_HYBRID_RRF_K=60`
- `SEARCH_HYBRID_CANDIDATE_SIZE=100`
- `SEARCH_HYBRID_KNN_TOP_K=100`

Blue/Green 마이그레이션:
```bash
WS_ID=<workspace-id> ./scripts/opensearch/diagnose-v2.sh
./scripts/opensearch/create-template-v2.sh
TARGET_INDEX="$(./scripts/opensearch/create-index-v2.sh | awk -F= '/^\[create-index-v2\] index=/{print $2}')"
./scripts/opensearch/reindex-to-v2.sh docs-active "$TARGET_INDEX"
./scripts/opensearch/validate-counts.sh docs-active "$TARGET_INDEX"
./scripts/opensearch/alias-swap.sh docs-active "$TARGET_INDEX"
```

검색 스모크:
```bash
WS_ID=<workspace-id> ./scripts/search-smoke.sh
```

### Tree Rebuild Quality (defaults)
- `neighbor-normalize=true`인 경우 무관 문서 cosine도 정규화 후 `0.5` 근처가 될 수 있으므로, 낮은 임계값(`0.25`)은 과연결을 유발할 수 있습니다.
- normalize 환경에서는 `minSimilarity`를 보통 `0.6~0.8` 범위로 두고 시작하는 것이 안전합니다.
- 기본/권장값:
  - `tree.neighbor-normalize=true`
  - `tree.neighbor-min-similarity=0.65`
  - `tree.neighbor-top-k=5`
  - `tree.neighbor-mutual-knn=true`
  - `tree.neighbor-shared-neighbor-jaccard-min=0.10`
  - `tree.cluster-merge-min-affinity=0.55`
- `tree_rebuild_summary` 주요 확인 항목:
  - `similarity_distributions.fused_sim.p95/p99`
  - `edge_statistics.edges_after_top_k`, `edges_filtered_by_mutual_knn`, `edges_filtered_by_snn`, `edges_filtered_by_degree_cap`
  - `graph_statistics.undirected_degree.p99/max` (과연결이면 p99가 급상승)

Optional local reranker runtime (Stage-B edge validation):
```bash
python3 scripts/model_manifest_verify.py --manifest models/manifest.json
docker compose --profile ml up -d reranker-api
./scripts/reranker_smoke.sh
```

Optional local structure worker runtime (hSBM import fallback path):
```bash
docker compose --profile structure up -d structure-worker
./scripts/structure_worker_smoke.sh
```

Optional local observability stack:
```bash
docker compose --profile observability up -d prometheus grafana
```
- Prometheus: `http://localhost:59090`
- Grafana: `http://localhost:53000` (`admin` / `admin`)

Offline model manifest workflow:
```bash
python3 scripts/model_manifest_lock.py --manifest models/manifest.json
python3 scripts/model_manifest_verify.py --manifest models/manifest.json
```

2) backend (IntelliJ)
- `services/doc-api` Run Config (`com.autodoctree.api.DocApiApplicationKt`)
- 기본 포트 기준 추가 env 없이 실행 가능

### Seed: `Test` workspace + diverse documents (SQL + shell)
`Test` 워크스페이스와 문서/하위 문서(문서 분류용), 다양한 주제 문서(노드 분류용)를 SQL로 주입합니다.

```bash
# doc-api를 먼저 1회 실행해 owner@autodoc.local 시드 계정을 생성
./scripts/seed_test_workspace.sh
```

- SQL 원본: `scripts/sql/seed_test_workspace.sql`
- 스크립트는 idempotent이며 재실행해도 중복 삽입을 방지합니다.
- `DocumentSaved` outbox 이벤트도 함께 enqueue되어 worker가 ingest→embed→index→tree를 이어서 처리할 수 있습니다.

3) front
```bash
pnpm -w install
pnpm -C web-user dev --port 5174
pnpm -C web-admin dev --port 5173
```

### web-user IA v2 (Workspace-first)
- `web-user`는 상단 탭 대신 `Sidebar + Main + Thin Header` 구조를 사용합니다.
- Sidebar 구성:
  - Workspace switcher
  - Quick actions (`새 페이지`, `검색/Cmd+K`)
  - `Favorites` (사용자별 즐겨찾기 문서, `...` 메뉴에서 토글)
  - `Pages` 문서 트리(부모-자식, 드래그로 하위/루트 이동)
  - `Views` (`Documents`, `Tree`, `Questions`, `Trash`)
- Main은 문서 중심 흐름입니다:
  - 문서 클릭 시 `/w/:workspaceId/doc/:docId` 편집 화면
  - 문서함/트리/질문함은 Sidebar View 전환으로 접근

주요 라우트:
- `/w/:workspaceId` : 기본 Documents view
- `/w/:workspaceId/doc/:docId` : 문서 에디터
- `/w/:workspaceId/doc/:docId/details` : 파이프라인/첨부/설명 상세
- `/w/:workspaceId/view/documents`
- `/w/:workspaceId/view/tree`
- `/w/:workspaceId/view/questions`
- `/w/:workspaceId/view/trash`

단축키:
- `Cmd/Ctrl + K`: Command Palette(문서 열기/새 페이지/뷰 이동)
- 에디터에서 `Cmd/Ctrl + S`: 문서 저장

워크스페이스 컨텍스트:
- 로그인 후 마지막 워크스페이스(`localStorage`)를 우선 복원합니다.
- 없으면 첫 번째 워크스페이스를 자동 선택합니다.

4) tests/build
```bash
./gradlew -p services test
./scripts/llm_smoke.sh
./scripts/reranker_smoke.sh
./scripts/structure_worker_smoke.sh
pnpm build
pnpm test:e2e
```

## Documents
- `ARCHITECTURE.md` : 시스템/데이터플로우/멀티테넌시 모델
- `API_SURFACE.md` : REST API v1 초안
- `Plans.md` : 마일스톤 + 티켓 인덱스
- `AGENTS.md` : 개발 규칙(테넌시/관측/테스트/PR)

## Ticket Prefix
- `B-xxxx` Backend (services)
- `U-xxxx` Web User
- `A-xxxx` Web Admin
- `I-xxxx` Infra/Platform

> 모든 구현은 `tasks/backlog/*.md` 티켓에서 시작합니다.

- Added Command Palette (Cmd/Ctrl+K) in web-user with unified command + document search, local filter persistence, and palette history endpoints.
- Extended `/api/v1/search` for advanced filters (`titleOnly`, creator/updater/date/scope/sort/mode/debug) and added `/api/v1/search/history`.
- Added invite + ACL/history schema in Flyway V13 and workspace invite APIs (`POST /workspaces/{id}/invites`, `POST /workspaces/invites/accept`).
