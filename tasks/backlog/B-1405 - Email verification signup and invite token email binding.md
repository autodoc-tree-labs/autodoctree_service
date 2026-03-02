# B-1405 - Email verification signup and invite token email binding

## Context
- 런처에서 계정 추가 시 회원가입/로그인이 혼합되어 있고, 회원가입이 이메일 인증 없이 즉시 생성된다.
- 워크스페이스 초대 토큰은 현재 토큰만 알면 다른 이메일 계정으로도 수락 가능해 보안 리스크가 있다.

## Goal
- 이메일 인증코드 검증을 통과한 가입만 허용하고, 초대 토큰은 초대된 이메일 계정에만 바인딩한다.

## Non-goals
- OAuth/SSO
- 비밀번호 재설정
- 초대 링크를 URL 기반 초대 플로우로 전면 교체

## Scope
- 회원가입 2단계 API 추가
  - 인증코드 발송
  - 인증코드 검증 후 계정 생성 + 토큰 발급
- 인증코드 저장용 DB 테이블/리포지토리 추가
- 이메일 발송 어댑터 추가(SMTP 기반)
- 기존 `/auth/register`는 인증코드 기반 등록으로 전환
- `/workspaces/invites/accept`에서 로그인 계정 이메일과 초대 이메일 일치 검증

## API / Contracts
- `POST /api/v1/auth/register/request-code`
  - request: `{ "email": string, "password": string }`
  - response: `{ "expires_in_seconds": number }`
- `POST /api/v1/auth/register/verify`
  - request: `{ "email": string, "verification_code": string }`
  - response: `{ "access_token": string, "refresh_token": string }`
- `POST /api/v1/auth/register`
  - request: `{ "email": string, "verification_code": string }`
  - response: `{ "access_token": string, "refresh_token": string }`
- `POST /api/v1/workspaces/invites/accept`
  - 기존 요청 유지, 단 초대 이메일과 현재 로그인 이메일이 다르면 `403`
- `API_SURFACE.md` 업데이트

## DB / Index changes
- Flyway migration 추가
  - `registration_verification_codes` 테이블 생성
  - email/code hash/만료시각/시도횟수/사용시각 저장

## Happy path
1. 사용자 이메일/비밀번호로 인증코드 발송 요청
2. 서버가 코드 생성 후 메일 발송 + 코드 해시 저장
3. 사용자가 코드 입력 후 가입 검증 API 호출
4. 서버가 코드 검증 후 사용자/기본 워크스페이스 생성 + 토큰 발급
5. 워크스페이스 초대 수락 시 초대 이메일과 로그인 이메일이 같을 때만 멤버십 생성

## Edge cases
- 중복 이메일 가입 요청 시 `409`
- 코드 만료/오입력/시도 초과 시 `400`
- SMTP 실패 시 가입 진행 중단 (`503`)
- 초대 토큰 이메일 불일치 시 `403`

## Acceptance Criteria
- [ ] 인증코드 검증 전에는 가입이 완료되지 않는다.
- [ ] 코드 검증 성공 시에만 토큰이 발급된다.
- [ ] 초대 토큰은 초대된 이메일 계정에서만 수락된다.
- [ ] 기존 로그인/리프레시/로그아웃 플로우는 유지된다.

## Testing
- 통합 테스트
  - 인증코드 발송/검증 성공
  - 만료/오코드 실패
  - 중복 이메일 실패
  - 초대 이메일 불일치 수락 거부
- 테넌트 음수 테스트
  - 초대 토큰 이메일 불일치 거부(워크스페이스 무단 참여 방지)

## Observability
- 코드/비밀번호/토큰 평문 로그 금지
- 실패 로그는 trace_id + 사유 코드만 기록

## Rollout / Rollback
- 롤아웃: 신규 가입 UI를 새 API로 전환 후 기존 register direct 사용 중단
- 롤백: 신규 테이블 유지한 채 컨트롤러를 기존 register direct 구현으로 되돌림

## Security / Privacy
- 인증코드/초대토큰은 해시 저장
- 초대 수락 시 이메일 바인딩 강제
- 민감정보(비밀번호/코드/토큰) 마스킹 또는 미기록
