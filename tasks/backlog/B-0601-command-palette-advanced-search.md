# B-0601 — Cmd+K Command Palette + Advanced Search + Search History foundation

## Context
- Existing search UX is fragmented between local title filtering and a basic `/search` page.
- Product requires a Notion-style unified command + search palette with server-side filters and history.

## Goal
- Deliver a tenant-safe, server-driven Command Palette that unifies commands, document search, advanced filters, and execution history.

## Non-goals
- Full enterprise-grade email SMTP delivery in dev (mock/log only).
- Recursive ACL inheritance beyond direct document ACL entries.

## Scope
- Extend doc-api search contract for filters/sort/mode/debug/scope subtree.
- Add palette history persistence + read API grouped client-side.
- Add DB fields for updated_by and tables for document ACL/workspace invites/history.
- Build web-user Cmd+K modal with command + documents sections and keyboard UX.
- Persist filter state to local storage.

## API / Contracts
- `GET /api/v1/search` extended query params.
- `GET /api/v1/search/history` and `POST /api/v1/search/history`.
- `POST /api/v1/workspaces/{workspaceId}/invites` and `POST /api/v1/workspaces/invites/accept`.

## DB / Index changes
- Flyway migration adding `documents.updated_by`, `document_acl`, `workspace_invites`, `palette_history`.

## Happy path
- User opens Cmd+K, types query, sees matching commands + server search results.
- User applies filters, executes command/open doc, history gets saved and appears grouped by date buckets.

## Edge cases
- Blank query returns commands + history only.
- Debug metadata only in dev/admin path.
- Tenant scope and ACL filters always applied.

## Acceptance Criteria
- [ ] Unified Cmd+K UX with keyboard controls.
- [ ] Advanced search filters applied server-side.
- [ ] Palette history persisted per user/workspace.
- [ ] ACL-aware result filtering.
- [ ] Invite create/accept flow available in dev.

## Testing
- Unit and integration tests for search filters, history tenant isolation, and invite accept.

## Observability
- Log workspace/user IDs and event types only; never content body.

## Rollout / Rollback
- Additive migration; rollback by disabling new UI paths and endpoints.

## Security / Privacy
- Strict workspace context enforcement and ACL checks.
- Truncate stored query text, no document body text in history/logs.
