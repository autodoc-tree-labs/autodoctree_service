# B-0305 — Search API BM25 (TenantSearchClient enforced)


## Goal
키워드 검색(BM25) 제공 + tenant scoping 강제.

## Scope
- GET /search?q=&mode=bm25&page=&size=
- 반드시 `TenantSearchClient` 사용

## Acceptance Criteria
- cross-tenant 검색 결과 0
- empty q → 400

