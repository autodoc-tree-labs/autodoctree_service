# A-1101 — Admin: Audit & Data Access Logs (workspace boundary visibility)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
관리자가 테넌트 경계 관련 이벤트(설정 변경/디버그 조회/룰 변경)를 추적할 수 있게 한다.

## Context
운영에서는 ‘누가 무엇을 봤는지/바꿨는지’가 신뢰의 기반이다.

## Scope
- audit 로그 리스트/필터
- 민감정보 없음
- export(옵션)

## Non-goals
SIEM 연동

## Deliverables
admin 페이지 + API

## Acceptance Criteria
주요 변경/조회 이벤트가 audit로 남고 조회 가능

## Task Breakdown
- [ ] audit API 연동
- [ ] 페이지 구현
- [ ] 필터/정렬

## Test Plan
UI smoke

## Observability
audit volume

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
