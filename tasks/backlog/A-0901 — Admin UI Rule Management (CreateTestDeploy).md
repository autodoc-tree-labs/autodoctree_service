# A-0901 — Admin UI: Rule Management (CreateTestDeploy)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
관리자가 규칙을 쉽게 만들고 테스트할 수 있게 한다.

## Context
규칙은 운영 강력 도구이지만, UI/테스트가 없으면 위험하다.

## Scope
- 규칙 목록/생성/수정
- ‘테스트’ 기능: 샘플 docId 넣고 어떤 노드로 갈지 preview
- 변경 이력/audit

## Non-goals
대규모 규칙 배포 파이프라인

## Deliverables
web-admin 룰 관리 화면

## Acceptance Criteria
룰을 만들고 1분 내 preview 후 적용 가능

## Task Breakdown
- [ ] CRUD UI
- [ ] Preview 호출/표시
- [ ] 권한/audit

## Test Plan
UI smoke

## Observability
rule changes

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
