# U-0802 — User UI: Explain Drawer v2 (근거이웃사유) + 수용되돌림

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
사용자가 배치 근거를 확인하고 수용/수정 피드백을 쉽게 남길 수 있게 한다.

## Context
Explain은 개인화 학습의 입력이기도 하다. UI에서 accept/reject 흐름이 있어야 한다.

## Scope
- 문서 카드에서 ‘왜 여기?’ 클릭 → drawer
- 근거 표시: 이웃 2개, 점수 바, 사유 코드
- 버튼: ‘수용’(accept), ‘다른 폴더로’(drag/추천)

## Non-goals
고급 분석 UI

## Deliverables
Explain drawer + API 연동 + 이벤트

## Acceptance Criteria
Explain 확인 후 수용/수정 흐름이 막힘 없이 동작

## Task Breakdown
- [ ] Drawer UI 구현
- [ ] Explain API 연동
- [ ] Accept 이벤트 발행
- [ ] Telemetry(accept/reject)

## Test Plan
UI smoke

## Observability
explain open rate, accept rate

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
