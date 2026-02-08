# B-0413 — Snapshot persistence + tree APIs (active/snapshots/activate/lock)


## Goal
트리 스냅샷 저장과 API 제공.

## Scope
- tree_snapshot/tree_node/tree_membership 저장
- APIs:
  - GET /tree/active
  - GET /tree/snapshots
  - POST /tree/rebuild
  - POST /tree/snapshots/{id}/activate
  - POST /tree/nodes/{id}/lock
- activate 시 audit log

## Acceptance Criteria
- active snapshot 조회 가능
- lock이 rebuild 시 유지되도록 모델 확장(최소 flag 저장)

