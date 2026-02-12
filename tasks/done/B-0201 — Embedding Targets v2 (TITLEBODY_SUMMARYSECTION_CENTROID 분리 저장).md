# B-0201 — Embedding Targets v2 (TITLEBODY_SUMMARYSECTION_CENTROID 분리 저장)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
문서 임베딩을 1개(DOCUMENT) 혼합에서 채널 분리로 전환하여 섞임(bridge)을 줄인다.

## Context
현재 DOCUMENT 임베딩 입력에 title/body/section이 섞여 1벡터가 되고, 긴 PDF/노이즈가 유사도에 과도하게 영향. v12는 채널 분리 + late fusion을 기본으로 한다.

## Scope
- embedding target_type 확장: TITLE, BODY_SUMMARY, SECTION, SECTION_CENTROID
- body_summary 입력은 정제(head/tail + 길이 제한 + 노이즈 제거)
- section 임베딩에서 centroid(상위 N 섹션 평균) 생성 저장
- Tree 리빌드에서 `DOCUMENT-only` 조회 → 채널 결합 조회로 변경

## Non-goals
임베딩 모델 자체 교체(별도)

## Deliverables
- DB 마이그레이션: embedding target_type/메타 컬럼
- `EmbeddingInputPreprocessor` 채널별 payload 생성
- `EmbeddingRepository` 채널별 list API

## Acceptance Criteria
- 동일 문서에 대해 TITLE/BODY_SUMMARY/SECTION_CENTROID 벡터가 저장된다
- Tree neighbor 계산에서 채널별 similarity가 계산/로그된다

## Task Breakdown
- [ ] 마이그레이션(타겟 타입/인덱스) 추가
- [ ] 채널별 입력 생성 규칙 정의 + 코드 구현
- [ ] centroid 생성/저장 로직 구현
- [ ] repo 조회 API 추가 및 TreeAlgorithms 반영
- [ ] 회귀 테스트(기존 DOCUMENT 경로 유지/마이그레이션 호환)

## Test Plan
unit: centroid 계산, 입력 생성 길이 제한 / integration: 파이프라인 실행 후 embedding row 수 검증

## Observability
embedding 생성 성공률/latency, 채널별 벡터 존재율

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
