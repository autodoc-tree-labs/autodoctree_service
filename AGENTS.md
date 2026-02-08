# AGENTS.md — AutoDoc Tree (Monorepo Rules)

## Mission
Users “save/upload” only; the system automatically organizes documents into a stable, explainable virtual folder tree, improving via feedback, with strict Workspace=Tenant isolation.

---

## Non‑negotiables (GA Gate)
1) **Tenant-safe by default**
- Every request is scoped to a `workspace_id` derived from the authenticated context (header + membership check).
- Cross-tenant reads/writes must be impossible in API/DB/OpenSearch/Redis/S3.
- Any new endpoint must add at least one negative tenant test.

2) **Non‑destructive organization**
- Default is **virtual tree snapshots**.
- No physical file moves for classification.

3) **Explainability**
- Any auto-placement shown to users must have rationale (keywords/similar docs/signals).
- Explain failures must **degrade gracefully** (never break browsing).

4) **Stable-by-default**
- Tree rebuilds are debounced/coalesced.
- Snapshots: ACTIVE + (optional) RECOMMENDED.
- Apply policy to avoid frequent flip-flops. Provide “lock” nodes.

5) **No sensitive logs**
- Never log document body, extracted text, chunks, or attachment contents.
- Logs may include ids, sizes, content_type, stage, durations, trace_id, workspace_id.

---

## Monorepo layout contracts
- `services/` uses Gradle multi-module (Kotlin/Spring).
- `web-user/`, `web-admin/` use pnpm workspaces.
- Shared TS packages go into `packages/` (optional) — prefer API client types from a single source.

---

## Ticket-driven development
- All work starts from `tasks/backlog/*.md`.
- If a needed feature has no ticket yet: create one using `tasks/_template/TICKET.md` before coding.
- Make small commits/PRs: one ticket (or tight bundle of 2–3). Commit messages reference ticket IDs.

### Definition of Done (DoD)
- Scope implemented
- Tests added (unit + integration/e2e where specified)
- Observability: logs/metrics/traces
- Rollout/Rollback notes added
- Docs updated if API or ops behavior changes (`API_SURFACE.md`, `RUNBOOK.md`, etc.)

---

## Backend rules (services/)
- WorkspaceContext is mandatory for tenant resources.
- DB: forbid unscoped queries (`findById(id)` without workspace is banned).
- OpenSearch: all queries must go through `TenantSearchClient` wrapper that injects workspace filter.
- Presigned URLs: require ownership check on both presign and complete.
- Fail closed for tenant issues (deny access).

---

## Frontend rules (web-user/web-admin)
- Always include `X-Workspace-Id` header for tenant-scoped requests once workspace selected.
- Treat 401/403/404 consistently; do not leak workspace existence.
- Never store tokens in localStorage if avoidable (prefer httpOnly cookie in prod; for local, short-lived memory store ok).
- UI must show current workspace clearly (prevent operator mistakes).

---

## Observability rules
- Every request/job must include trace_id/request_id (server generates if missing).
- Tag metrics with `workspace_id` only when safe (avoid cardinality explosion; use sampling or omit in high-volume metrics).
- Minimum baseline:
  - API: latency, 5xx rate, auth failures
  - Workers: stage success/failure, lag, DLQ
  - Security: tenant scope missing, OS query missing tenant filter (assert)
