# U-0102 — Auth UI (login/refresh handling) + session store


## Goal
로그인 및 토큰 관리(세션 스토어) 구현.

## Scope
- login form
- access/refresh 저장(로컬은 memory store, prod는 cookie 전략 문서화)
- 401 발생 시 refresh 시도 후 재요청(최소)

## Acceptance Criteria
- 로그인 성공 후 보호 페이지 접근 가능
- 실패 시 에러 UX

