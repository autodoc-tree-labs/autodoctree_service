# B-0702 — Tree Extraction Optimizer (목표함수 기반 + 변경비용 정규화)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
트리 생성 자체를 최적화 문제로 정의하여 안정성/제약/UX 목표를 동시에 만족한다.

## Context
movedRatio만으로 ACTIVE/RECOMMENDED를 나누면, 구조가 ‘왜’ 좋은지 설명이 부족. 목표함수로 트리 품질을 정의하면 운영 정책이 일관된다.

## Scope
- 목적함수: fitScore - λ*changeCost - μ*cannotViolations - ν*sizePenalty
- depth 제한(≤3), 폴더 크기 제약
- local search(부분 변경)로 최적화
- 결과: ACTIVE/RECOMMENDED 결정 근거 저장

## Non-goals
글로벌 최적해(ILP)

## Deliverables
TreeOptimizer + 파라미터 + 설명 데이터

## Acceptance Criteria
새 문서 소량 유입 시 트리 급변이 줄고, RECOMMENDED로만 노출되는 경우가 증가

## Task Breakdown
- [ ] 목표함수 정의 및 파라미터 구성
- [ ] changeCost 계산(이전 스냅샷 대비)
- [ ] local search 구현(스왑/이동)
- [ ] 결과 저장(점수 breakdown)
- [ ] 회귀/성능 테스트

## Test Plan
integration: 이전 스냅샷 존재 시 changeCost 반영 검증

## Observability
optimizerIterations, changeCost, objectiveScore

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
