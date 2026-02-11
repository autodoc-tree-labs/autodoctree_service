# U-0114 — Observability hooks (client request_id) + safe logging


## Goal
클라이언트 관측성(요청 상관관계) 최소 구현.

## Scope
- request_id 생성 후 모든 API 요청에 헤더 첨부
- 클라이언트 로그는 민감정보 제외(개발 모드에서만)

## Acceptance Criteria
- 서버 로그와 request_id로 추적 가능

