# U-0801 — User UI: Multi-View Navigator (TopicProjectTimelineVersion)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
사용자가 목적에 따라 뷰를 전환하며 문서를 찾을 수 있게 한다.

## Context
v12는 구조 분리가 핵심. UI에서 뷰 전환이 자연스럽지 않으면 효과가 반감된다.

## Scope
- 상단 탭/드롭다운으로 view 선택
- view별 트리/리스트 렌더링
- 선택한 view를 workspace 단위로 기억

## Non-goals
개인별 뷰 커스텀

## Deliverables
web-user view 전환 UI + 상태 저장

## Acceptance Criteria
view 전환으로 ‘섞임’ 체감이 감소하고, 찾기 시간이 줄어든다

## Task Breakdown
- [ ] view selector 컴포넌트 구현
- [ ] API 파라미터 연동
- [ ] view별 빈 상태/권한 처리
- [ ] workspace preference 저장

## Test Plan
UI smoke + contract test

## Observability
viewSwitch events

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
