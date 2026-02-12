# B-0402 — Bridge & Hub Quarantine v2 (혼합 멤버십유보 사유 모델)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
브릿지/허브 문서를 자동으로 감지해 트리를 망치지 않도록 ‘격리/유보’로 처리한다.

## Context
브릿지는 제거가 아니라 격리가 정답. 자동 배치 오답을 줄이려면 ‘확신 낮은 문서’를 구조적으로 unsorted로 보낸다.

## Scope
- hubness score: (degree, local scaling, neighbor diversity)
- bridge score: 커뮤니티 분산(여러 클러스터에 비슷하게 연결)
- assignment confidence/margin 계산
- 정책: confidence 낮으면 `UNSORTED`, 사유 코드 저장(LOW_CONFIDENCE / HUB / TEMPLATE / CONFLICT)
- explain에 사유 표시

## Non-goals
Active Learning 질문 큐(0902에서)

## Deliverables
QuarantinePolicy + 저장 스키마 + UI용 DTO

## Acceptance Criteria
브릿지 문서가 강제로 폴더에 들어가지 않고 unsorted에 남으며, 사용자가 확정하면 수렴한다

## Task Breakdown
- [ ] hub/bridge score 정의 및 구현
- [ ] assignment confidence/margin 계산
- [ ] 유보 사유 코드/저장(멤버십 확장)
- [ ] Tree snapshot 생성에서 unsorted 처리 반영
- [ ] 회귀 테스트(브릿지 샘플)

## Test Plan
integration: 브릿지 샘플 입력 → unsorted 이동 + 사유 노출

## Observability
hubCount, unsortedReason histogram

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
