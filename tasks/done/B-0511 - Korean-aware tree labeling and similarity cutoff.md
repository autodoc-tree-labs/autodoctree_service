# B-0511 - Korean-aware tree labeling and similarity cutoff

## Context
- Current tree labels are generated from English-only tokenization, so Korean titles/body text can be underrepresented.
- Neighbor graph currently keeps top-K neighbors without a minimum similarity threshold, which can over-connect small local datasets and collapse labels into a single cluster.

## Goal
- Improve auto-tree quality for mixed Korean/English documents by introducing Korean-aware tokenization and minimum similarity filtering in neighbor construction.

## Non-goals
- No API contract changes.
- No tenant scope or authorization behavior changes.

## Scope
- Extend tree tokenization to support Unicode letters (including Hangul).
- Adjust token length/filter heuristics to preserve meaningful short Korean tokens.
- Add configurable `tree.neighbor-min-similarity` and apply it in neighbor graph construction.
- Wire the new tree property through config and service flow.
- Add/update unit tests for tokenization and similarity-threshold behavior.

## API / Contracts
- No endpoint path or request/response schema changes.

## DB / Index changes
- None.

## Happy path
1. Documents are embedded as before.
2. Neighbor graph excludes weak links below threshold.
3. Cluster labels include Korean tokens when present.
4. Tree snapshot yields more meaningful node split/labels for mixed-language docs.

## Edge cases
- If no links pass threshold, each doc can become its own component/cluster.
- If tokens are empty after filtering, label falls back to `general`.

## Acceptance Criteria
- [ ] Tree tokenization includes Korean terms.
- [ ] Neighbor builder enforces minimum similarity threshold.
- [ ] Config exposes `tree.neighbor-min-similarity` with sane default.
- [ ] Unit tests cover Korean tokenization + threshold filtering.

## Testing
- Unit tests: `TreeAlgorithmsTest` for tokenizer and neighbor threshold.
- Integration tests: N/A.
- E2E tests: Manual check with mixed Korean/English titles.

## Observability
- Existing tree metrics remain; no sensitive content logging added.

## Rollout / Rollback
- Rollout via deploy/restart with default threshold.
- Rollback by setting threshold to `0.0` and reverting tokenizer changes if needed.

## Security / Privacy
- No tenant isolation changes.
- No additional logging of document content.
