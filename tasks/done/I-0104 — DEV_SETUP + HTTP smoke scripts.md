# I-0104 — DEV_SETUP + HTTP smoke scripts


## Goal
IntelliJ로 바로 실행 가능한 개발 가이드 제공.

## Scope
- `docs/DEV_SETUP.md` 작성:
  - docker compose
  - IntelliJ Run Configs (doc-api + workers)
  - web-user/web-admin 실행
- `tools/http/*.http` (IntelliJ HTTP Client)로 smoke:
  - login → workspace → create doc → presign/complete(스텁 가능)

## Acceptance Criteria
- 새 개발자가 30분 내 로컬 구동 가능

## Security
- 토큰/비밀키는 문서에 하드코딩 금지

