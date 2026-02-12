# B-0501 — Pairwise Validation Stage B (Reranker Gate) — Edge Precision Boost

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
후보 쌍(topK) 중 실제 edge로 남길 쌍을 reranker로 정밀 검증한다.

## Context
v12 그래프 품질=엣지 품질. reranker gate를 통과한 쌍만 edge로 남기면 cluster purity가 크게 오른다.

## Scope
- Candidate: ANN/mutual-kNN 결과
- Validate: reranker score → p(edge) 업데이트
- Budget: 문서당 validate pair 수 상한(성능)
- Fallback: reranker down 시 기존 hybrid sim로 degrade

## Non-goals
학습형 reranker 튜닝(초기엔 고정)

## Deliverables
NeighborBuilder에 Stage B 추가 + 설정키 + fallback

## Acceptance Criteria
reranker on/off 비교 시 bridge edge/hubCount 감소, movedRatio 개선

## Task Breakdown
- [ ] reranker client 구현(배치)
- [ ] pair text 구성(title+summary+top sections) 규칙 확정
- [ ] validate budget 및 캐시(동일 pair 중복 방지)
- [ ] fallback/degrade 구현
- [ ] 벤치/회귀 테스트

## Test Plan
integration: reranker mock로 점수 주입 → edge 선택 검증

## Observability
validatedPairs, passRate, fallbackRate

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
