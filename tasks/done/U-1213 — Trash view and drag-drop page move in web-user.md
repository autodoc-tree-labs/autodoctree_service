# U-1213 — Trash view and drag-drop page move in web-user

## Context
- 사용자는 사이드바 페이지에서 삭제한 문서를 다시 복원할 수 있는 휴지통 기능을 원한다.
- 또한 페이지를 드래그해서 다른 문서 하위 또는 루트 위치로 이동하는 편집 경험이 필요하다.

## Goal
- Workspace 범위에서 휴지통 조회/복원과 드래그 기반 페이지 이동을 안정적으로 제공한다.

## Non-goals
- 문서 본문 편집 포맷 변경
- 트리(가상 폴더) 알고리즘 변경

## Scope
- Backend
  - `GET /documents/trash`
  - `POST /documents/{documentId}/restore`
  - `POST /documents/{documentId}/move`
  - cycle 방지 및 tenant-safe 검증
- Frontend
  - Sidebar Views에 `Trash` 추가
  - Trash 페이지에서 복원 동작 제공
  - Pages 목록에서 drag & drop 이동(하위/루트)
- 문서/API 업데이트

## API / Contracts
- 문서 이동 요청
```json
{ "parent_document_id": "target_doc_id_or_null" }
```

## DB / Index changes
- 추가 테이블 없음(기존 `documents.deleted` 활용)

## Happy path
- 문서를 삭제하면 Trash에서 확인 가능
- Trash에서 복원하면 일반 문서 목록으로 복귀
- Pages에서 문서를 드래그해 다른 문서 하위 또는 루트로 이동

## Edge cases
- 자기 자신/자손 하위로 이동 시 400
- 타 워크스페이스 문서 이동/복원 차단
- 삭제 문서는 일반 목록에 보이지 않고 Trash에만 보임

## Acceptance Criteria
- [x] Trash 목록/복원이 동작한다.
- [x] Drag & Drop 이동이 동작한다.
- [x] tenant negative 테스트가 추가된다.
- [x] web-user e2e가 통과한다.

## Testing
- Backend integration: 이동/복원/cycle/cross-tenant
- Frontend e2e: 드래그 이동/Trash 복원

### Result
- [x] `./gradlew -p services :doc-api:test --tests "com.autodoctree.api.integration.DocumentTrashMoveIntegrationTest" --tests "com.autodoctree.api.integration.TenantIsolationIntegrationTest"` 통과
- [x] `pnpm -C web-user build` 통과
- [x] `pnpm -C web-user test:e2e --grep "renders workspace app shell and view navigation|page row hover shows quick actions and context menu|favorite toggle from page menu updates favorites section|drag and drop page row moves document under another document|trash view lists deleted documents and supports restore|mobile menu button opens fallback menu panel"` 통과

## Observability
- audit log에 `document.move`, `document.restore` 기록
- 본문/첨부 원문 로그 금지

## Rollout / Rollback
- 롤아웃: API + web-user 동시 배포
- 롤백: 변경 커밋 revert

## Security / Privacy
- WorkspaceContext 기반 테넌트 범위 검증 유지
