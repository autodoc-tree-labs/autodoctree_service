# B-1403 — Library hierarchy API and personal top pages storage

## Context
- 문서가 많은 워크스페이스에서 좌측 패널 전량 렌더링은 탐색/성능/가독성 문제가 큽니다.
- 라이브러리 페이지에서 루트+하위 문서를 단일 응답으로 제공하고, 개인 상단 페이지 구성을 서버에 저장할 필요가 있습니다.

## Goal
- 루트 중심 페이지네이션 + 하위 트리 동봉 응답 API와 개인 상단 페이지 저장소를 제공한다.

## Non-goals
- 검색 랭킹 로직 변경
- 물리 파일 이동

## Scope
- `documents/library` 조회 API
- `documents/sidebar` 조회 API (루트 20개 제한 + more)
- `documents/library/personal-top` 반영 API
- `documents/library/bulk-trash` 일괄 삭제 API
- `document_personal_top` migration + repository + service wiring

## API / Contracts
- 신규 endpoint 4종
- `items[].children[]` 트리 응답 포함
- tenant mismatch 시 403/404 fail-closed

## DB / Index changes
- Flyway `V16__document_personal_top.sql`

## Acceptance Criteria
- [ ] 라이브러리 응답은 page root + descendants 동봉
- [ ] 사이드바 응답은 개인 top 우선 + 20개 제한
- [ ] 일괄 action은 subtree까지 처리
- [ ] 삭제 시 personal top 참조 정리
- [ ] tenant negative test 포함

## Testing
- integration tests: library/sidebar/list/bulk/personal-top + tenant isolation

## Observability
- payload에 문서 본문 로그 금지
- action별 count metric/log

## Security / Privacy
- workspace membership 강제
- 루트 문서 검증
