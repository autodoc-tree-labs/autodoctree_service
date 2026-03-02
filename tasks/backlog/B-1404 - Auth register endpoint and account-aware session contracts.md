# B-1404 - Auth register endpoint and account-aware session contracts

## Context
- 현재 인증 플로우는 로그인/토큰갱신/로그아웃만 제공하며, 신규 사용자가 앱 내에서 계정을 생성할 수 없다.
- 다중 계정 전환 UX를 제공하려면 계정 추가 시 회원가입 API가 필요하다.

## Goal
- `POST /api/v1/auth/register`를 추가해 이메일/비밀번호로 신규 계정 생성 후 즉시 로그인 토큰을 발급한다.

## Non-goals
- 이메일 인증/비밀번호 재설정
- SSO/OAuth 연동

## Scope
- `AuthService.register(email, password)` 추가
- 중복 이메일은 `409 CONFLICT` 처리
- 기본 워크스페이스 1개 자동 생성 + OWNER 멤버십 부여
- AuthController에 `/auth/register` 노출
- 보안 필터/permitAll 경로에 register 추가

## API / Contracts
- `POST /api/v1/auth/register`
  - request: `{ "email": string, "password": string }`
  - response: `{ "access_token": string, "refresh_token": string }`
- `API_SURFACE.md` 반영

## DB / Index changes
- 없음 (기존 users/workspaces/memberships 재사용)

## Happy path
1. 클라이언트가 회원가입 요청
2. 서버가 사용자 생성 + 기본 워크스페이스 생성
3. 액세스/리프레시 토큰 반환
4. 클라이언트가 즉시 로그인 상태 진입

## Edge cases
- 동일 이메일 재가입 시 409
- 잘못된 입력(빈 값) 시 400

## Acceptance Criteria
- [ ] register API 호출로 신규 계정이 생성된다.
- [ ] 응답 토큰으로 즉시 `/workspaces` 조회가 가능하다.
- [ ] 중복 이메일은 409로 실패한다.

## Testing
- 통합 테스트: register 성공/중복 실패

## Observability
- 인증 로그에 본문/비밀번호 미기록
- trace_id/request_id는 기존 체계 준수

## Rollout / Rollback
- 롤아웃: API 추가만 포함
- 롤백: 엔드포인트 비활성화(코드 revert)

## Security / Privacy
- 비밀번호는 해시 저장
- 응답/로그에 평문 비밀번호 노출 금지
