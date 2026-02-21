# U-1214 — Inline rename in panel menu without browser prompt

## Context
- 현재 페이지 메뉴의 "이름 바꾸기"가 `window.prompt`를 사용해 브라우저 기본 팝업이 노출됩니다.
- 사용 흐름이 끊기고, 패널 내 조작 UX와 일관되지 않습니다.

## Goal
- 페이지 메뉴 안에서 제목을 바로 수정/저장/취소할 수 있도록 인라인 편집으로 전환한다.

## Non-goals
- 백엔드 API 스펙 변경.
- 노드 라벨 이름 변경 UX 변경.

## Scope
- Sidebar Pages 메뉴의 "이름 바꾸기"를 인라인 입력 UI로 전환.
- Editor 트리 메뉴의 "이름 바꾸기"도 동일 방식으로 전환.
- `window.prompt` 제거.
- 최소 E2E 검증 추가.

## API / Contracts
- 기존 `PATCH /api/v1/documents/{id}` 재사용.

## DB / Index changes
- 없음.

## Happy path
- 사용자가 메뉴에서 "이름 바꾸기" 클릭.
- 메뉴 내부 입력창이 열리고 제목 수정.
- 저장 시 즉시 PATCH 호출 후 목록/선택 문서 제목 갱신.

## Edge cases
- 빈 제목: 저장 차단.
- 기존 제목과 동일: 저장 대신 편집 종료.
- Esc: 편집 취소.

## Acceptance Criteria
- [x] 브라우저 prompt가 더 이상 뜨지 않는다.
- [x] 메뉴 내부에서 제목 편집/저장/취소가 가능하다.
- [x] 저장 후 제목이 즉시 반영된다.

## Testing
- E2E: 메뉴 인라인 이름 변경 시나리오 1개 이상.

## Observability
- 기존 API 에러 패널/노티스 재사용.

## Rollout / Rollback
- 프론트 UI 로직 변경이며 롤백은 이전 커밋 revert.

## Security / Privacy
- 민감정보 로그 추가 없음.
