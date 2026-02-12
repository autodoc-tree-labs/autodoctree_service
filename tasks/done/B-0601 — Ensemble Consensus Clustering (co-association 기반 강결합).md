# B-0601 — Ensemble Consensus Clustering (co-association 기반 강결합)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
단일 알고리즘 결과 흔들림을 줄이기 위해 여러 군집 결과의 합의로 강결합을 만들고 최종 클러스터를 안정화한다.

## Context
데이터가 애매할수록 파라미터 민감도가 커진다. 합의(co-association)는 운영 안정성에 매우 효과적.

## Scope
- 2~3개의 clusterer(Leiden/HDBSCAN/HAC) 결과를 생성
- co-association matrix(샘플링/희소화) 계산
- P_same>=t 인 문서쌍만 strong edge로 확정
- strong edge 그래프에서 최종 커뮤니티 추출

## Non-goals
대규모 최적화(초기엔 로컬 규모)

## Deliverables
ConsensusClusterer + 설정키 + 메트릭

## Acceptance Criteria
파라미터 변경 시 movedRatio/cluster split-merge 변동이 감소

## Task Breakdown
- [ ] 클러스터러 인터페이스 정리
- [ ] co-association 계산(희소) 구현
- [ ] strong edge threshold 적용
- [ ] 메트릭/로그 추가
- [ ] 회귀 테스트(재현성)

## Test Plan
integration: 동일 입력에서 seed 고정 → 결과 재현

## Observability
consensusStrength, unstableClusterCount

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
