# B-0602 — hSBM Import Path + Fallback (worker 결과를 Tree로 반영)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
(옵션) hSBM 결과를 받아 트리 생성에 사용하고, 실패 시 기존 clusterer로 fallback 한다.

## Context
python worker는 실패/지연이 있을 수 있다. 운영 안정성을 위해 degrade 전략이 필요.

## Scope
- worker 호출/타임아웃/재시도
- 결과 검증(스키마, 제약 위반)
- fallback: consensus/Leiden 경로

## Non-goals
worker 내부 최적화

## Deliverables
WorkerClient + ImportValidator + fallback

## Acceptance Criteria
worker 다운 상황에서도 트리 생성이 지속된다

## Task Breakdown
- [ ] worker client 구현
- [ ] 결과 검증 로직 구현
- [ ] fallback 경로 연결
- [ ] 테스트(실패/타임아웃)

## Test Plan
integration: worker 오류 주입 → fallback 확인

## Observability
workerFallbackRate

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
