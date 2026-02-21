# U-1304 - Sync Sidebar Title After Editor Save

## Context
- 문서 편집 화면에서 제목을 저장해도 좌측 Pages 패널의 제목이 즉시 갱신되지 않아 사용자가 저장 실패로 오해할 수 있다.
- 현재 편집 화면(`WorkspaceDocumentEditorPage`) 저장 성공 후 문서 본문만 다시 로드하고, `Layout`의 사이드바 문서 목록 상태는 별도로 갱신되지 않는다.

## Goal
- 문서 제목 저장 성공 시 좌측 패널의 페이지 트리/즐겨찾기 제목이 즉시 최신값으로 반영된다.

## Non-goals
- 문서 저장 API 스펙 변경.
- 사이드바 렌더 구조 전면 개편.

## Scope
- 웹 앱 내부 문서 변경 동기화 이벤트 추가.
- `Layout`에서 동기화 이벤트 수신 시 사이드바 문서/즐겨찾기(노드 모드면 노드 트리 포함) 재조회.
- 문서 편집 페이지에서 저장/생성/삭제 성공 시 동기화 이벤트 발행.
- 회귀 방지를 위한 E2E 테스트 1건 추가.

## API / Contracts
- 외부 API 변경 없음.
- 내부 브라우저 이벤트 계약 추가:
  - 이벤트명: `autodoc.sidebar.documents.sync`
  - detail: `{ workspaceId: string | null }`

## DB / Index changes
- 없음.

## Happy path
1. 사용자가 문서 편집 화면에서 제목을 수정하고 저장한다.
2. 저장 성공 후 편집 화면이 동기화 이벤트를 발행한다.
3. `Layout`이 이벤트를 수신해 사이드바 문서 목록을 다시 조회한다.
4. 좌측 Pages/Favorites 제목이 즉시 최신 제목으로 표시된다.

## Edge cases
- 이벤트의 `workspaceId`가 현재 활성 워크스페이스와 다르면 무시한다.
- 워크스페이스 미선택 상태에서는 동기화 동작을 수행하지 않는다.

## Acceptance Criteria
- [ ] 문서 제목 저장 후 좌측 Pages 리스트 제목이 새 제목으로 갱신된다.
- [ ] 문서 제목 저장 후 좌측 Favorites 제목도 새 제목으로 갱신된다(해당 문서가 즐겨찾기인 경우).
- [ ] 워크스페이스가 다른 탭/경로에는 영향이 없다.
- [ ] 관련 E2E 테스트가 통과한다.

## Testing
- Unit tests: 없음(프론트 이벤트 wiring 중심).
- Integration tests: 없음.
- E2E tests:
  - 문서 편집 화면에서 제목 저장 후 사이드바 제목 즉시 반영 검증.

## Observability
- 로그/메트릭 변경 없음.

## Rollout / Rollback
- 프론트엔드 코드 배포만 필요.
- 문제 시 이벤트 수신/발행 로직만 롤백 가능.

## Security / Privacy
- 테넌트 식별자는 기존 활성 워크스페이스 범위만 사용.
- 문서 본문/민감정보를 이벤트 payload나 로그에 포함하지 않는다.
