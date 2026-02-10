# B-0304 — Indexer worker upsert/delete


## Goal
DB 문서를 OpenSearch에 동기화(결국 일관).

## Scope
- consume outbox: DocumentSaved/Updated/Deleted
- upsert into docs-active alias
- delete policy: remove or mark deleted (정책 문서화)

## Acceptance Criteria
- create/update 후 검색 결과 반영
- 멱등 동작

