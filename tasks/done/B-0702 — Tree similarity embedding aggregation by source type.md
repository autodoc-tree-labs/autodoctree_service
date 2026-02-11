# B-0702 — Tree similarity embedding aggregation by source type

## Context
- Current tree neighbor similarity consumes only `DOCUMENT` embeddings although the pipeline stores `DOCUMENT`, `SUMMARY`, and `SECTION` vectors.
- Teams reported unstable clustering when long content dominates a single combined document embedding input.

## Goal
- Build tree similarity vectors by aggregating document/summary/section embeddings with configurable weights to improve stability without breaking tenant safety.

## Non-goals
- No API contract changes.
- No DB schema migrations.
- No physical file moves.

## Scope
- Add tree properties for embedding aggregation weights.
- Aggregate `DOCUMENT` + `SUMMARY` + averaged `SECTION` vectors into a synthetic per-document vector for tree build/explain neighbor debug.
- Keep lexical+semantic fusion algorithm unchanged.
- Add unit tests for aggregation math and fallback behavior.

## API / Contracts
- No endpoint changes.
- No response schema changes.

## DB / Index changes
- None.

## Happy path
1. Pipeline stores embeddings for multiple target types.
2. Tree rebuild loads workspace embeddings for current model version.
3. Service aggregates per-document vectors using configured weights.
4. Neighbor builder uses aggregated vectors for semantic similarity.

## Edge cases
- Missing summary/section vectors should gracefully fallback to available vectors.
- Invalid vectors should be skipped safely.
- Zero/negative weights should not crash and should fallback.

## Acceptance Criteria
- [x] Tree rebuild uses aggregated per-document embeddings.
- [x] Debug neighbor endpoint uses same aggregated embeddings.
- [x] Unit tests validate weighted average and fallback paths.

## Testing
- Unit tests for aggregation.
- Existing tree algorithm tests remain passing.

## Observability
- Existing tree rebuild summary logs remain intact and content-safe.

## Rollout / Rollback
- Rollout by enabling default weights in application config.
- Rollback by setting document weight to 1 and others to 0.

## Security / Privacy
- Workspace-scoped repository query only.
- No sensitive content logging.


## Implementation Notes
- Added `EmbeddingAggregationService` and routed tree rebuild/debug-neighbors through aggregated vectors.
- Added repository query to fetch latest embeddings per `(document_id, target_type, target_id)` within workspace/model scope.
- Added unit tests for weighted blend, fallback to available vectors, and invalid-vector tolerance.
