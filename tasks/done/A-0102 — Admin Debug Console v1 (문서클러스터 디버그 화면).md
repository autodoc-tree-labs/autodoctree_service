# A-0102 — Admin Debug Console v1 (문서클러스터 디버그 화면)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
관리자가 UI에서 문서 정리 문제를 빠르게 진단할 수 있게 한다.

## Context
백엔드 디버그 API가 있어도, 운영/개발자가 UI로 빠르게 확인할 수 있어야 튜닝 속도가 빨라진다.

## Scope
- 문서 디버그 페이지: docId 입력 → neighbors/score breakdown/유보 사유
- 클러스터 디버그: clusterId 선택 → 멤버/대표 문서/라벨 후보
- 파라미터 스냅샷 보기

## Non-goals
일반 사용자 제공

## Deliverables
web-admin 페이지 + API 연동 + 기본 레이아웃

## Acceptance Criteria
10분 내 문제 문서를 입력해 원인을 파악할 수 있는 정보가 UI에 표시된다

## Task Breakdown
- [ ] Doc Debug UI 구성
- [ ] Cluster Debug UI 구성
- [ ] API 연동 및 에러 처리
- [ ] 권한/라우팅 가드 추가

## Test Plan
UI smoke test + API contract test

## Observability
관리자 페이지에서 trace_id 표시

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
