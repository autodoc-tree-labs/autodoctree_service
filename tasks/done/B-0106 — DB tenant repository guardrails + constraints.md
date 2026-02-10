# B-0106 — DB tenant repository guardrails + constraints


## Goal
workspace 조건 누락 쿼리를 작성하기 어렵게 만든다.

## Scope
- repository pattern:
  - 모든 조회/갱신은 `(workspaceId, id)` 형태를 강제
  - unscoped method 금지(정적 체크/리뷰 룰/테스트)
- schema constraints:
  - unique/index 설계에 workspace_id 포함
- (선택) Postgres RLS는 v1.1로 문서화만

## Acceptance Criteria
- 코드에서 unscoped access 패턴이 존재하지 않음
- 테스트로 cross-tenant DB 조회 방지 확인

