# U-0128 — User document failed-stage retry action

## Context
- 문서 상세에서 파이프라인 실패를 확인할 수 있지만 사용자 콘솔에서 실패 단계 재실행을 직접 트리거할 수 없다.

## Goal
- 문서 상세에서 실패한 파이프라인 단계만 안전하게 재실행할 수 있게 한다.

## Non-goals
- 관리자 작업 콘솔(`/admin/jobs`) 제거/대체
- 자동 재시도 정책(백오프/서킷브레이커) 변경

## Scope
- 사용자 API: 문서 단위 실패 단계 재실행 엔드포인트 추가
- web-user 문서 상세: 실패 단계 재실행 버튼/에러/안내 메시지
- 테넌트 음수 테스트 추가(교차 워크스페이스 호출 차단)
- API_SURFACE 문서 갱신

## API / Contracts
- `POST /documents/{documentId}/pipeline/retry`
- Request:
```json
{ "stage": "EMBED" }
```
- 동작:
- 요청 workspace + document 소유권 검증
- `OWNER|MEMBER`만 허용
- 실패 상태(`FAILED`) 단계만 재실행 허용
- outbox `StageRetry` 이벤트 enqueue

## DB / Index changes
- 없음

## Happy path
1. 사용자 문서 상세에서 실패 상태 확인
2. 실패 단계 재실행 클릭
3. API가 `StageRetry` enqueue
4. 워커가 해당 단계부터 재실행

## Edge cases
- 실패가 아닌 단계 재실행 요청: `400`
- 문서 미존재/타 워크스페이스 문서: `404` 또는 `403`
- 권한 부족(VIEWER): `403`

## Acceptance Criteria
- [x] web-user 문서 상세에 실패 단계 재실행 버튼이 노출된다.
- [x] 버튼 클릭 시 실패 단계만 재실행 요청된다.
- [x] 교차 테넌트 재실행 요청이 차단된다.

## Testing
- Integration: tenant isolation negative case
- Manual: 실패 문서에서 재실행 버튼 동작 및 상태 전이 확인

## Observability
- 감사 로그 action `document.pipeline.retry` 기록(본문/청크 로그 금지)

## Rollout / Rollback
- 롤아웃: backend + web-user 배포
- 롤백: endpoint/UI 변경 revert

## Security / Privacy
- `X-Workspace-Id` + membership 검증 필수
- 로그에 문서 본문/추출 텍스트 포함 금지
