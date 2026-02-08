# B-0502 — Immediate membership patch on move (or defined async behavior)


## Goal
드래그 이동이 사용자에게 즉시 반영되도록 한다.

## Scope
- option A: active snapshot membership 즉시 변경(권장)
- option B: 빠른 비동기 적용(수 초 내), UX에서 pending 표시
- 정책/정합성(동시성) 문서화

## Acceptance Criteria
- move 후 tree view에서 문서가 바뀐 위치로 보임

