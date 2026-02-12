# I-0201 — Ollama Embedding Setup (bge-m3) — Offline Bootstrap & Healthchecks

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
로컬 오프라인에서 bge-m3 임베딩을 안정적으로 제공한다.

## Context
stub 임베딩은 품질 한계. 오프라인 전제로도 모델 pull/캐시/헬스체크가 안정적이어야 한다.

## Scope
- compose `llm` profile에서 ollama 런타임 구동
- `bge-m3` 모델 pull 가이드(자동 init 옵션 포함)
- 헬스체크/재시도 정책

## Non-goals
클라우드 모델 호스팅

## Deliverables
- `infra/docker-compose.yml`(ollama-init optional)
- `DEV_SETUP.md` 업데이트

## Acceptance Criteria
로컬에서 `FEATURE_EMBEDDING_OLLAMA=true`, `EMBEDDING_PROVIDER=ollama` 설정 시 임베딩 생성 성공

## Task Breakdown
- [ ] ollama-init 서비스(옵션) 추가
- [ ] 모델명/환경변수 표준화
- [ ] 헬스체크 및 재시도 옵션 정리
- [ ] 문서화

## Test Plan
수동: compose up → pull → doc ingest → embedding 생성 확인

## Observability
ollama latency, error rate, queue backlog

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
