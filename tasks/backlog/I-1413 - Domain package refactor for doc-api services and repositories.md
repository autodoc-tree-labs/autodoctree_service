# I-1413 — Domain package refactor for doc-api services and repositories

## Context
- `services/doc-api` currently keeps major service classes in a few large files under `com.autodoctree.api.domain` and most repository classes in a single `Repositories.kt` under `com.autodoctree.api.db`.
- Controller refactoring already split HTTP entrypoints by domain, but the service and persistence layers still have weak package boundaries, which makes navigation and ownership harder.
- The current structure slows down onboarding and raises the cost of making isolated changes in tenant-sensitive code paths.

## Goal
- Repackage doc-api services and repositories into domain-oriented packages without changing endpoint URLs, runtime behavior, or tenant-safety guarantees.

## Non-goals
- Rewriting business logic or changing request/response contracts.
- Changing Flyway migrations, table schemas, or OpenSearch mappings.
- Splitting every class into one-file-per-class where that adds churn without clearer boundaries.

## Scope
- Move service classes into domain packages such as `auth`, `workspace`, `document`, `attachment`, `search`, `question`, `tree`, `feedback`, and `admin`.
- Split repository classes into domain packages while keeping shared row models and JDBC helpers reusable.
- Update imports and Spring component scanning references impacted by the package moves.
- Update docs that reference old service or repository file paths if needed.

## API / Contracts
- No API behavior changes.
- Existing endpoint paths and request/response shapes remain unchanged.
- No `API_SURFACE.md` update required unless path references in docs become stale.

## DB / Index changes
- None.

## Happy path
1. Add the refactor ticket and define package targets.
2. Move services into domain-specific packages and fix imports.
3. Split repositories into domain-specific packages with shared support types.
4. Compile `doc-api` and update any stale docs or references.

## Edge cases
- Preserve Spring bean discovery after package moves.
- Keep cross-domain shared helpers importable without introducing cycles.
- Do not weaken workspace scoping in repository APIs during file/package extraction.

## Acceptance Criteria
- [ ] Services are grouped into domain-specific packages under `com.autodoctree.api.domain`.
- [ ] Repositories are grouped into domain-specific packages under `com.autodoctree.api.db`.
- [ ] Shared DB row/helper types remain available without circular package dependencies.
- [ ] `./gradlew -p services :doc-api:compileKotlin --no-daemon` passes after the refactor.

## Testing
- Compile validation with `./gradlew -p services :doc-api:compileKotlin --no-daemon`
- Spot-check existing integration tests for tenant-scoped repository/service imports if compile errors surface.

## Observability
- No behavior change expected.
- Existing logs and metrics must remain content-safe and tenant-safe.

## Rollout / Rollback
- Rollout: land as one refactor-only change set after compile verification.
- Rollback: restore previous file/package layout if Spring wiring or imports regress.

## Security / Privacy
- Keep `WorkspaceContext` and workspace-scoped repository methods intact.
- Do not introduce unscoped repository access during extraction.
- Do not add document content to logs while touching services.
