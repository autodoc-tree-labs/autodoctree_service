# I-0501 — Local Reranker Service (Cross-Encoder) — Offline Inference

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
pairwise 검증(Stage B)을 위한 로컬 reranker를 제공해 브릿지 엣지를 고정밀로 제거한다.

## Context
임베딩 cosine만으로는 애매한 쌍이 많다. reranker(교차 인코더)는 precision을 크게 올려 오답 자동 배치를 줄인다.

## Scope
- `services/reranker-api` (Python FastAPI) 추가
- 모델: 로컬에서 실행 가능한 한국어/다국어 reranker(ONNX 가능)
- API: `POST /v1/rerank/pairs` → score
- docker compose profile `ml` 추가

## Non-goals
GPU 최적화/대규모 서빙

## Deliverables
reranker service + dockerfile + 모델 캐시 경로 + DEV_SETUP

## Acceptance Criteria
로컬에서 doc-api가 reranker 호출하여 p(edge) 정밀도가 개선된다

## Task Breakdown
- [ ] reranker 모델 선정(오프라인, 허용 라이선스)
- [ ] FastAPI 엔드포인트 구현(배치)
- [ ] ONNX/CPU 최적화 옵션(선택)
- [ ] compose 통합 및 헬스체크
- [ ] 문서화 및 샘플 호출 스크립트

## Test Plan
integration: doc-api→reranker 호출 성공, latency budget 확인

## Observability
reranker latency, error rate, QPS

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
