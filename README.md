# AutoDoc Tree (Monorepo)
“저장/업로드만 하면 알아서 트리로 정리되는” 문서 서비스 — **Workspace = Tenant 멀티테넌시**

이 레포는 **모노레포**로 운영합니다.

- `services/` : Kotlin + Spring Boot (API + Workers)
- `web-user/` : 사용자 웹 (Vite + React + TS)
- `web-admin/` : 운영/관리자 웹 (Vite + React + TS)
- `tasks/backlog/` : 티켓 기반 개발(프론트/백엔드/인프라 포함)

## Technical Docs
- `docs/TECHNICAL_HANDBOOK.md` : 현재 코드 기준 통합 기술 가이드
- `docs/DEV_SETUP.md` : 로컬 실행/시드/스모크 절차
- `ARCHITECTURE.md` : 상위 아키텍처 요약
- `API_SURFACE.md` : REST API 초안/계약 문서
- `docs/RUNBOOK.md` : 운영/복구 런북

## Goals (v1 GA)
- Web에서 문서 작성(Markdown V1 + TipTap Block Editor V2) + 파일 업로드(PDF/DOCX/TXT/MD/Image)
- 비동기 파이프라인: ingest → embedding → index → auto-tree snapshot
- 가상 트리(스냅샷): 2-depth 기본, recommended/apply, lock(고정)
- (옵션) multi-view tree(`topic/project/timeline/version/template`)로 목적별 구조 분리
- (옵션) consensus clustering(co-association strong edge)로 리빌드 흔들림 완화
- (옵션) concept prototype 기반 증분 선배치 + objective optimizer(변경비용 정규화)
- Explain(왜 여기에?) + Drag&Drop 피드백(move/rename) + 개인화 v1(간단 규칙/가중치)
- 멀티테넌시 격리: API/DB/OpenSearch/Redis/S3까지 **fail-closed**
- 운영: 재처리/감사로그(필터/정렬)/관측성/런북

## First clone (copy-paste)
```bash
git clone <repo-url>
cd autodoc-tree-monorepo-docs
cp .env.example .env
pnpm -w install
docker compose up -d
SPRING_PROFILES_ACTIVE=local \
DOC_API_JWT_SECRET=dev-secret-change-me \
FEATURE_EMBEDDING_OLLAMA=true \
EMBEDDING_PROVIDER=ollama \
EMBEDDING_OLLAMA_BASE_URL=http://localhost:21434 \
EMBEDDING_OLLAMA_MODEL=bge-m3 \
EMBEDDING_OLLAMA_TIMEOUT_MS=60000 \
LLM_PROVIDER=ollama \
LLM_OLLAMA_BASE_URL=http://localhost:21434 \
LLM_OLLAMA_MODEL=llama3.1:8b-instruct \
FEATURE_LLM_LABELING=true \
FEATURE_LLM_EXPLAIN=true \
SMTP_HOST=localhost \
SMTP_PORT=51025 \
SMTP_AUTH=false \
SMTP_STARTTLS_ENABLE=false \
DOC_API_REGISTER_SENDER_EMAIL=no-reply@autodoc.local \
./gradlew -p services :doc-api:bootRun
pnpm -C web-user dev --port 5174
```

- 관리자 UI가 필요하면 별도로 `pnpm -C web-admin dev --port 5173`를 실행합니다.
- 최초 로그인 테스트 계정은 `owner@autodoc.local / password` 입니다.

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
- normalize 환경에서는 `minSimilarity`를 보통 `0.55~0.75` 범위로 두고 시작하는 것이 안전합니다.
- 기본/권장값:
  - `tree.neighbor-normalize=true`
  - `tree.neighbor-min-similarity=0.55`
  - `tree.neighbor-top-k=5`
  - `tree.neighbor-mutual-knn=true`
  - `tree.neighbor-shared-neighbor-jaccard-min=0.10`
  - `tree.fusion-lexical-gate=0.25`
  - `tree.assign-auto-threshold=0.58`
  - `tree.assign-recommend-threshold=0.45`
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

2) backend (CLI 또는 IntelliJ)

CLI 풀 실행(권장):
```bash
SPRING_PROFILES_ACTIVE=local \
DOC_API_JWT_SECRET=dev-secret-change-me \
FEATURE_EMBEDDING_OLLAMA=true \
EMBEDDING_PROVIDER=ollama \
EMBEDDING_OLLAMA_BASE_URL=http://localhost:21434 \
EMBEDDING_OLLAMA_MODEL=bge-m3 \
EMBEDDING_OLLAMA_TIMEOUT_MS=60000 \
LLM_PROVIDER=ollama \
LLM_OLLAMA_BASE_URL=http://localhost:21434 \
LLM_OLLAMA_MODEL=llama3.1:8b-instruct \
FEATURE_LLM_LABELING=true \
FEATURE_LLM_EXPLAIN=true \
SMTP_HOST=localhost \
SMTP_PORT=51025 \
SMTP_AUTH=false \
SMTP_STARTTLS_ENABLE=false \
DOC_API_REGISTER_SENDER_EMAIL=no-reply@autodoc.local \
./gradlew -p services :doc-api:bootRun
```

