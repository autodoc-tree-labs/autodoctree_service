# B-1101 — Multi-Tenancy Hardening v2 (DBSearchCacheObjectStorage leak tests)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
워크스페이스 멀티테넌시를 누수 테스트까지 포함해 강화한다.

## Context
트리/검색/캐시/오브젝트스토리지는 누수 위험이 크다. v12로 구조가 복잡해질수록 안전장치가 필요.

## Scope
- 모든 query에 workspace_id 강제(DAO layer guard)
- OpenSearch index routing/필터 확인
- Redis key namespace 점검
- S3/minio path prefix 강제
- e2e 누수 테스트(다른 workspace에서 접근 시 404)

## Non-goals
기업용 RBAC 고도화

## Deliverables
TenantGuard + 누수 회귀 테스트 세트

## Acceptance Criteria
자동 테스트에서 20개 누수 시나리오가 모두 차단됨

## Task Breakdown
- [ ] DAO tenant guard 추가
- [ ] Search/Cache/ObjectStorage namespace 검증
- [ ] e2e 누수 테스트 추가
- [ ] 문서화(runbook)

## Test Plan
e2e: workspace A/B 생성 → 교차 조회 차단

## Observability
tenant violation counter

## Implementation Notes
### Files/Modules to Touch (suggested)
- `services/doc-api/src/main/...` (Tree/Embedding/Worker modules)
- `services/doc-api/src/main/resources/application.yml` (feature flags/config)
- `services/doc-api/src/test/...` (unit/integration tests)
- `infra/observability/...` (metrics dashboards/rules) — if applicable
- `web-user/...` or `web-admin/...` — if applicable

### Config Keys (add/update)
- Add keys under `tree.*`, `embedding.*`, `lexical.*`, `llm.*` as appropriate.
- All new keys must have:
  - default value (safe/off by default)
  - env override mapping in `.env.example`
  - documented in `DEV_SETUP.md` and `README.md`

### Performance Budgets
- Tree rebuild: p95 ≤ 3s for 200 docs, ≤ 10s for 2,000 docs (local CPU)
- Memory: avoid O(N^2) materialization; use top-k/sparse structures.
- Reranker stage (if enabled): p95 ≤ 50ms per pair batch(64) on CPU.

### Rollout / Migration
- DB migrations must be backward compatible (read old + new during transition)
- Feature flags:
  - Ship code with flag OFF → add metrics → enable on a dev workspace
  - Provide a rollback path (disable flag, keep old behavior)

### Done Definition
- Code merged + tests green
- Docs updated (`README.md`, `DEV_SETUP.md`, `API_SURFACE.md` if API touched)
- Observability: at least 1 metric + 1 log line for the new behavior
