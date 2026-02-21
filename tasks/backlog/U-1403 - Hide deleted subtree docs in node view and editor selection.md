# U-1403 - Hide Deleted Subtree Docs In Node View And Editor Selection

## Context
- 상위 페이지 삭제가 subtree soft-delete로 바뀌었지만, 재빌드/노드 분류 화면에서 삭제 문서가 UUID fallback으로 보이는 현상이 있다.
- `/w/:workspaceId/doc/:documentId`에서 이미 삭제된 문서 id로 진입 시 404 화면이 남아 UX가 끊긴다.

## Goal
- 삭제 문서는 노드 분류/사이드바/에디터 선택 목록에 다시 나타나지 않도록 보장한다.

## Scope
- 백엔드 `GET /api/v1/tree/active` 응답에서 삭제된 문서 membership 필터링.
- 프론트 노드 트리 렌더에서 live documents 기준 방어 필터 추가(문서 id fallback 노출 금지).
- 사이드바에서 부모 삭제 시 현재 라우트가 삭제 subtree 문서면 루트로 안전 이동.
- 에디터 선택 문서 조회 404 시 목록 재동기화 및 선택 해제.

## Acceptance Criteria
- [ ] 재빌드 후 부모 삭제 시 노드 분류에 삭제 문서(UUID)가 표시되지 않는다.
- [ ] 삭제 subtree 문서 id로 열린 에디터는 루트/문서 목록으로 복귀한다.
- [ ] 문서 삭제 후 favorites/선택 상태/사이드바가 subtree 기준으로 정리된다.

## Testing
- [ ] `DocumentHierarchyIntegrationTest`에 active tree payload 필터 검증 추가.
- [ ] `web-user/tests/app-shell.spec.ts`에 stale membership 숨김 회귀 테스트 추가.
