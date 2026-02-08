# B-0402 — Embedding worker + storage


## Goal
문서/청크 임베딩 생성 및 저장.

## Scope
- embedding table (target_type, target_id, vector, model_version)
- consume events when body_text/sections ready
- idempotency via stage_execution

## Acceptance Criteria
- embedding 생성 성공
- 중복 이벤트는 스킵(idempotency hit)

