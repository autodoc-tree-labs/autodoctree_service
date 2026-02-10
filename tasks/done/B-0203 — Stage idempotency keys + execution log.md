# B-0203 — Stage idempotency keys + execution log


## Goal
중복 이벤트로 중복 결과가 생기지 않게 stage-level 멱등 보장.

## Scope
- stage_execution table keyed by (ws, doc, stage, input_hash, model_version)
- workers check before processing
- input_hash stable computation

## Acceptance Criteria
- 동일 이벤트 2번 처리해도 결과 1번만 생성

