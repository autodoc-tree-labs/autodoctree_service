# B-1203 — StageRetry downstream cascade for INDEX/TREE

## Context
- 현재 `StageRetry`가 `EMBED` 단계로 들어오면 임베딩만 재실행되고 종료된다.
- 이 경우 `pipeline_status`에서 `index/tree`가 `PENDING`으로 남아 문서함 UI가 계속 “인덱스: 대기, 트리: 대기”로 고정될 수 있다.

## Goal
- 실패 단계 재실행 시 선택한 단계 이후 downstream(`INDEX`, `TREE`)까지 자동으로 이어서 처리되도록 한다.

## Non-goals
- 새로운 API 엔드포인트 추가
- 기존 권한/테넌트 모델 변경

## Scope
- `OutboxWorker`의 `StageRetry` 처리 경로를 “단일 단계”에서 “선택 단계 이후 연쇄 실행”으로 변경
- `EMBED` 재시도 성공 시 `INDEX`/`TREE`가 자동 수행되도록 보장
- 회귀 테스트 추가(통합 테스트)
- API 문서 동기화

## API / Contracts
- `POST /documents/{documentId}/pipeline/retry` 요청/응답 스키마는 유지
- 동작 변경: `stage=EMBED` 재시도 시 `EMBED -> INDEX -> TREE` 순으로 수행

## DB / Index changes
- 없음

## Happy path
1. 문서 `EMBED` 실패
2. `pipeline/retry`로 `stage=EMBED` 요청
3. 워커가 `EMBED` 재실행 후 `INDEX`, `TREE`를 연쇄 실행
4. `pipeline_status`가 최종 `DONE`으로 수렴

## Edge cases
- `EMBED`가 이미 `DONE`이어도 `INDEX/TREE`가 `PENDING`이면 downstream은 실행돼야 한다.
- `retry` 도중 실패하면 기존 재시도/백오프 정책을 따른다.

## Acceptance Criteria
- [x] `StageRetry(EMBED)` 처리 후 `index_status`, `tree_status`가 자동으로 진행된다.
- [x] 기존 `DocumentSaved` 전체 파이프라인 동작에는 회귀가 없다.
- [x] 통합 테스트로 연쇄 실행 동작이 검증된다.

## Testing
- Integration: `OutboxWorker.poll()` 기준 `StageRetry(EMBED)` 이후 `INDEX/TREE DONE` 검증
- Full: `./gradlew -p services :doc-api:test`

## Observability
- 기존 워커 로그/메트릭 체계를 유지하고 민감정보는 로그에 남기지 않는다.

## Rollout / Rollback
- 롤아웃: 코드 배포만으로 적용
- 롤백: `StageRetry`를 기존 단일 단계 실행 로직으로 복귀

## Security / Privacy
- 테넌트 스코프는 기존 `workspace_id + document_id` 경계 유지
- 본문/원문 로그 금지 원칙 유지

## Completion Notes
- `OutboxWorker`에 `cascadeFromStage` 모드를 추가해 `StageRetry`를 선택 단계부터 downstream까지 실행하도록 변경했다.
- `EMBED` 재시도 후 `INDEX/TREE`가 자동 진행되며, 재시도 성공 시 문서 상태를 `READY`로 복구하도록 반영했다.
- `EmbeddingTargetsIntegrationTest`에 `StageRetry(EMBED)` 연쇄 실행 통합 테스트를 추가했다.
- `API_SURFACE.md`에 `pipeline/retry` 동작(EMBED -> INDEX -> TREE) 설명을 업데이트했다.
- 검증: `./gradlew -p services :doc-api:test` 통과.
