# B-0101 — Tree Rebuild Telemetry v1 (분해된 메트릭로그디버그 스냅샷)

- Status: TODO
- Priority: P1
- Owner: Codex
- Created: 2026-02-11

## Goal
트리 리빌드가 왜 섞이는지/왜 이동하는지 원인 추적이 가능한 수준으로 로그/메트릭/디버그 데이터를 표준화한다.

## Context
현재는 rebuild 결과(스냅샷)만 보이고, edge/클러스터/라벨링 단계의 내부 상태가 부족하면 품질 개선이 감으로 흐른다. v12부터는 pairwise/graph/cluster/assign 단계가 늘어나므로 관측 설명가능성이 필수다.

## Scope
- `tree_rebuild_summary` 로그를 단계별로 확장(ingest/embed/pairwise/graph/cluster/assign/tree_extract)
- 분포 메트릭: similarity(p_edge) 분포, degree 분포, cluster size 분포, unsorted 비율
- 디버그 스냅샷(PII 제외): 특정 문서의 top neighbors + 채널별 점수(title/body/sec/lex) + gate/threshold 통과 여부
- 워크스페이스 단위 rebuild trace_id 전파(OTel)

## Non-goals
- 본문/첨부 원문 로그/저장(금지)
- UI 화면 개선(별도 U 티켓)

## Deliverables
- Kotlin: `TreeTelemetry.kt`(공용) + 단계별 측정 wrapper
- 로그 스키마 문서 `docs/telemetry/tree_rebuild.md`
- Prometheus 메트릭(또는 OTel metric) 키 정의

## Acceptance Criteria
- 리빌드 1회당 `summary` 1개 + `stage` 로그 N개가 일관 스키마로 남는다
- 샘플 문서 10개에 대해 디버그 엔드포인트가 채널 점수/근거를 반환(PII 마스킹)
- CI에서 로그 스키마 스냅샷 테스트 통과

## Task Breakdown
- [ ] 단계별 stopwatch/metric helper 추가
- [ ] rebuild trace_id/request_id를 모든 단계로 전파
- [ ] degree/cluster size/unsorted/edge type 분포 메트릭 추가
- [ ] debug DTO(PII 마스킹) 설계 및 샘플 응답 고정 테스트
- [ ] 문서화 및 예시 로그 첨부

## Test Plan
- unit: metric helper, log DTO 마스킹
- integration: 샘플 워크스페이스에서 rebuild 실행 → 메트릭/로그 key 존재 검증

## Observability
- RED: rebuild latency, error rate
- Quality: movedRatio, unsortedRatio, avgClusterSize, avgDegree, hubDocCount

## Risks & Mitigations
디버그 데이터가 민감정보를 포함할 수 있음 → title/body는 hash+길이만 노출, 키워드/엔티티는 allowlist 기반

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
