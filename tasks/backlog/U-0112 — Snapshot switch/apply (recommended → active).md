# U-0112 — Snapshot switch/apply (recommended → active)


## Goal
추천 스냅샷을 보고 적용할 수 있다.

## Scope
- GET /tree/snapshots
- recommended 표시 + diff 요약(간단)
- apply 버튼 → POST /tree/snapshots/{id}/activate

## Acceptance Criteria
- apply 후 active 트리가 바뀜

