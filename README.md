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

OpenSearch reindex + alias swap helper:
```bash
./scripts/opensearch_reindex_alias_swap.sh docs-active docs-v1-000002
```

Offline model manifest workflow:
```bash
python3 scripts/model_manifest_lock.py --manifest models/manifest.json
python3 scripts/model_manifest_verify.py --manifest models/manifest.json
```

2) backend (IntelliJ)
- `services/doc-api` Run Config (`com.autodoctree.api.DocApiApplicationKt`)
- 기본 포트 기준 추가 env 없이 실행 가능

3) front
```bash
pnpm -w install
pnpm -C web-user dev --port 5174
pnpm -C web-admin dev --port 5173
```

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
