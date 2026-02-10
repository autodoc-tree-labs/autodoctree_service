# U-0127 — Tree doc click-through to document detail

## Goal
트리 화면의 "선택 노드의 문서"에서 문서를 클릭하면 문서 상세 페이지로 이동한다.

## Scope
- web-user 트리 페이지의 문서 리스트 렌더링 수정
- 문서 제목 클릭 시 `/documents/{id}` 라우팅
- 기존 드래그 이동 기능 유지

## Non-goals
- 트리 알고리즘/스냅샷 로직 변경 없음
- 백엔드 API 계약 추가 변경 없음

## Acceptance Criteria
- [ ] 선택 노드 문서 제목 클릭 시 문서 상세로 이동한다.
- [ ] UUID만 보이는 UX가 아닌 제목 중심 표시가 유지된다.
- [ ] 드래그 이동 기능이 동작한다.

## Testing
- web-user build 통과
- 수동: 트리 페이지에서 문서 제목 클릭 시 상세 페이지 이동 확인

## Rollout / Rollback
- 프론트엔드 배포로 롤아웃
- 문제 시 해당 UI 커밋 롤백
