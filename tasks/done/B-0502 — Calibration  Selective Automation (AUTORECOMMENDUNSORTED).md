# B-0502 — Calibration & Selective Automation (AUTORECOMMENDUNSORTED)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
자동 배치의 오답을 줄이기 위해 p(edge)/p(assign)를 캘리브레이션하고 ‘확신 없으면 유보’ 정책을 강제한다.

## Context
전역 minSimilarity는 의미가 불명확. 확률로 바꾸고, AUTO/추천/유보를 정책으로 고정하면 운영 안정성이 급상승한다.

## Scope
- calibration: isotonic 또는 temperature scaling(간단)
- assign confidence: cluster membership margin/entropy
- 정책: AUTO if conf>=Ta, RECOMMEND if Ta>T>=Tr, else UNSORTED
- per-workspace threshold(초기엔 global + override)

## Non-goals
conformal guarantee(후속)

## Deliverables
Calibrator + 정책 엔진 + 저장/메트릭

## Acceptance Criteria
AUTO 배치 비율은 유지하되 사용자 수정률(Correction Rate)이 감소

## Task Breakdown
- [ ] 피드백 기반 학습 데이터 생성(positive/negative)
- [ ] 캘리브레이터 구현 및 모델 저장(로컬 파일/DB)
- [ ] assign confidence 계산 및 정책 적용
- [ ] threshold override/관리 API(간단)
- [ ] 회귀 테스트(임계값 시나리오)

## Test Plan
integration: 샘플 라벨로 calibrate → AUTO/UNSORTED 분류 검증

## Observability
autoRatio, correctionRate, recommendRatio, unsortedRatio

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
