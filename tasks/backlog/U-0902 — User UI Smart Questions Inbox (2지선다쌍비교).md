# U-0902 — User UI: Smart Questions Inbox (2지선다쌍비교)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
사용자가 ‘질문’으로 빠르게 정리를 수렴시킬 수 있게 한다.

## Context
드래그보다 더 빠른 2지선다/쌍비교 UI가 효율적일 수 있다.

## Scope
- 질문 인박스 화면
- 질문 카드: A vs B, 또는 같은가?
- 답변 후 즉시 반영(optimistic)

## Non-goals
복잡한 워크플로

## Deliverables
질문 UI + API 연동

## Acceptance Criteria
질문 5개를 30초 내 처리 가능

## Task Breakdown
- [ ] 질문 목록/카드 UI
- [ ] 답변 API 연동
- [ ] 상태/에러 처리

## Test Plan
UI smoke

## Observability
answer latency, completion rate

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
