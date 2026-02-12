# B-0701 — ConceptPrototype Store v1 (클러스터 프로토타입 기반 증분 배치)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
클러스터를 ‘문서 묶음’이 아니라 ‘개념 노드(prototype)’로 관리해 증분 업데이트를 안정화한다.

## Context
매번 full rebuild는 흔들림이 크다. prototype(centroid/대표 문서/키워드)을 유지하면 새 문서 배치가 안정된다.

## Scope
- concept 테이블: prototype vector(채널별), label, exemplars
- 새 문서: doc→concept similarity로 1차 배치(확신 낮으면 유보)
- 주기적으로 concept 업데이트(EM-lite)

## Non-goals
완전한 온라인 학습(초기엔 간단)

## Deliverables
concept schema + updater + assigner

## Acceptance Criteria
새 문서 유입 시 전체 트리 재구성이 아닌 부분 업데이트로도 안정적 배치

## Task Breakdown
- [ ] concept DB 스키마 추가
- [ ] prototype 계산/업데이트 로직
- [ ] doc→concept 배치 로직 + 유보
- [ ] rebuild에서 concept 활용(선배치)
- [ ] 회귀 테스트(증분 시나리오)

## Test Plan
integration: 초기 rebuild → concept 생성 → 신규 doc 배치

## Observability
incrementalAssignRate, conceptCount, conceptDrift

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
