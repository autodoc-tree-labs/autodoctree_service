# <TICKET-ID> — <Title>

## Context
- Why this exists, user pain, and where it fits in the architecture.

## Goal
- What “done” means in one sentence.

## Non-goals
- Explicit exclusions.

## Scope
- Bullet list of what to implement.

## API / Contracts
- Endpoints, request/response shapes, event schemas.
- Update `API_SURFACE.md` if changed.

## DB / Index changes
- Flyway migrations / OpenSearch mappings/templates/aliases.

## Happy path
- Step-by-step expected flow.

## Edge cases
- Failure modes and expected behavior.

## Acceptance Criteria
- Checklist.

## Testing
- Unit tests
- Integration tests (DB/OS/MinIO)
- E2E tests (tenant isolation if relevant)

## Observability
- Logs: trace_id, request_id, workspace_id (no content)
- Metrics: counters/timers/gauges
- Alerts: if needed

## Rollout / Rollback
- Feature flags, safe steps, rollback plan.

## Security / Privacy
- Tenant isolation points
- PII/log redaction
