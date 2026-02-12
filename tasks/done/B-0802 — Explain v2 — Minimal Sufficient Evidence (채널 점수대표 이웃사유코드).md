# B-0802 — Explain v2 — Minimal Sufficient Evidence (채널 점수대표 이웃사유코드)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
배치 결과를 ‘짧고 검증 가능’하게 설명해 신뢰를 만든다.

## Context
설명이 길면 피로. ‘왜’의 최소 근거(이웃 2개 + 채널 점수 + 사유 코드)만 제공하는 게 운영에서 가장 효과적.

## Scope
- explain payload: top neighbors(2~3), channel score breakdown, gate pass, reason codes
- LLM 문장(옵션): 실패 시 TF-IDF fallback
- PII 금지(본문 원문 노출 없음)

## Non-goals
장문 요약/리포트

## Deliverables
ExplainBuilder + API 응답 + 저장(rationale_json)

## Acceptance Criteria
사용자가 explain을 보고 1~2번 클릭으로 ‘수용/수정’할 수 있다

## Task Breakdown
- [ ] Explain payload 스키마 정의
- [ ] Neighbor/Score 기반 근거 생성
- [ ] LLM 옵션 라우팅(실패 graceful)
- [ ] 회귀 테스트(스키마/PII 마스킹)

## Test Plan
integration: explain 생성 → schema/마스킹 검증

## Observability
explain shown rate, accept rate(후속)

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
