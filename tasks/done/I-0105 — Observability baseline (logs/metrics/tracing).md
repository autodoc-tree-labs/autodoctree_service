# I-0105 — Observability baseline (logs/metrics/tracing)


## Goal
운영 가능한 관측성 베이스라인을 깐다.

## Scope
- services:
  - JSON structured logging (trace_id, request_id, workspace_id)
  - Micrometer metrics (latency, 5xx, worker success/fail, lag)
  - (선택) OpenTelemetry tracing
- web:
  - client request_id 생성 및 header 전파

## Acceptance Criteria
- /metrics endpoint에 핵심 지표 노출
- 민감 데이터 로그 금지 가드(테스트 포함)

## Security
- 본문/추출텍스트/첨부내용 로그 금지

