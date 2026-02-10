# I-0107 — Local upload reliability (MinIO endpoint alignment + diagnostics)

## Context
- File upload uses `presign -> PUT to storage -> complete`.
- In local environments with port conflicts, MinIO may run on non-default ports.
- If `storage.endpoint` and actual MinIO port are misaligned, upload fails at PUT with opaque client-side errors.

## Goal
- Make local MinIO endpoint configuration consistent by default and improve upload failure diagnostics so operators can self-serve fixes quickly.

## Non-goals
- No production cloud storage redesign.
- No API shape changes for attachment endpoints.

## Scope
- Align local defaults for MinIO host ports and backend storage endpoint fallback.
- Update setup docs/env examples to keep backend and infra in sync.
- Improve web-user upload error messages for storage endpoint/CORS/network failures.

## API / Contracts
- No endpoint path or request/response schema changes.

## DB / Index changes
- None.

## Happy path
1. Local infra starts with MinIO on configured port.
2. Backend presigns URL targeting that MinIO endpoint.
3. Browser PUT succeeds and `/attachments/complete` marks attachment complete.

## Edge cases
- MinIO not running: user sees actionable error to check storage endpoint/service.
- Wrong port/service bound: user sees actionable error instead of generic failure.
- CORS/network rejection on PUT: user sees storage/CORS guidance.

## Acceptance Criteria
- [ ] Local default MinIO endpoint and compose port defaults are consistent.
- [ ] `DEV_SETUP` and `README` port docs are consistent.
- [ ] Upload UI shows actionable Korean error for storage connectivity/CORS failures.
- [ ] User/app builds remain green.

## Testing
- Unit tests: N/A (config/doc + UI messaging)
- Integration tests: N/A
- E2E/manual: verify presign host matches local MinIO port and upload succeeds.

## Observability
- Keep current safe logging policy; do not log presigned URL contents.

## Rollout / Rollback
- Rollout: docs/config merge and backend restart with updated defaults.
- Rollback: revert config/doc changes; explicitly set `S3_ENDPOINT` in runtime env.

## Security / Privacy
- Tenant isolation unchanged.
- No additional sensitive payload logging.
