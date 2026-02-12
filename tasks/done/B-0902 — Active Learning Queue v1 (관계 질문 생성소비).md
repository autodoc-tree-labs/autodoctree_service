# B-0902 — Active Learning Queue v1 (관계 질문 생성소비)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
확신이 낮은 문서/관계를 최소 질문으로 해소해 빠르게 수렴시킨다.

## Context
유보 정책이 강해질수록 ‘어디로 가야 하지?’가 남는다. 가장 정보이득 큰 질문만 뽑아 사용자에게 제시하면 드래그 비용이 급감한다.

## Scope
- 질문 타입: doc→cluster(후보2), doc-pair same? (must/cannot)
- 선정 기준: margin 낮음, bridge score 높음, 구조 영향도 큼
- 질문 큐 저장/상태(OPEN/ANSWERED/EXPIRED)
- 답변 → constraint( must/cannot ) 생성

## Non-goals
복잡한 추천 모델

## Deliverables
question schema + generator + API

## Acceptance Criteria
질문 3~5개로 유보 문서의 70% 이상이 확정되도록 설계

## Task Breakdown
- [ ] 질문 스키마/상태 모델
- [ ] 선정 알고리즘 구현
- [ ] 답변 → constraint 변환
- [ ] TTL/만료 처리
- [ ] 회귀 테스트(정보이득 기반 선택)

## Test Plan
integration: 유보 데이터 → 질문 생성 → 답변 반영

## Observability
questionsOpen, answerRate, impactScore

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
