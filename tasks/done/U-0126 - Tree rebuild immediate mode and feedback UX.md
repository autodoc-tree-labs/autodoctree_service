# U-0126 - Tree rebuild immediate mode and feedback UX

## Context
- Tree page `재빌드` currently calls debounced rebuild mode.
- Users expect immediate visual changes after clicking rebuild and perceive no reaction.

## Goal
- Make rebuild interaction explicit and responsive by using immediate rebuild mode and showing clear status messages.

## Non-goals
- No backend contract changes.
- No tree algorithm changes.

## Scope
- Switch user tree-page rebuild request mode from `DEBOUNCED` to `IMMEDIATE`.
- Add in-page status notice for rebuild/apply actions (started/success/no recommended snapshot).
- Keep existing error handling behavior for failures.

## API / Contracts
- Reuse existing `POST /tree/rebuild` and `POST /tree/snapshots/{id}/activate`.

## DB / Index changes
- None.

## Happy path
1. User clicks `재빌드`.
2. Immediate rebuild API runs and returns snapshot.
3. UI refreshes tree and shows success notice.

## Edge cases
- No recommended snapshot exists: show informative notice.
- API failure: show existing error panel.

## Acceptance Criteria
- [ ] `재빌드` uses immediate mode.
- [ ] Status notice appears for rebuild/apply actions.
- [ ] No regression in tree page interactions.

## Testing
- Unit tests: N/A
- Integration tests: N/A
- Manual: verify notice + immediate tree refresh in web-user tree page.

## Observability
- Existing client/server logging unchanged.

## Rollout / Rollback
- Rollout with frontend deploy.
- Rollback by reverting this UI change.

## Security / Privacy
- Tenant-scoped request flow unchanged.
