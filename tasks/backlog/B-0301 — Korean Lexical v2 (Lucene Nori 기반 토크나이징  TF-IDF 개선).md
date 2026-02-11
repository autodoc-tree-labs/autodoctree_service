# B-0301 — Korean Lexical v2 (Lucene Nori 기반 토크나이징 + TF-IDF 개선)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
lexical 신호가 ‘헛스윙’하지 않도록 한국어 토크나이징을 개선하고 gate/overlap 신뢰도를 올린다.

## Context
현재 단순 토큰 normalize는 조사/복합명사/형태소 처리에서 한계. Nori를 사용하면 title/body 텍스트에서 의미 토큰이 잘 뽑혀 lexical 보정이 실제로 작동한다.

## Scope
- Lucene analyzers-nori 도입(서버 내 토크나이저)
- 사용자 사전(프로젝트명/도메인 용어) 지원
- TF-IDF 벡터 생성/캐시 최적화
- lexical gate를 `token overlap + BM25-lite` 조합으로 개선

## Non-goals
OpenSearch 인덱스 분석기 변경(별도 I 티켓)

## Deliverables
NoriTokenizerAdapter + TFIDF 개선 + 설정 키

## Acceptance Criteria
동일 문서군에서 lexical similarity가 의미 있게 분리/보정되고, 브릿지 엣지가 감소

## Task Breakdown
- [ ] nori 의존성 추가 및 tokenizer wrapper 구현
- [ ] stopword/숫자/단위 필터 룰 정의
- [ ] TF-IDF 캐시/메모리 가드(큰 문서 방지)
- [ ] neighborBuilder gate 로직 교체
- [ ] 성능 측정(리빌드 시간/메모리)

## Test Plan
unit: tokenizer / integration: 샘플 문서에서 token set 스냅샷 비교

## Observability
lexical token count, TF-IDF compute time, gate pass rate

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
