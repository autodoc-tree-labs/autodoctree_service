# U-1207 — Editor sidebar toggle between node grouping and document grouping

## Context
- 현재 에디터 좌측 목록은 문서 중심(페이지 리스트)으로만 보인다.
- 사용자는 노션형 편집 흐름을 유지하면서, 트리 노드 기준 분류 결과와 문서 기준 목록을 버튼으로 전환해서 보고 싶어한다.

## Goal
- 에디터 좌측에 보기 전환 버튼을 추가해 `문서로 분류` / `노드로 분류`를 즉시 토글할 수 있게 한다.

## Scope
- `web-user` 에디터 페이지에 2-state 토글 UI 추가
- `문서로 분류`: 기존 페이지 트리(로컬 parent/favorite 기반)
- `노드로 분류`: 활성 topic 트리 스냅샷(`/trees?view=topic`) 기준으로 노드/문서 계층 렌더링
- 노드 보기에서도 문서 클릭 시 우측 편집기 로드

## Non-goals
- 백엔드 API/스키마 변경
- 트리 알고리즘/클러스터링 정책 변경

## Acceptance Criteria
- [x] 에디터 좌측에 보기 전환 버튼이 있다.
- [x] `문서로 분류`와 `노드로 분류`를 즉시 전환할 수 있다.
- [x] 노드 보기에서 문서를 클릭하면 우측 편집기에서 수정 가능하다.

## Testing
- `pnpm -C web-user build`

### Result
- [x] `pnpm -C web-user build` 통과
