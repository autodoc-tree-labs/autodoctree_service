# U-0107 — Document detail (pipeline status panel)


## Goal
문서 상세 + 파이프라인 상태 표시.

## Scope
- GET /documents/{id}
- pipeline_status 패널(ingest/embed/index/tree)
- failure reason 표시(민감정보 제외)

## Acceptance Criteria
- PROCESSING 중인 문서는 상태가 갱신되어 보임(폴링 또는 SSE는 v1.1)

