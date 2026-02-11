# U-0402 — User UI: Confidence-aware Placement (후보 폴더 Top-3 제안 + 원클릭 확정)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
유보 문서에 대해 사용자가 최소 노력으로 확정할 수 있도록 후보 폴더 제안을 제공한다.

## Context
유보 정책이 강해질수록 UI에서 확정 UX가 없으면 사용성이 떨어진다. Top-3 후보 + 원클릭 확정으로 피드백 효율을 올린다.

## Scope
- 유보 문서 카드에 Top-3 추천 폴더 표시
- 클릭 시 `confirmPlacement(docId, targetNodeId)` 이벤트 발행
- 확정 후 즉시 UI 반영(optimistic)

## Non-goals
대규모 추천 모델(초기엔 backend score 기반)

## Deliverables
추천 UI + API 연동 + 상태관리

## Acceptance Criteria
유보 문서 10개를 1분 내 확정 가능(클릭 기반)

## Task Breakdown
- [ ] 추천 폴더 UI 컴포넌트 구현
- [ ] 확정 API 호출 및 optimistic update
- [ ] 에러/롤백 처리
- [ ] 분석 이벤트(확정 클릭) 기록

## Test Plan
UI smoke + contract test

## Observability
confirm clicks, time-to-fix(가늠치)

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
