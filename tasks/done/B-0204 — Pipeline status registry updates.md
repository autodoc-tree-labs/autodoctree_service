# B-0204 — Pipeline status registry updates


## Goal
문서별 stage 상태(PENDING/RUNNING/DONE/FAILED) 관리.

## Scope
- pipeline_status row maintain
- workers update reliably
- GET /documents/{id} includes pipeline_status

## Acceptance Criteria
- stage 상태 전이가 올바르고 failure reason 노출(민감정보 없음)

