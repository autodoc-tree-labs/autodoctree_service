# A-0902 — Admin: Question Triage & Analytics (품질부하 모니터링)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
질문 시스템이 과도한 부하를 만들지 않도록 관리한다.

## Context
질문이 너무 많아지면 사용성이 떨어진다. 생성/소비/효과를 분석해야 한다.

## Scope
- 질문량/답변률/효과(unsorted 감소) 대시보드
- 강제 만료/비활성화

## Non-goals
A/B 테스트 플랫폼

## Deliverables
web-admin 페이지 + API

## Acceptance Criteria
질문 폭증 상황에서 즉시 제어 가능

## Task Breakdown
- [ ] analytics 화면
- [ ] controls(disable/expire)

## Test Plan
UI smoke

## Observability
question metrics

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
