# I-0101 — Observability Stack for Local Dev (OTelPrometheusGrafana) — Tree Dashboards

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
로컬에서도 트리 품질/안정성 지표를 대시보드로 확인할 수 있게 한다.

## Context
v12는 파라미터/모델/정책 변화가 많아, 눈으로 확인할 수 있는 대시보드가 없으면 튜닝이 느려진다.

## Scope
- docker compose profile `observability` 정리
- Grafana 대시보드: Tree rebuild overview / Edge-Graph health / Unsorted & Questions queue
- 기본 알람 룰(로컬은 disabled 가능): movedRatio 급증, rebuild 실패율

## Non-goals
클라우드 운영 알람/온콜(초기 제외)

## Deliverables
- `infra/observability/grafana/dashboards/tree/*.json`
- `infra/observability/prometheus/tree_rules.yml`
- README에 로컬 실행/스크린샷

## Acceptance Criteria
- `docker compose --profile observability up -d` 후 Grafana에서 Tree 대시보드가 로드된다
- rebuild 1회 실행 시 대시보드 패널에 데이터가 표시된다

## Task Breakdown
- [ ] 대시보드 설계(패널/쿼리)
- [ ] 메트릭 scrape 설정 확인
- [ ] 로컬 seed 데이터로 데모 시나리오 추가
- [ ] 문서화(DEV_SETUP/README) 업데이트

## Test Plan
수동 검증: compose up → rebuild → Grafana 패널 값 확인

## Observability
대시보드 패널: latency, movedRatio, edgeCount, avgDegree, clusterSize histogram, unsortedRatio

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
