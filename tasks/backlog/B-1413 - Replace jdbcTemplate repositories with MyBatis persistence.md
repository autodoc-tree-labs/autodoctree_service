# B-1413 — Replace jdbcTemplate repositories with MyBatis persistence

## Context
- `services/doc-api`의 DB 접근이 [`Repositories.kt`](/Users/seungyoonkim/Documents/autodoc-tree-monorepo-docs/services/doc-api/src/main/kotlin/com/autodoctree/api/db/Repositories.kt)에 집중되어 있고, 현재 구현은 전부 `JdbcTemplate`에 의존한다.
- SQL이 많고 tenant-scoped 조건, upsert, 동적 필터, 재시도 상태 갱신이 섞여 있어 JPA보다 MyBatis가 기존 동작을 안전하게 유지하기 쉽다.

## Goal
- `doc-api`의 현재 `JdbcTemplate` 사용 코드를 MyBatis 기반 persistence 레이어로 전환한다.

## Non-goals
- API 스펙 변경
- DB 스키마 변경
- 트리/검색/인증 도메인 로직 변경

## Scope
- `doc-api`에 MyBatis 의존성과 mapper 설정 추가
- 기존 repository public API를 유지하면서 내부 구현을 MyBatis로 전환
- `JdbcTemplate`를 직접 쓰는 테스트 코드도 MyBatis 또는 repository 경유로 변경
- 전환 후 `doc-api` 테스트로 회귀 확인

## API / Contracts
- 외부 API 계약 변경 없음

## DB / Index changes
- 스키마 변경 없음

## Happy path
- 서비스 계층은 기존 repository 메서드를 그대로 호출한다.
- repository는 MyBatis statement를 호출해 tenant-scoped SQL을 수행한다.
- 기존 CRUD/검색/트리/파이프라인 흐름이 동일하게 동작한다.

## Edge cases
- 낙관적 락 충돌은 기존처럼 `ConflictException`으로 유지한다.
- tenant-scoped 조회/수정 조건이 누락되지 않아야 한다.
- explain/search/tree 관련 동적 SQL은 빈 필터를 안전하게 생략해야 한다.

## Acceptance Criteria
- [x] `doc-api` main/test 코드에 `JdbcTemplate` 직접 사용이 남지 않는다.
- [x] repository 공개 메서드 시그니처를 유지한다.
- [x] tenant-scoped SQL 동작이 기존 테스트에서 유지된다.
- [x] build/test가 통과한다.

## Testing
- Unit tests: existing guardrail/unit tests 회귀
- Integration tests: `doc-api` Spring Boot integration suite 회귀
- E2E tests: 해당 없음

## Observability
- 로그/메트릭 계약 변경 없음

## Rollout / Rollback
- Rollout: dependency + repository implementation 교체
- Rollback: MyBatis 관련 파일과 dependency를 제거하고 기존 `JdbcTemplate` 구현으로 복귀

## Security / Privacy
- tenant 조건을 mapper SQL에 유지
- 로그에 문서 본문/추출 텍스트를 추가하지 않음
