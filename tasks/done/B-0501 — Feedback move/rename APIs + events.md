# B-0501 — Feedback move/rename APIs + events


## Goal
사용자 수정(move/rename) 이벤트를 수집한다.

## Scope
- POST /feedback/move
- POST /feedback/rename
- feedback_event 저장 + outbox FeedbackRecorded 발행

## Acceptance Criteria
- cross-tenant feedback denied
- 이벤트가 개인화/라벨러에 반영될 수 있는 형태로 저장

