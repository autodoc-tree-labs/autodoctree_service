# B-0102 — Flyway baseline schema (tenant tables)


## Goal
Workspace=Tenant를 전제로 한 최소 스키마를 마이그레이션으로 만든다.

## Scope
- tables:
  - users, workspaces, memberships
  - documents, attachments, document_sections
  - pipeline_status, outbox_event, dlq_event, stage_execution
  - tree_snapshot, tree_node, tree_membership
  - feedback_event, audit_log
- indexes include workspace_id where appropriate

## Acceptance Criteria
- fresh DB에서 migrate 성공
- tenant-owned tables all have workspace_id

