# B-0201 — Outbox events (at-least-once)


## Goal
API 트랜잭션과 함께 이벤트를 안전하게 발행.

## Scope
- outbox_event table + publisher/worker polling strategy
- event types:
  - DocumentSaved, DocumentUpdated, DocumentDeleted
  - AttachmentUploaded
  - FeedbackRecorded
- at-least-once semantics

## Acceptance Criteria
- domain write와 outbox insert가 same tx
- duplicate delivery 가능(하위 stage에서 멱등 처리)

