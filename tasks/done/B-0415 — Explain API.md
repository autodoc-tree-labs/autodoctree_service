# B-0415 — Explain API


## Goal
왜 이 폴더인지 설명하는 API 제공.

## Scope
- GET /documents/{id}/explain
- source: membership.rationale_json preferred
- fallback: on-demand search-based compute (optional)

## Acceptance Criteria
- 항상 스키마 반환(부분 빈 값 허용)
- cross-tenant explain denied

