# I-0301 — OpenSearch Korean Analyzer (nori) for Search Index + Field Mapping

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
검색 품질(키워드/하이라이트) 개선 및 트리/검색 신호 일관성을 위해 OpenSearch에 nori analyzer를 적용한다.

## Context
서버 내부 lexical과 검색 인덱스 분석기가 다르면 사용자 체감/설명이 어긋난다.

## Scope
- index template에 nori analyzer 설정
- title/body/keyword field mapping 정리
- reindex + alias swap 절차 문서화

## Non-goals
벡터 검색 파라미터 튜닝(별도)

## Deliverables
opensearch template json + migrate script + docs

## Acceptance Criteria
nori 기반 검색이 동작하고, alias swap로 무중단 전환 가능

## Task Breakdown
- [ ] nori analyzer 설정 추가
- [ ] field별 analyzer 지정
- [ ] reindex 스크립트/문서 작성
- [ ] 로컬 compose 이미지/플러그인 확인

## Test Plan
수동: 샘플 검색 쿼리에서 토큰화/결과 확인

## Observability
search p95, zero-result rate

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
