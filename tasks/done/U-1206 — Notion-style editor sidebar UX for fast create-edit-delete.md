# U-1206 — Notion-style editor sidebar UX for fast create-edit-delete

## Context
- 현재 `web-user`의 에디터는 단일 작성 폼 중심이라, 문서 탐색/하위 문서 추가/빠른 액션(즐겨찾기, 링크 복사, 이름 변경, 삭제)이 비효율적이다.
- 사용자는 노션처럼 좌측 트리형 문서 탐색과 hover 액션(`+`, `…`)을 요구한다.

## Goal
- 에디터 화면을 노션 스타일 UX로 개편해 문서 생성/편집/삭제/탐색을 한 화면에서 빠르게 수행할 수 있게 한다.

## Non-goals
- 서버 스키마 변경(문서 parent/favorite 컬럼 추가)
- 트리 알고리즘/분류 정책 변경

## Scope
- `web-user` 에디터 페이지를 2-pane 구조(좌측 문서 트리 + 우측 편집기)로 개편
- 좌측 문서 항목 hover 시 `+`(하위 문서 생성), `…`(문서 액션 메뉴) 노출
- 액션 메뉴: 즐겨찾기 토글, 링크 복사, 이름 바꾸기, 휴지통 이동
- 우측 편집기: 제목/본문 수정, 저장, 삭제, Cmd/Ctrl+S 저장
- 워크스페이스별 로컬 상태로 parent/favorite 저장(localStorage)

## API / Contracts
- 기존 문서 API 재사용
  - `GET /documents`
  - `GET /documents/{id}`
  - `POST /documents`
  - `PATCH /documents/{id}`
  - `DELETE /documents/{id}`
- API 스키마 변경 없음

## DB / Index changes
- 없음

## Happy path
- 사용자가 에디터에서 기존 문서를 트리로 탐색한다.
- 문서 hover 후 `+`로 하위 문서를 즉시 추가한다.
- `…` 메뉴에서 즐겨찾기/링크복사/이름변경/삭제를 수행한다.
- 우측 편집기에서 저장하면 즉시 반영된다.

## Edge cases
- 워크스페이스 전환 시 로컬 트리 상태는 workspace key로 분리
- 문서 삭제 시 해당 문서의 하위 매핑은 상위 루트로 자동 승격
- 선택 문서가 삭제되면 인접 문서 또는 신규 생성 문서를 선택

## Acceptance Criteria
- [x] 좌측 트리 hover 시 `+`, `…` 액션이 보인다.
- [x] 하위 문서 생성/문서 이름변경/삭제가 에디터에서 가능하다.
- [x] 즐겨찾기/부모 관계는 workspace별로 로컬 유지된다.
- [x] Cmd/Ctrl+S로 저장 가능하다.

## Testing
- Frontend build: `pnpm -C web-user build`

### Result
- [x] `pnpm -C web-user build` 통과

## Observability
- 클라이언트 동작이며 추가 서버 로그/민감정보 로그 없음

## Rollout / Rollback
- 롤아웃: web-user 배포 후 에디터 탭에서 즉시 사용
- 롤백: 해당 커밋 revert

## Security / Privacy
- tenant header 사용 경로 변경 없음(api client 재사용)
- 민감 텍스트/본문 로그 출력 추가 없음
