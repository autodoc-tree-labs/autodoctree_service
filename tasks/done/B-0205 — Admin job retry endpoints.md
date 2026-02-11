# B-0205 — Admin job retry endpoints


## Goal
운영자가 특정 문서의 stage를 재처리할 수 있게 한다.

## Scope
- GET /admin/jobs (filter by document_id)
- POST /admin/jobs/retry (stage)
- audit log 기록

## Acceptance Criteria
- retry 요청 시 outbox 또는 job queue에 재처리 이벤트 생성
- 권한: admin(OWNER) 또는 운영자 정책(추후 강화)

