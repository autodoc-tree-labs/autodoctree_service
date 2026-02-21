# U-1212 — Workspace-user persisted document favorites for sidebar Pages

## Context
- 현재 즐겨찾기는 에디터 로컬 스토리지 상태로만 저장되어, 앱 쉘 사이드바(Pages)와 공유되지 않고 새 브라우저/세션/기기에서 유지되지 않는다.
- 사용자는 사이드바 `...` 메뉴에서 즐겨찾기를 추가하면 `Pages` 상단 `Favorites` 영역에서 즉시 확인되길 원한다.

## Goal
- 즐겨찾기를 사용자-워크스페이스 범위로 DB에 영속 저장하고, 앱 쉘 사이드바에서 일관되게 조회/토글한다.

## Non-goals
- 트리 알고리즘/분류 정책 변경
- 문서 본문 검색/인덱싱 로직 변경

## Scope
- Flyway 마이그레이션으로 `document_favorite` 테이블 추가
- `GET /documents/favorites`, `POST /documents/{id}/favorite`, `DELETE /documents/{id}/favorite` 추가
- 문서 삭제 시 해당 문서 즐겨찾기 정리
- 웹 앱 사이드바에 `Favorites` 섹션 추가(페이지 목록 상단)
- `...` 메뉴에 즐겨찾기 토글 항목 추가

## API / Contracts
- `GET /documents/favorites`
- `POST /documents/{documentId}/favorite`
- `DELETE /documents/{documentId}/favorite`
- `API_SURFACE.md` 반영

## DB / Index changes
- Flyway `V13__document_favorites.sql`
- PK: `(workspace_id, user_id, document_id)`

## Happy path
- 사용자가 사이드바 페이지 메뉴에서 즐겨찾기에 추가한다.
- 서버가 workspace/user 범위로 저장한다.
- 사이드바 `Favorites` 목록에 문서가 즉시 표시된다.
- 즐겨찾기 해제 시 목록에서 제거된다.

## Edge cases
- 다른 워크스페이스 문서를 즐겨찾기하려 하면 실패(NotFound/Forbidden)
- 이미 즐겨찾기된 문서 재추가 요청은 멱등 처리
- 삭제된 문서는 목록에서 자동 제외

## Acceptance Criteria
- [x] 즐겨찾기가 DB에 저장되고 재시작 후 유지된다.
- [x] `...` 메뉴에서 즐겨찾기 토글 가능하다.
- [x] `Favorites`가 `Pages` 위에 노출된다.
- [x] 테넌트 음성 테스트가 추가된다.

## Testing
- Backend integration: 즐겨찾기 CRUD + cross-tenant negative
- Frontend e2e: 메뉴 토글 및 Favorites 섹션 반영

### Result
- [x] `./gradlew -p services :doc-api:test --tests "com.autodoctree.api.integration.DocumentFavoriteIntegrationTest" --tests "com.autodoctree.api.integration.TenantIsolationIntegrationTest"` 통과
- [x] `pnpm -C web-user build` 통과
- [x] `pnpm -C web-user test:e2e --grep "renders workspace app shell and view navigation|page row hover shows quick actions and context menu|favorite toggle from page menu updates favorites section|mobile menu button opens fallback menu panel"` 통과

## Observability
- 문서 본문 로그 없이 `workspace_id`, `document_id`, `user_id`, 성공/실패 상태만 로그/감사에 기록

## Rollout / Rollback
- 롤아웃: 마이그레이션 적용 후 web-user 배포
- 롤백: API/프론트 커밋 revert + 마이그레이션은 비파괴 테이블 유지

## Security / Privacy
- 모든 요청은 `WorkspaceContextResolver`를 통과해 workspace 멤버십 검증
- workspace 범위 문서 존재 검증 후 즐겨찾기 저장
