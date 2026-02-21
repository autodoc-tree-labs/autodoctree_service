# B-1205 — Pipeline stage retry status consistency and stale stage healing

## Context
- 문서 상세에서 실패 단계 재실행(예: EMBED) 요청 후에도 `pipeline_status`가 `EMBED=FAILED`, `INDEX/TREE=DONE`으로 남는 사례가 있다.
- stage execution dedupe(동일 input hash)로 실제 실행이 생략될 때 pipeline_status가 동기화되지 않아 상태 불일치가 발생할 수 있다.

## Goal
- 실패 단계 재실행 요청 시 pipeline_status가 항상 일관되게 전이되고, dedupe 경로에서도 stale FAILED 상태를 자동 회복한다.

## Non-goals
- 임베딩 모델/인덱싱 알고리즘 변경
- UI 디자인 변경

## Scope
- retry 요청 시 선택 stage 및 downstream stage를 `PENDING`으로 리셋
- worker stage dedupe 시 기존 stage_execution 상태를 pipeline_status에 동기화
- stage가 `DONE`이 아닐 때 downstream stage 실행을 중단해 상태 역전 방지
- stale failed 상태 회복 통합 테스트 추가

## API / Contracts
- `POST /documents/{documentId}/pipeline/retry` 동작 보강:
  - 요청 직후 stage/downstream `PENDING`, 문서 `PROCESSING`
  - 실행 생략(dedupe) 시에도 pipeline_status 동기화

## DB / Index changes
- 스키마 변경 없음

## Happy path
- `EMBED` 실패 문서에서 재실행 요청 시 `EMBED -> INDEX -> TREE`가 순차적으로 재진행된다.

## Edge cases
- 동일 input hash의 stage execution이 이미 DONE인 경우 재실행 생략 시에도 pipeline_status가 DONE으로 회복된다.
- stage가 RUNNING/FAILED 상태로 완료되지 않으면 downstream stage는 실행되지 않는다.

## Acceptance Criteria
- [x] retry 요청 직후 stage/downstream status가 PENDING으로 바뀐다.
- [x] dedupe 경로에서 stale FAILED가 남지 않는다.
- [x] `EMBED=FAILED + INDEX/TREE=DONE` 상태가 재현되지 않는다.

## Testing
- `./gradlew -p services :doc-api:test --tests "com.autodoctree.api.integration.EmbeddingTargetsIntegrationTest"`
- `./gradlew -p services :doc-api:test`

## Observability
- `stage_execution_reused` 로그로 dedupe 재사용 상태 확인(문서 본문 로그 없음)

## Rollout / Rollback
- 롤아웃: doc-api 배포 후 즉시 반영
- 롤백: 커밋 revert

## Security / Privacy
- 테넌트 스코프 로직 변경 없음
- 민감 텍스트 로그 추가 없음
