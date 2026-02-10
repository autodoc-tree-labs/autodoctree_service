# B-0110 — Document CRUD v1 + pipeline_status in response


## Goal
문서 생성/조회/수정/삭제(soft) + 파이프라인 상태 노출.

## Scope
- POST/GET/PATCH/DELETE /documents
- GET /documents list (pagination/filter)
- optimistic locking (version)
- GET document response includes pipeline_status & attachments summary

## Acceptance Criteria
- cross-tenant doc access denied
- update conflict 409
- body 내용 로그 금지

