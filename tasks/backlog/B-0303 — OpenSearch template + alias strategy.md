# B-0303 — OpenSearch template + alias strategy


## Goal
무중단 인덱스 운영을 위한 템플릿/알리아스 전략.

## Scope
- index template:
  - workspace_id keyword
  - title/body text fields
  - created_at date
  - optional vector field
- alias:
  - docs-active (read/write)
  - versioned indices docs-v1-000001…

## Acceptance Criteria
- alias로 검색/인덱싱 가능
- runbook 초안 포함(재색인)

