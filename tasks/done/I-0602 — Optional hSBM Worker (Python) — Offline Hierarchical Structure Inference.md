# I-0602 — Optional: hSBM Worker (Python) — Offline Hierarchical Structure Inference

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
계층 구조(상위/하위)를 모델 기반으로 유도하기 위한 hSBM 추론 워커를 제공한다(옵션).

## Context
2-depth 재군집은 단순하지만, 허브/브릿지 내성이 부족할 수 있다. hSBM은 계층 구조에 강하고 안정적이다.

## Scope
- python worker `services/structure-worker`
- 입력: doc graph(희소), constraints
- 출력: hierarchical clusters + confidence
- doc-api는 결과를 import하여 tree snapshot 생성

## Non-goals
대규모 성능 최적화

## Deliverables
worker + compose profile + import API

## Acceptance Criteria
옵션 활성화 시 2~3 depth 트리가 더 안정적으로 생성된다

## Task Breakdown
- [ ] 입력/출력 스키마 정의(JSON)
- [ ] worker 구현 및 로컬 실행
- [ ] doc-api import 경로 구현
- [ ] 문서화 및 샘플 데이터

## Test Plan
integration: worker 결과 import → snapshot 생성

## Observability
worker runtime, import failures

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
