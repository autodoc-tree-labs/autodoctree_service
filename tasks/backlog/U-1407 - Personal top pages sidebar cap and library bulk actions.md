# U-1407 — Personal top pages sidebar cap and library bulk actions

## Context
- 현재 좌측 Pages 패널은 상/하위 문서를 모두 펼쳐 렌더링하여 정보량이 과도하고, 문서 수가 많은 워크스페이스에서 탐색성이 낮습니다.
- 상위 페이지 20개 노출, 더보기 기반 라이브러리 탐색, 일괄 상단 고정/휴지통 이동 UX가 필요합니다.

## Goal
- 좌측 패널은 상위 페이지 20개만 기본 노출하고, 라이브러리 페이지에서 100개 단위 계층 탐색 및 일괄 액션을 제공한다.

## Non-goals
- 물리 폴더 이동/파일시스템 구조 변경
- 문서 권한 모델 자체 개편

## Scope
- 좌측 Pages(Document mode) 기본 접힘 구조: 하위는 토글 클릭 시만 노출
- 상위 페이지 20개 캡 + `... 더보기` → 라이브러리 페이지 이동
- 라이브러리 페이지 신설: 상위 페이지 100개/페이지, 하위 문서 트리 동봉 응답으로 즉시 토글
- 체크박스 다중 선택 후
  - `개인 페이지 상단으로 이동`
  - `휴지통으로 이동`
- 상단 고정 개인 설정 저장/반영

## API / Contracts
- `GET /api/v1/documents/library?page=0&size=100&q=`
- `POST /api/v1/documents/library/personal-top` (`document_ids`)
- `POST /api/v1/documents/library/bulk-trash` (`document_ids`)
- `GET /api/v1/documents/sidebar` (상위 20개 + 하위 트리 + more metadata)

## DB / Index changes
- `document_personal_top` 테이블 추가 (workspace_id, user_id, document_id, ord)

## Happy path
1. 좌측 패널은 루트만 노출, 토글 시 하위 즉시 펼침
2. 루트가 20개 초과이면 `... 더보기` 노출
3. 라이브러리 페이지 이동 후 100개 루트/페이지 조회
4. 루트 선택 후 상단 이동 또는 일괄 휴지통 이동
5. 좌측 패널 반영

## Edge cases
- 삭제된/존재하지 않는 문서 ID 포함 요청
- 루트가 아닌 문서를 상단 고정 대상으로 보내는 경우
- 빈 선택 일괄 요청

## Acceptance Criteria
- [ ] 좌측 패널 기본은 루트만
- [ ] 하위는 토글로만 렌더
- [ ] 루트 20개 초과 시 더보기 노출/동작
- [ ] 라이브러리 100개/페이지 및 즉시 하위 토글
- [ ] 일괄 상단 이동/휴지통 API+UI 동작
- [ ] tenant 음성 테스트 추가

## Testing
- web-user e2e: 사이드바 토글/더보기/라이브러리 일괄액션
- doc-api integration: library/siderbar endpoint + bulk actions + tenant isolation

## Observability
- 문서 본문 로그 금지
- workspace_id, document_id, action, count만 로깅

## Rollout / Rollback
- 점진 배포 가능 (기존 /documents 기반 화면 유지)
- 문제 시 UI route hide + 신규 endpoint 미사용

## Security / Privacy
- workspace context 강제
- root/ownership 검증 실패 시 fail-closed
