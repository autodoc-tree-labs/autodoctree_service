# B-0901 — User Rule Engine v1 (조건→노드 라우팅 hardsoft)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
사용자/관리자가 ‘이 조건이면 항상 이 폴더’ 같은 규칙으로 구조 안정성을 강화한다.

## Context
완전 자동 군집화는 정답이 없다. 규칙은 반복되는 업무 패턴을 고정해 흔들림을 줄인다.

## Scope
- rule types: entity contains, source type, author, filename ext, tag
- hard rule(강제 배치), soft rule(가중치)
- rule evaluation은 rebuild/증분 배치 모두에 적용

## Non-goals
복잡한 DSL

## Deliverables
rule schema + evaluator + API

## Acceptance Criteria
규칙을 추가하면 다음 리빌드부터 해당 문서가 고정된 폴더로 간다

## Task Breakdown
- [ ] rule 스키마/조건 JSON 정의
- [ ] evaluator 구현(순서/우선순위)
- [ ] 관리 API(CRUD)
- [ ] 회귀 테스트(규칙 충돌)

## Test Plan
integration: rule 생성 → rebuild → 배치 고정

## Observability
ruleHitCount, ruleConflictCount

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
