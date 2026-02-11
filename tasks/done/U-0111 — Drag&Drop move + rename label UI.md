# U-0111 — Drag&Drop move + rename label UI


## Goal
사용자 피드백(move/rename) UI.

## Scope
- drag doc card → target node
- POST /feedback/move
- rename node label input → POST /feedback/rename
- optimistic UI + 실패 롤백

## Acceptance Criteria
- move 후 위치가 반영됨(즉시 or pending)

