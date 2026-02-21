# B-1402 — Enforce tenant scope on workspace invite creation endpoint

## Context
- `POST /api/v1/workspaces/{workspaceId}/invites` 호출 시 테넌트 컨텍스트 필터가 적용되지 않아 `WorkspaceContextResolver` 단계에서 `TENANT_FORBIDDEN`이 발생했다.
- 멤버 초대 기능이 정상 권한 사용자에게도 실패하며, 테넌트 스코프 강제 규칙과도 어긋난다.

## Goal
- 워크스페이스 초대 생성 엔드포인트가 tenant filter를 통해 정상 컨텍스트를 주입받고, 허용/거부가 일관되게 동작한다.

## Non-goals
- 초대 토큰 정책/만료 정책 변경
- 멤버십 역할 정책 변경

## Scope
- `WorkspaceContextFilter.shouldNotFilter`에 `/api/v1/workspaces/{workspaceId}/invites` 경로 포함
- 통합 테스트 추가
  - 같은 테넌트(owner) 요청 성공
  - 교차 테넌트 요청 거부

## API / Contracts
- API 스펙 변경 없음

## DB / Index changes
- 없음

## Happy path
1. OWNER가 `X-Workspace-Id`와 path `workspaceId`를 일치시켜 초대 생성 요청
2. 필터가 WorkspaceContext를 주입
3. 초대 토큰이 생성되어 응답 반환

## Edge cases
- 다른 워크스페이스 사용자가 같은 path에 요청 시 `403 TENANT_FORBIDDEN`
- `X-Workspace-Id` 누락 시 `400 BAD_REQUEST`

## Acceptance Criteria
- [ ] 초대 생성 API가 정상 응답(200 + invite_token)을 반환한다.
- [ ] 교차 테넌트 초대 생성 요청은 거부된다.

## Testing
- Integration: `TenantIsolationIntegrationTest`에 invite 생성 성공/거부 케이스 추가

## Observability
- 기존 trace_id/request_id/workspace_id 로깅 체계 유지

## Rollout / Rollback
- 롤아웃: 서버 재배포 후 즉시 반영
- 롤백: 필터 경로 추가 변경 revert

## Security / Privacy
- tenant scope 강제 범위 확장
- 본문/첨부 민감 데이터 로깅 없음
