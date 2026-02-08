# B-0504 — Snapshot stability policy (recommended/apply change limit)


## Goal
새 문서 유입으로 트리가 매번 뒤집히지 않게 안정화.

## Scope
- rebuild 결과가 기존 ACTIVE와 diff가 크면:
  - RECOMMENDED로 저장
  - 사용자가 apply해야 ACTIVE가 바뀜
- change limit 정의:
  - moved ratio, node rename count, membership churn 등

## Acceptance Criteria
- ACTIVE가 자동으로 급변하지 않음
- apply 시 audit log 남김

