# I-1407 — Bulk synthetic dataset seeder for 1000+ docs and file metadata

## Context
- Local verification of tree classification, document hierarchy, and attachment-driven signals needs more volume than the small `Test` seed.
- Manual input is slow and inconsistent, making threshold/policy tuning hard to reproduce.

## Goal
- Provide a one-command script that seeds a workspace with 1000+ diverse documents plus attachment metadata in an idempotent way.

## Non-goals
- Real binary object upload to MinIO/S3
- Changes to document API contracts
- Production data generation pipeline

## Scope
- Add bulk seed SQL using set-based generation (`generate_series`) for speed.
- Add shell wrapper script to run the SQL with configurable env vars.
- Seed:
  - workspace bootstrap + owner membership
  - multi-category root/child hierarchy
  - diverse title/body markdown content
  - attachment metadata rows per configurable ratio
  - pipeline status + `DocumentSaved` outbox events
- Update `README.md` and `docs/DEV_SETUP.md` usage docs.

## API / Contracts
- No API schema changes.

## DB / Index changes
- No Flyway migration.
- Data-only seed SQL targeting existing tables:
  - `workspaces`, `memberships`, `documents`, `attachments`, `pipeline_status`, `outbox_event`

## Happy path
1. Run `./scripts/seed_bulk_workspace_dataset.sh`.
2. Script validates DB connectivity.
3. SQL seeds workspace/documents/attachments and enqueues pipeline events.
4. Worker processes pending `DocumentSaved` events for ingest/embed/index/tree.

## Edge cases
- Missing owner seed user: fail fast with explicit message.
- Re-run same command: no duplicate primary keys; rows are upserted.
- Attachment ratio out of range: clamped to `0..100`.

## Acceptance Criteria
- [ ] One shell command seeds 1000+ documents by default.
- [ ] Seeded dataset includes multiple categories and parent-child hierarchy.
- [ ] Attachment metadata is generated for a configurable subset.
- [ ] Re-running script does not create duplicate PK rows.
- [ ] README/DEV_SETUP include execution examples and env knobs.

## Testing
- Manual integration run against local Postgres.
- Verify seeded counts with SQL summary output.

## Observability
- Reuse existing outbox/worker metrics.
- No document body or attachment bytes logged by script.

## Rollout / Rollback
- Rollout: add script and docs only.
- Rollback: remove script + SQL and docs references.

## Security / Privacy
- Workspace-scoped inserts only (`workspace_id` fixed per run).
- No cross-tenant reads/writes.
- No sensitive content logging.
