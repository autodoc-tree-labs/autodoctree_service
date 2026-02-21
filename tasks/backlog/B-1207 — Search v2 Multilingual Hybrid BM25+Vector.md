# B-1207 — Search v2 Multilingual Hybrid BM25+Vector

## Context
- Current OpenSearch query uses `simple_query_string` over `title^2 + body` with workspace filter.
- Korean morphology quality is limited under current mapping; query like "과학" may miss inflected forms.
- Search lacks vector retrieval and hybrid ranking.
- Operational diagnostics for `0 hit` causes (empty workspace index, alias mismatch, vector unavailable) are limited.

## Goal
- Deliver production-grade multilingual + hybrid search (BM25 + vector + RRF) with tenant-safe filtering, blue/green index migration, and reproducible smoke tests.

## Non-goals
- Replacing OpenSearch backend with another engine.
- Exposing raw body text or sensitive content in logs/debug payloads.

## Scope
- Add OpenSearch template v2 with Korean + multilingual analyzers and optional vector field.
- Upgrade search query DSL to BM25 `multi_match` with workspace filter and fallback behavior.
- Add hybrid mode combining BM25 and kNN (when available) via RRF.
- Include index upsert payload with aggregated document embedding.
- Add search debug diagnostics (safe metadata only).
- Provide blue/green scripts: template/index/reindex/alias swap/validation.
- Add smoke script and unit tests for payload + RRF merge behavior.
- Update README/DEV_SETUP/API docs.

## API / Contracts
- `GET /api/v1/search` supports:
  - `mode`: `bm25` | `hybrid` (default `bm25`)
  - `debug`: boolean (default `false`)
- Response keeps `items` compatibility and adds optional `debug`.

## DB / Index changes
- OpenSearch template version bump and index version bump (`docs-v2-*`).
- No SQL schema change required.

## Happy path
1. Create template v2 and index v2, attach alias.
2. Index documents with `doc_embedding` when available.
3. Search in hybrid mode executes BM25 + vector retrieval under same workspace filter.
4. RRF combines results and returns ranked hits.
5. Debug mode provides non-sensitive diagnostics.

## Edge cases
- ICU plugin unavailable: degrade to standard/asciifolding analyzer path.
- kNN/vector unavailable: degrade to BM25-only without failing request.
- Query embedding timeout/failure: skip vector branch, return BM25 result.
- Workspace has no indexed docs: return `items=[]` plus debug reason metadata.

## Acceptance Criteria
- [ ] Korean token-level search quality improved with nori-backed field.
- [ ] Hybrid mode returns merged results with tenant-safe filters on both branches.
- [ ] Upsert includes document embedding when present; skips safely when absent.
- [ ] Blue/green alias and reindex scripts run locally.
- [ ] `scripts/search-smoke.sh` validates alias/count/"과학"/hybrid behavior.
- [ ] `./gradlew -p services :doc-api:test` passes.

## Testing
- Unit tests for search payload structure, workspace filter enforcement, and RRF merge.
- Smoke tests for OpenSearch alias/template/search behavior.
- Existing tenant isolation tests remain green.

## Observability
- Safe debug payload includes:
  - `workspace_id`, `index_alias`, `resolved_index_name`, `workspace_indexed_doc_count`
  - `search_backend`, `mode`, `lang_detected`, `vector_used`
  - optional top-rank diagnostics (doc id/rank/score only)
- No body/chunk/raw attachment text in logs.

## Rollout / Rollback
- Rollout via template/index v2 and alias swap.
- Rollback by alias swap back to previous versioned index.

## Security / Privacy
- Keep workspace filter mandatory in every query branch.
- Debug fields sanitized to IDs/counts/ranks only.
