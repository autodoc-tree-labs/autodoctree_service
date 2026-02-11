# B-0103 — Auth v1 (access/refresh rotation)


## Goal
안전한 로그인/리프레시/로그아웃(회전/철회) 구현.

## Scope
- endpoints: /auth/login, /auth/refresh, /auth/logout
- password hashing (bcrypt/argon2)
- refresh token rotation + revoke list

## Acceptance Criteria
- login→refresh→logout 시나리오 테스트 통과
- auth 실패 시 정보 노출 최소화

