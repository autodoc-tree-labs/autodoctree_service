# U-1301 — User UI Notion-style minimal workspace shell

## Context
- 현재 `web-user` 화면은 카드/배지/그라디언트가 많아 정보 우선순위가 흐리고, 문서 탐색 시 피로도가 높다.
- 사용자는 노션처럼 좌측 내비게이션 + 우측 단순 콘텐츠 영역의 미니멀한 UI를 요구한다.

## Goal
- `web-user`의 공통 레이아웃과 문서 탐색 화면을 노션형 미니멀 UI로 개편해 가독성과 집중도를 높인다.

## Non-goals
- 문서 분류/트리 알고리즘, API 계약, 인증 방식 변경
- 테넌트 스코프/권한 처리 로직 변경

## Scope
- 공통 `Layout`을 좌측 사이드바 + 우측 콘텐츠 구조로 변경
- 상단 breadcrumb/검색 단축 진입 UI 추가
- 과도한 카드성 스타일(그라디언트, 과한 그림자, 높은 채도 배지) 축소
- `Inbox`/`Tree` 중심 화면을 행(row) 중심의 단순한 밀도 UI로 재스타일링
- 모바일에서 사이드바가 상단 접힘/스크롤 가능한 형태로 동작하도록 반응형 조정

## API / Contracts
- 기존 API 재사용, 계약 변경 없음
  - `GET /documents`
  - `GET /trees`
  - `POST /tree/rebuild`
  - `POST /feedback/move`
  - `POST /feedback/rename`

## DB / Index changes
- 없음

## Happy path
- 사용자가 로그인 후 좌측 사이드바에서 메뉴를 선택한다.
- 우측 콘텐츠 영역에서 문서 목록/트리를 단순한 구조로 탐색한다.
- 이동/잠금/이름변경 등 기존 동작은 동일하게 수행된다.

## Edge cases
- 워크스페이스 미선택 상태에서 기존 가드 UI를 유지한다.
- 좁은 화면에서 사이드바가 콘텐츠를 가리지 않고 상단 내비게이션으로 전환된다.
- explain drawer/모달 오버레이가 레이아웃 변경 후에도 정상 표시된다.

## Acceptance Criteria
- [ ] 공통 레이아웃이 노션형 2-pane 구조로 변경된다.
- [ ] 색상/그림자/배지 스타일이 미니멀 톤으로 정리된다.
- [ ] `Inbox`/`Tree` 화면이 과도한 카드형에서 단순 탐색형 UI로 바뀐다.
- [ ] `pnpm -C web-user build`가 통과한다.

## Testing
- Frontend build: `pnpm -C web-user build`

## Observability
- 클라이언트 UI 변경이며 서버 로그/메트릭 스키마 변경 없음
- 민감 텍스트/본문 로그 추가 없음

## Rollout / Rollback
- 롤아웃: web-user 배포 후 즉시 반영
- 롤백: 해당 커밋 revert

## Security / Privacy
- `X-Workspace-Id` 헤더 전달 경로 변경 없음
- 테넌트 분리 로직 및 접근 제어 동작 변경 없음
