# I-1001 — Offline Model Packaging & Bootstrap (bge-m3 llama3.1 reranker clip)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
완전 오프라인 환경에서도 모델을 일관되게 배포/캐시/검증할 수 있게 한다.

## Context
오프라인은 ‘한 번 받아두면 끝’이 아니라, 버전/경로/헬스체크/재현성이 중요하다.

## Scope
- 모델 레지스트리 폴더 구조(버전 고정)
- 체크섬/manifest로 무결성 검증
- compose 시작 시 모델 존재 검사(없으면 안내)
- DEV_SETUP에 오프라인 반입 절차

## Non-goals
온라인 자동 다운로드

## Deliverables
`models/manifest.json` + bootstrap 스크립트 + 문서

## Acceptance Criteria
동일 zip/폴더를 다른 PC로 옮겨도 동일 모델로 재현 가능

## Task Breakdown
- [ ] manifest 스키마/체크섬 생성 스크립트
- [ ] compose init 검사 스크립트
- [ ] DEV_SETUP 문서화

## Test Plan
수동: 모델 폴더 비움 → 안내 / 채움 → 헬스체크 통과

## Observability
model version labels in logs

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
