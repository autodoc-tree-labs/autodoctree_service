# B-0202 — Embedding Input Quality Scoring (q_bodyq_layoutq_ocr) & Noise Filters

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
PDF 추출 텍스트/스캔 등 노이즈가 많은 입력을 자동 감지해 유사도에 덜 반영한다.

## Context
긴 PDF의 헤더/푸터 반복, OCR 쓰레기 텍스트가 임베딩/lexical을 오염시켜 섞임을 만든다. v12는 입력 품질을 점수화해서 late fusion에서 가중치를 조정한다.

## Scope
- `q_body`: 추출 텍스트 품질(알파벳/한글 비율, 반복률, 엔트로피, 길이, stopword 비율)
- `q_layout`(옵션): PDF 레이아웃 템플릿성(헤더/푸터 반복) 신호
- `q_ocr`(옵션): OCR 결과 품질(문자 다양성/오타율 proxy)
- Tree neighbor 계산에서 `effectiveWeight = baseWeight * q` 적용

## Non-goals
완전한 문서 품질 판별(초기엔 heuristic)

## Deliverables
QualityScorer + 저장 컬럼 + fusion 반영

## Acceptance Criteria
노이즈 높은 문서에서 q_body가 낮아지고, 유사도 edge가 감소/브릿지 문서가 유보로 이동

## Task Breakdown
- [ ] 품질 지표 정의 및 스코어링 구현
- [ ] DB 컬럼 추가(embedding_metadata 또는 document_feature 테이블)
- [ ] fusion 계산에서 q 반영
- [ ] 회귀 테스트(정상 텍스트 q 높음, 노이즈 샘플 q 낮음)

## Test Plan
unit: scorer / integration: 샘플 PDF 텍스트로 q 분포 검증

## Observability
q_body 평균/분포, low-quality doc ratio

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
