# B-0302 — Template Detection & Template Bin (양식푸터 반복 문서 격리)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
템플릿/양식 유사로 인해 ‘비슷해 보이지만 의미가 다른’ 문서가 섞이는 문제를 차단한다.

## Context
영수증/정형 서류/리포트 템플릿은 공통 푸터/헤더 때문에 lexical/embedding이 올라갈 수 있다. 이를 별도 bin으로 격리해야 트리가 안정화된다.

## Scope
- template score 계산(반복 n-gram, boilerplate ratio)
- template cluster(type=template)로 격리하거나, topic tree에서 제외
- debug/explain에 ‘템플릿 격리’ 근거 표시

## Non-goals
완벽한 템플릿 분류(초기엔 heuristic)

## Deliverables
TemplateScorer + Tree 정책(격리) + 저장 컬럼

## Acceptance Criteria
템플릿 문서가 topic cluster를 오염시키지 않고 별도 영역으로 이동

## Task Breakdown
- [ ] template score 정의/계산
- [ ] 격리 정책(임계값/옵션) 구현
- [ ] UI 연동을 위한 노드 타입 추가
- [ ] 회귀 테스트(템플릿 샘플)

## Test Plan
unit: scorer / integration: 템플릿+정상 문서 혼합 데이터에서 cluster purity 개선 확인

## Observability
template doc ratio, template bin size

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
