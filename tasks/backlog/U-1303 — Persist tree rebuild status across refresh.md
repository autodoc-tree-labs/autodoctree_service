# U-1303 — Persist tree rebuild status across refresh

## Context
- Tree page currently keeps rebuild progress only in frontend local state (`isRebuilding`).
- After browser refresh, users lose visibility of queued/running rebuild work and cannot tell if server-side rebuild is still processing.

## Goal
- Rebuild progress indication remains visible after page refresh by reading server-side status.

## Non-goals
- Redesigning the full tree page UX.
- Adding long-term rebuild history UI.

## Scope
- Add tenant-scoped tree rebuild status API.
- Expose debounce queue status (`QUEUED`) and in-flight rebuild execution status (`RUNNING`) for current workspace.
- Update Tree page to load/poll rebuild status and render persistent notice across refresh.
- Keep existing rebuild request flow and snapshot apply flow unchanged.

## API / Contracts
- Add `GET /api/v1/tree/rebuild/status`.
- Response shape:
  - `status`: `IDLE | QUEUED | RUNNING`
  - `pending_count`: number
  - `running_since`: optional ISO timestamp
  - `view_type`: current requested view api value
- Update `API_SURFACE.md`.

## DB / Index changes
- None.

## Happy path
1. User clicks rebuild (debounced).
2. Server enqueues rebuild and returns `QUEUED`.
3. Tree page polls status endpoint.
4. If queue is flushed, status changes to `RUNNING` then `IDLE`.
5. User refreshes page; status endpoint still reports current state and notice is shown.

## Edge cases
- Queue empty and no running task: return `IDLE`.
- Workspace mismatch/tenant violation: deny access (403/404 according context resolver path).
- Status polling failures should not block tree rendering; only surface transient UI error when needed.

## Acceptance Criteria
- [ ] Rebuild status is visible on Tree page even after browser refresh.
- [ ] Status transitions (`QUEUED`/`RUNNING`/`IDLE`) are reflected without manual re-trigger.
- [ ] New endpoint enforces workspace context.
- [ ] Existing tree rebuild flow still works.

## Testing
- Unit tests: queue state transitions and running markers.
- Integration tests: status endpoint response and tenant isolation negative case.
- E2E tests: optional; manual verification on refresh.

## Observability
- Logs include `workspace_id`, `status`, `pending_count` (no content).
- Reuse existing request tracing.

## Rollout / Rollback
- No feature flag required.
- Rollback by reverting API + frontend polling changes.

## Security / Privacy
- Endpoint requires authenticated workspace context.
- No document body/content in logs.
