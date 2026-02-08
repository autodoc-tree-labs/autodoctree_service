# B-0101 — Services scaffold + /health


## Goal
services/ 하위에 Spring Boot 모노레포 스캐폴드와 /health를 만든다.

## Scope
- modules: doc-api + worker-* + libs/common/contracts
- doc-api:
  - GET /api/v1/health → {status:"OK"}
  - request_id/trace_id 생성(미들웨어)
- basic error model 공통화

## Acceptance Criteria
- `./gradlew -p services test` 통과
- doc-api 부팅 가능(local profile)

