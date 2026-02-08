# Plans.md — AutoDoc Tree (Monorepo, v1 GA)

## 0) What “GA” means here
- 로컬에서 전체 플로우 동작:
  - web-user에서 문서 작성/업로드
  - 비동기 파이프라인(ingest→embed→index→tree) 진행 상태 확인
  - search(BM25) 가능
  - tree active 조회 + explain 가능
  - move/rename 피드백 수집 + 최소 개인화 반영(가중치/룰)
- 멀티테넌시(Workspace=Tenant) 누수 방지:
  - API/DB/OpenSearch/Redis/S3 전부 scope 강제
  - CI(E2E)에서 wsA/wsB 누수 테스트 통과
- 운영 기초:
  - admin job console(재시도), audit log, 관측/런북

---

## 1) Milestones
### M0 — Monorepo bootstrap + local infra
- I-0101~0106

### M1 — Backend foundation (auth/tenant/docs/upload)
- B-0101~0120

### M2 — Async pipeline foundation (outbox/workers/idempotency/status)
- B-0201~0210

### M3 — Ingest + Search (BM25) + Index
- B-0301~0312

### M4 — Embeddings + Tree snapshots + Explain
- B-0401~0420

### M5 — Feedback + Personalization v1 + Stability
- B-0501~0510

### M6 — Web User (core UX)
- U-0101~0125

### M7 — Web Admin (ops/governance)
- A-0101~0115

---

## 2) Ticket Index (ready-to-implement)
### Infra (I-xxxx)
- I-0101 Monorepo layout + tooling (pnpm workspace + services gradle root)
- I-0102 docker-compose (Postgres/OpenSearch/Redis/MinIO) + healthchecks
- I-0103 CI gates (build/test/lint/format) for services + web
- I-0104 Local dev docs (DEV_SETUP.md) + HTTP smoke scripts
- I-0105 Observability baseline (logs/metrics/tracing wiring)
- I-0106 Secrets/config/feature flags baseline

### Backend (B-xxxx)
- B-0101 Services scaffold + /health
- B-0102 Flyway baseline schema (tenant tables)
- B-0103 Auth v1 (access/refresh rotation)
- B-0104 Workspace + membership RBAC
- B-0105 WorkspaceContext resolver + tenant enforcement middleware
- B-0106 DB tenant repository guardrails + constraints
- B-0107 Audit log core
- B-0110 Document CRUD v1 + pipeline_status in response
- B-0111 Attachment presign/complete (S3 namespace + ownership)
- B-0201 Outbox events (at-least-once)
- B-0202 Worker runtime (retry/backoff/DLQ)
- B-0203 Stage idempotency keys + execution log
- B-0204 Pipeline status registry updates
- B-0205 Admin job retry endpoints
- B-0301 Text extractor v1 (Tika) + quality flags
- B-0302 Section splitter + chunker
- B-0303 OpenSearch template + alias strategy
- B-0304 Indexer worker upsert/delete
- B-0305 Search API BM25 (TenantSearchClient enforced)
- B-0306 TenantSearchClient (non-removable tenant filter) + asserts
- B-0401 Embedding provider abstraction (stub for local)
- B-0402 Embedding worker + storage
- B-0410 Neighbor builder (TopK) per workspace
- B-0411 Clustering v1 (2-depth)
- B-0412 Labeler v1 (keywords/representatives)
- B-0413 Snapshot persistence + tree APIs (active/snapshots/activate/lock)
- B-0414 Rationale generator (keywords+similar docs+signals)
- B-0415 Explain API
- B-0501 Feedback move/rename APIs + events
- B-0502 Immediate membership patch on move (or defined async behavior)
- B-0503 Personalization v1 (boost moved docs / centroid routing)
- B-0504 Snapshot stability policy (recommended/apply change limit)
- B-0505 Debounce/coalesce rebuild scheduler
- B-0510 Multi-tenant E2E security test suite (wsA/wsB) — GA Gate

### Web User (U-xxxx)
- U-0101 web-user scaffold (Vite/React/TS) + routing
- U-0102 Auth UI (login/refresh handling) + session store
- U-0103 Workspace selector + header indicator (X-Workspace-Id)
- U-0104 Documents Inbox/List (status badges) + pagination
- U-0105 Document editor (markdown) + save
- U-0106 Upload UI (presign/complete) + progress
- U-0107 Document detail (pipeline status panel)
- U-0108 Search UI (BM25) + results
- U-0109 Tree view (active snapshot) + doc listing per node
- U-0110 Explain drawer/panel
- U-0111 Drag&Drop move + rename label UI
- U-0112 Snapshot switch/apply (recommended → active)
- U-0113 Error handling (401/403/5xx) + retry UX
- U-0114 Observability hooks (client request_id) + safe logging
- U-0120 Basic settings (profile, theme optional)
- U-0125 E2E smoke flows (Playwright) for user app

### Web Admin (A-xxxx)
- A-0101 web-admin scaffold (Vite/React/TS) + routing
- A-0102 Admin auth gating + workspace switcher (operator safety)
- A-0103 Jobs console (stage status, retry)
- A-0104 Audit log viewer (filters)
- A-0105 Workspace/member management UI
- A-0106 Ingest inspector (extracted meta, not raw text) — minimal
- A-0107 Search diagnostics (query builder view, tenant filter check) — minimal
- A-0110 Dangerous operation guardrails (confirm workspace name)
- A-0115 Admin E2E smoke (Playwright) + CI

> 각 티켓 상세는 `tasks/backlog/`에 있음.