Ollama 기본 포트(`11434`)를 쓸 때는 위 커맨드에서 아래 2개만 바꿉니다.
```bash
EMBEDDING_OLLAMA_BASE_URL=http://localhost:11434
LLM_OLLAMA_BASE_URL=http://localhost:11434
```

IntelliJ 실행:
- `services/doc-api` Run Config (`com.autodoctree.api.DocApiApplicationKt`)
- `SPRING_PROFILES_ACTIVE=local` 권장

### Seed: `Test` workspace + diverse documents (SQL + shell)
`Test` 워크스페이스와 문서/하위 문서(문서 분류용), 다양한 주제 문서(노드 분류용)를 SQL로 주입합니다.

```bash
# doc-api를 먼저 1회 실행해 owner@autodoc.local 시드 계정을 생성
./scripts/seed_test_workspace.sh
```

- SQL 원본: `scripts/sql/seed_test_workspace.sql`
- 스크립트는 idempotent이며 재실행해도 중복 삽입을 방지합니다.
- `DocumentSaved` outbox 이벤트도 함께 enqueue되어 worker가 ingest→embed→index→tree를 이어서 처리할 수 있습니다.

### Seed: bulk dataset (1000+ docs + attachment metadata)
대량 테스트/분류 튜닝용으로 카테고리 다변화 문서를 한 번에 생성합니다.

```bash
# 기본: child 3000건 + 카테고리 root 문서 + 첨부 메타데이터(기본 35%)
./scripts/seed_bulk_workspace_dataset.sh
```

옵션은 환경변수로 제어합니다.

```bash
SEED_WORKSPACE_ID=8f70d9bb-9e5b-4c48-b6a6-9f2755899c11 \
SEED_WORKSPACE_NAME=Test \
SEED_OWNER_EMAIL=owner@autodoc.local \
SEED_DOC_COUNT=1500 \
SEED_ATTACHMENT_RATIO=40 \
./scripts/seed_bulk_workspace_dataset.sh
```

- SQL 원본: `scripts/sql/seed_bulk_workspace_dataset.sql`
- `SEED_DOC_COUNT`는 child 문서 개수이며, 카테고리 root 문서는 별도로 추가됩니다.
- 문서 제목/본문은 조합형 템플릿으로 생성되어 카테고리별로 반복 패턴이 줄어들도록 구성됩니다.
- 첨부는 DB 메타데이터(파일명/타입/object_key) 기준으로 생성됩니다.
- `DocumentSaved` outbox 이벤트를 `PENDING`으로 upsert하여 파이프라인 재처리가 가능하도록 맞춥니다.

3) front
```bash
pnpm -w install
pnpm -C web-user dev --port 5174
pnpm -C web-admin dev --port 5173
```

### web-user IA v2 (Workspace-first)
- `web-user`는 상단 탭 대신 `Sidebar + Main + Thin Header` 구조를 사용합니다.
- Sidebar 구성:
  - Workspace launcher (멀티 계정/워크스페이스)
    - 계정별 워크스페이스 목록과 전환
    - 계정별 `새 워크스페이스`, `...` 메뉴(워크스페이스 생성/참여, 로그아웃)
    - `다른 계정 추가`에서 로그인 후 계정 세션 추가
    - 회원가입은 별도 `/signup` 화면에서 이메일 인증코드 검증 후 완료
    - `모든 계정에서 로그아웃` 지원
  - Quick actions (`새 페이지`, `검색/Cmd+K`)
  - `Favorites` (사용자별 즐겨찾기 문서, `...` 메뉴에서 토글)
  - `Pages` 문서 트리
    - 기본: 상위 페이지만 노출
    - 하위 페이지: 토글 클릭 시 확장
    - 상위 페이지는 최대 20개만 노출, 초과 시 `... 더보기`로 Library 이동
  - `Views` (`Documents`, `Library`, `Tree`, `Questions`, `Trash`)
- Main은 문서 중심 흐름입니다:
  - 문서 클릭 시 `/w/:workspaceId/doc/:docId` 편집 화면
  - Library에서 상위 페이지 100개/페이지 조회 + 하위 즉시 토글
  - Library 다중 선택 액션: `개인 페이지 상단으로 이동`, `휴지통으로 이동`
  - 문서함/라이브러리/트리/질문함은 Sidebar View 전환으로 접근

