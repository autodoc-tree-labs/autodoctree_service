# B-1401 - Cascading Soft Delete For Document Hierarchy

## Context
- 현재 문서 삭제는 대상 문서만 soft-delete되고, 하위 문서는 `parent_document_id = null`로 살아남는다.
- 사용자 기대는 상위 페이지 삭제 시 하위 페이지도 함께 삭제되는 트리 기반 삭제다.
- 문서 트리 UX(`문서로 분류`)와 서버 동작이 불일치해 혼동을 유발한다.

## Goal
- 상위 문서 삭제 시 해당 문서 subtree(자기 자신 + 모든 하위 문서)를 일괄 soft-delete한다.

## Non-goals
- hard delete(물리 삭제) 도입.
- 복원 API를 subtree 단위 복원으로 확장.

## Scope
- `DocumentRepository`에 recursive subtree soft-delete 쿼리 추가.
- `DocumentService.deleteDocument`를 subtree 삭제로 변경.
- subtree 전체 즐겨찾기 제거.
- subtree의 각 문서에 대해 `DocumentDeleted` outbox 이벤트 enqueue(검색 인덱스 정합성 유지).
- 통합 테스트 기대치 갱신(기존 parent null 정책 제거).

## API / Contracts
- 외부 엔드포인트 스펙은 유지.
- `DELETE /documents/{documentId}`의 동작 의미를 명시적으로 변경:
  - 기존: 대상 문서만 삭제
  - 변경: 대상 문서 subtree 전체 삭제

## DB / Index changes
- 스키마 변경 없음.
- `documents` table recursive CTE 기반 update 사용.

## Happy path
1. 부모 문서를 삭제한다.
2. 서버가 subtree id를 계산한다.
3. subtree 문서가 모두 `deleted=true, status=DELETED` 처리된다.
4. 목록 API에서는 subtree 문서가 보이지 않고, trash API에서는 subtree 문서가 조회된다.
5. 검색 인덱스 정리를 위해 각 문서별 삭제 이벤트가 outbox에 기록된다.

## Edge cases
- 하위가 없는 단일 문서 삭제는 기존과 동일하게 동작.
- 이미 삭제된 문서 id는 `404` 처리.
- 다른 workspace의 문서는 recursive 범위에 절대 포함되지 않는다.

## Acceptance Criteria
- [ ] 부모 삭제 후 자식/손자 문서 `GET`이 `404`를 반환한다.
- [ ] 부모/자식/손자 문서가 모두 trash 목록에 존재한다.
- [ ] 기존 move/cycle/trash-restore 테스트가 회귀하지 않는다.
- [ ] 검색 인덱스 정리 이벤트가 subtree 문서 수만큼 enqueue된다.

## Testing
- Unit tests: 없음.
- Integration tests:
  - `DocumentHierarchyIntegrationTest`에서 부모 삭제 시 subtree 삭제 검증.
- E2E tests(선택): 추후 UI 삭제 플로우 검증.

## Observability
- 기존 audit/outbox를 재사용.
- 민감 본문 로그 추가 금지.

## Rollout / Rollback
- 배포 후 즉시 적용.
- 문제 발생 시 delete 경로를 기존 단일 문서 soft-delete로 롤백 가능.

## Security / Privacy
- recursive 쿼리는 `workspace_id`로 강제 스코프.
- cross-tenant 삭제 전파 방지.
