# U-0302 — User UI: Template Bin & Unsorted Inbox (격리유보 영역 UX)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
템플릿 격리/유보 문서를 사용자가 쉽게 확인/정리할 수 있는 UX를 제공한다.

## Context
v12는 ‘틀릴 것 같으면 유보’가 핵심 전략이므로 Unsorted/Template 영역 UX가 품질과 직결된다.

## Scope
- 좌측 트리: `Inbox (Unsorted)` / `Templates` 고정 노드
- 문서 카드에 ‘유보 사유’ 배지 표시
- 드래그로 확정 시 피드백 이벤트 수집

## Non-goals
고급 필터/검색 UX(별도)

## Deliverables
web-user 트리/리스트 UI + API 연동

## Acceptance Criteria
유보 문서를 드래그 3~5번으로 안정화시키는 흐름이 가능

## Task Breakdown
- [ ] 고정 노드 UI 추가(Inbox/Templates)
- [ ] 유보 사유/점수 배지 표시
- [ ] 드래그 피드백 이벤트 호출
- [ ] 빈 상태/로딩/에러 처리

## Test Plan
UI e2e smoke: 유보→드래그→재빌드→배치 반영

## Observability
drag events count, unsorted ratio 변화

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
