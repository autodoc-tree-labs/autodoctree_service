# B-0102 — Tree Debug API v1 (문서별 근거이웃클러스터 상태 조회)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
품질 이슈를 재현할 때 API만으로 원인을 파고들 수 있게 디버그 조회를 제공한다.

## Context
클러스터가 섞였을 때 UI만 보고는 원인을 알 수 없다. 문서별 이웃/게이트/점수/클러스터 소속/유보 사유를 조회해야 한다.

## Scope
- `GET /v1/debug/tree/docs/{docId}`: neighbors(topN), channel scores, edge decisions, assignment confidence
- `GET /v1/debug/tree/clusters/{clusterId}`: members, exemplars, label candidates
- `GET /v1/debug/tree/rebuilds/{snapshotId}`: 파라미터/모델/결정 요약

## Non-goals
운영에서 일반 사용자 노출(권한 admin-only)

## Deliverables
Controller/DTO + RBAC guard + 샘플 응답 fixtures

## Acceptance Criteria
admin 권한에서만 접근 가능, 본문/첨부 원문 노출 없음, 디버그 응답으로 ‘왜 유보/왜 연결’이 설명된다

## Task Breakdown
- [ ] 디버그 DTO 설계(PII 최소화)
- [ ] repo 쿼리 추가(assignment/edge/cluster 상태)
- [ ] RBAC(admin) 적용
- [ ] fixtures 기반 contract test 추가

## Test Plan
integration: 샘플 데이터 세팅 → debug API 호출 → schema/마스킹 검증

## Observability
debug API 호출량/latency

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