주요 라우트:
- `/w/:workspaceId` : 기본 Documents view
- `/w/:workspaceId/doc/:docId` : 문서 에디터
- `/w/:workspaceId/doc/:docId/details` : 파이프라인/첨부/설명 상세
- `/w/:workspaceId/view/documents`
- `/w/:workspaceId/view/library`
- `/w/:workspaceId/view/tree`
- `/w/:workspaceId/view/questions`
- `/w/:workspaceId/view/trash`
- `/login` : 로그인 전용
- `/signup` : 회원가입(이메일 인증코드 2단계)

단축키:
- `Cmd/Ctrl + K`: Command Palette(문서 열기/새 페이지/뷰 이동)
- 에디터에서 `Cmd/Ctrl + S`: 문서 저장

### Block Editor V2 (feature flag)
- 기본값은 TipTap 기반 `EditorV2`가 활성화(`VITE_FEATURE_BLOCK_EDITOR` 미설정 시 true)됩니다.
- 기존 Markdown textarea(`EditorV1`)로 강제하려면 `VITE_FEATURE_BLOCK_EDITOR=false`를 설정합니다.
- `EditorV2`는 `blocks_json`을 저장하고, 서버가 `body_markdown`/`body_text`를 동기화해 기존 검색/임베딩 파이프라인을 유지합니다.
- Slash menu(`/`)는 추천/기본/미디어/데이터 섹션으로 노출되며, 한글/영문 fuzzy 검색과 별칭(`/h1`, `/todo`, `/table`)을 지원합니다.
- 지원 블록(MVP):
  - Text, H1/H2/H3, Bullet/Numbered, Todo, Toggle, Quote, Callout, Divider, Code
  - Table v1, TOC, Image/File upload block, block drag handle
- 문서 응답에는 `created_by`, `updated_by`, `created_at`, `updated_at`가 포함되며 에디터 화면에 표시됩니다.

### Attachment upload policy (editor)
- 업로드 방식: `POST /api/v1/attachments/presign` -> client PUT -> `POST /api/v1/attachments/complete`
- 서버 정책:
  - size: `> 0` and `<= 50MB`
  - content type: `image/*`, `application/pdf`, `application/msword`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `text/plain`, `text/markdown`, `text/csv`, `application/octet-stream`
- 로그에는 파일 본문/문서 본문/블록 원문을 남기지 않습니다.

워크스페이스 컨텍스트:
- 세션은 계정 단위로 `localStorage(autodoc.user.sessions.v2)`에 저장됩니다.
- 활성 계정의 마지막 워크스페이스를 우선 복원합니다.
- 활성 워크스페이스가 없으면 해당 계정의 첫 번째 워크스페이스를 자동 선택합니다.

회원가입/메일 인증 로컬 기본값:
- `docker compose up -d` 시 Mailpit(SMTP/UI)가 함께 실행됩니다.
- 로컬 기본은 **실제 Gmail 발송이 아니라 Mailpit 수신함**으로 전송됩니다.
- 기본 SMTP 연결값:
  - `SMTP_HOST=localhost`
  - `SMTP_PORT=51025`
  - Mail UI: `http://localhost:58025`
- 인증코드 정책:
  - `DOC_API_REGISTER_CODE_TTL_SECONDS` (기본 600초)
  - `DOC_API_REGISTER_MAX_ATTEMPTS` (기본 5회)

실제 SMTP(Gmail 등)로 바꾸려면:
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS_ENABLE` 값을 실제 SMTP에 맞게 설정
- Gmail은 일반 비밀번호가 아니라 앱 비밀번호를 사용

워크스페이스 초대 토큰 보안 기본값:
- DB에는 토큰 원문이 아닌 `SHA-256` 해시만 저장
- 토큰 만료는 7일, 수락 후 재사용 불가(1회용)
- `DOC_API_INVITE_REQUIRE_EMAIL_MATCH=true` 기본값으로 초대 이메일과 로그인 이메일이 다르면 수락 거부(`403`)

### Tree rebuild status UX (refresh-safe)
- 트리 화면은 `GET /api/v1/tree/rebuild/status`로 `QUEUED/RUNNING/IDLE`를 동기화합니다.
- `QUEUED/RUNNING` 상태는 워크스페이스별로 `localStorage`에 캐시되며, 새로고침 직후에도 상태 배너/버튼 문구를 복원합니다.
- 캐시 TTL은 기본 15분이며, 서버에서 `IDLE` 응답을 받으면 캐시를 정리합니다.
- 상태 엔드포인트가 일시 실패해도 캐시된 진행 상태를 우선 유지해 UX 공백을 줄입니다.

### Document open resilience (attachments)
- 문서 상세 조회 시 첨부파일 `download_url` presign 생성이 일부 실패해도 문서 본문 로딩은 실패하지 않도록 처리합니다.
- 이 경우 실패한 첨부만 `download_url: null`로 내려가며, API 전체는 `200`을 유지합니다.
- `EditorV2` 이미지 노드뷰는 mount 초기 시점에서 `editor.view.dom` 접근 오류를 방지하도록 가드되어, 이미지 블록 포함 문서도 안정적으로 열립니다.

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
