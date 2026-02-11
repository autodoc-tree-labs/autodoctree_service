# A-0502 — Admin: Threshold & Policy Controls (workspace override)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
관리자가 워크스페이스별 자동화 정책(임계값)을 제어할 수 있게 한다.

## Context
팀마다 문서 특성이 달라 threshold가 다를 수 있다. UI로 손쉽게 조정해야 튜닝/운영이 가능하다.

## Scope
- workspace 설정 화면: Ta/Tr, quarantine on/off, reranker on/off
- 변경 이력/audit 로그

## Non-goals
복잡한 실험 플랫폼(초기 제외)

## Deliverables
web-admin 설정 UI + API + audit

## Acceptance Criteria
설정 변경 즉시 다음 리빌드부터 적용되고, audit에 남는다

## Task Breakdown
- [ ] 설정 스키마 확정(DB)
- [ ] Admin UI 폼/검증
- [ ] API 구현 및 권한 체크
- [ ] audit 로그

## Test Plan
integration: 설정 변경 → rebuild → 정책 반영 확인

## Observability
설정 변경 이벤트 로그

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
