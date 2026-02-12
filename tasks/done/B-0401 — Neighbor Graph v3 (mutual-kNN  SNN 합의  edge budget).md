# B-0401 — Neighbor Graph v3 (mutual-kNN + SNN 합의 + edge budget)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
브릿지 엣지를 줄이기 위해 그래프 생성 정책을 kNN 단순 방식에서 합의 기반(SNN)으로 고도화한다.

## Context
topK만으로는 우연히 비슷한 문서가 연결되어 연결요소가 커지며 섞임이 발생한다. mutual-kNN과 shared-neighbor agreement(SNN)가 실무 정석.

## Scope
- mutual-kNN: A→B, B→A 모두 topK일 때만 후보
- SNN: Jaccard(N(A),N(B)) >= t 인 경우만 edge 확정
- per-node edge budget(저점 edge 제거)
- edge type: topic_candidate vs project_candidate(확장 대비)

## Non-goals
SBM/Ensemble(0600에서)

## Deliverables
NeighborBuilder 개편 + 설정 키 + 메트릭

## Acceptance Criteria
샘플 데이터에서 avgDegree는 유지하되 hub/bridge edge가 감소하고 cluster purity가 상승

## Task Breakdown
- [ ] mutual-kNN 구현
- [ ] SNN agreement 구현(Jaccard/overlap)
- [ ] edge budget 정책(저점 컷)
- [ ] 메트릭/로그에 agreement 통과율 추가
- [ ] 회귀 테스트(그래프 특성 스냅샷)

## Test Plan
unit: SNN 계산 / integration: 그래프 생성 후 edgeCount/degree 분포 검증

## Observability
mutual pass rate, SNN pass rate, avgDegree, hubCount

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
