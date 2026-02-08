# B-0306 — TenantSearchClient (non-removable tenant filter) + asserts


## Goal
OpenSearch 쿼리에서 tenant filter 누락을 구조적으로 방지.

## Scope
- TenantSearchClient(workspaceId).search(spec)
- bool.filter에 term(workspace_id=...) 주입 (제거 불가)
- dev/prod assert:
  - prod: fail closed (deny)
  - dev: throw to catch early

## Acceptance Criteria
- OS 쿼리는 전부 wrapper 통과
- filter 누락 테스트로 검출

