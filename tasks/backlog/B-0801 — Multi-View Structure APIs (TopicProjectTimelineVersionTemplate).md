# B-0801 — Multi-View Structure APIs (TopicProjectTimelineVersionTemplate)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
하나의 트리로 모든 관계를 담지 않고, 목적별 뷰를 분리해 섞임을 줄인다.

## Context
topic/project/version/timeline은 서로 다른 신호로 만들어야 한다. v12는 최소 3뷰(Topic/Project/Version)를 제공한다.

## Scope
- API: `GET /v1/trees?view=topic|project|timeline|version|template`
- snapshot은 view별로 저장(또는 동일 snapshot에 view partition)
- version chain: duplicate fingerprint 기반

## Non-goals
복잡한 사용자 커스터마이징(초기 제외)

## Deliverables
API/DB 스키마 확장 + view 라우팅

## Acceptance Criteria
view 전환 시 다른 구조가 노출되고, topic 섞임이 줄어든다

## Task Breakdown
- [ ] view enum/스키마 정의
- [ ] tree_snapshot/ node/ membership에 view 반영
- [ ] version chain 생성 로직(중복 탐지 티켓과 연동)
- [ ] API 라우팅 및 캐시

## Test Plan
integration: view별 snapshot 생성/조회

## Observability
view별 요청량/latency

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
