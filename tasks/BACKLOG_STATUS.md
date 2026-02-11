# Backlog Status Audit

Last updated: 2026-02-10

Status legend:
- DONE: implemented and validated in current codebase/tests
- PARTIAL: implemented with reduced scope or missing acceptance details
- TODO: not implemented

## Infra (I)
| Ticket | Status | Notes |
|---|---|---|
| I-0101 | DONE | Monorepo scaffold, Gradle multi-module, web apps, workspace config complete |
| I-0102 | DONE | Docker Compose for Postgres/OpenSearch/Redis/MinIO + healthchecks |
| I-0103 | DONE | CI workflow for services/web + builds/tests/lint/e2e smoke |
| I-0104 | DONE | `docs/DEV_SETUP.md` and `tools/http/*.http` smoke scripts added |
| I-0105 | DONE | JSON structured logging + worker metrics + sensitive-log guard tests added |
| I-0106 | DONE | Config/env template + feature flags baseline added |
| I-0107 | DONE | Local MinIO endpoint defaults aligned (59000/59001), docs synced, upload error diagnostics improved |

## Backend (B)
| Ticket | Status | Notes |
|---|---|---|
| B-0101 | DONE | services scaffold + `/api/v1/health`, request/trace IDs |
| B-0102 | DONE | Flyway baseline schema with tenant-owned tables/workspace indexes |
| B-0103 | DONE | login/refresh/logout with rotation/revoke |
| B-0104 | DONE | workspace/membership APIs + RBAC |
| B-0105 | DONE | workspace header resolver + fail-closed tenant middleware + metric |
| B-0106 | DONE | Tenant repository static guardrail test enforces workspaceId parameter pattern |
| B-0107 | DONE | audit table/writer + admin audit API |
| B-0110 | DONE | document CRUD/list + pipeline status + optimistic locking |
| B-0111 | DONE | attachment presign/complete with ownership checks and namespacing |
| B-0201 | DONE | outbox events same transaction path in services |
| B-0202 | DONE | retry/backoff/DLQ worker runtime |
| B-0203 | DONE | stage idempotency table + keying + duplicate handling |
| B-0204 | DONE | pipeline status transitions + failure reason |
| B-0205 | DONE | admin jobs list/retry endpoints + audit |
| B-0301 | DONE | Tika extractor now emits quality flags and fails closed on encrypted PDF (`ENCRYPTED_PDF`) |
| B-0302 | DONE | heading/length chunking + overlap + ord preservation |
| B-0303 | DONE | OpenSearch template + versioned index/alias bootstrap + runbook added |
| B-0304 | DONE | Outbox-driven upsert/delete now OpenSearch-backed with idempotent upsert semantics |
| B-0305 | DONE | `/search` BM25 via OpenSearch `simple_query_string` + tenant filter |
| B-0306 | DONE | TenantSearchClient enforces workspace filter and has missing-filter detection tests |
| B-0401 | DONE | embedding provider abstraction + deterministic local stub |
| B-0402 | DONE | embedding worker + storage implemented |
| B-0410 | DONE | Explicit NeighborBuilder (TopK) with metrics (duration/docs/edges) |
| B-0411 | DONE | Explicit clusterer with bounded component splitting and depth-2 node structure |
| B-0412 | DONE | TF-IDF-style labeler module with non-empty/length guardrails |
| B-0413 | DONE | snapshot persistence + active/snapshots/rebuild/activate/lock APIs |
| B-0414 | DONE | rationale JSON generation (keywords/similar_docs/signals) |
| B-0415 | DONE | explain API returns stable schema with fallback |
| B-0501 | DONE | feedback move/rename APIs + feedback/outbox events |
| B-0502 | DONE | immediate move reflection in active snapshot membership |
| B-0503 | DONE | Personalization model adds decayed doc+keyword move signals with overfit guard threshold |
| B-0504 | DONE | stability policy via moved ratio -> ACTIVE/RECOMMENDED |
| B-0505 | DONE | workspace-level debounce/coalesce queue integrated into worker with coalescing test |
| B-0510 | DONE | multi-tenant negative integration suite for doc/list/search/tree/explain/presign/complete/admin |
| B-0511 | DONE | Korean-aware tree tokenization + neighbor similarity cutoff (`tree.neighbor-min-similarity`) |
| B-0702 | DONE | Tree similarity now aggregates DOCUMENT/SUMMARY/SECTION embeddings with configurable weights and tests |

## Web User (U)
| Ticket | Status | Notes |
|---|---|---|
| U-0101 | DONE | scaffold + routing + shared API client |
| U-0102 | DONE | login/session + refresh retry path |
| U-0103 | DONE | workspace selector + header indicator + workspace header injection |
| U-0104 | DONE | inbox list/status/pagination params wired |
| U-0105 | DONE | markdown editor save flow |
| U-0106 | DONE | file picker + drag/drop + progress + retry flow for presign/upload/complete |
| U-0107 | DONE | detail view shows pipeline panel/failure reason and auto-refresh while processing |
| U-0108 | DONE | search UI + results |
| U-0109 | DONE | active tree + node docs list |
| U-0110 | DONE | explain panel |
| U-0111 | DONE | drag-and-drop move + rename with optimistic UI and rollback on failure |
| U-0112 | DONE | snapshots list/apply recommended |
| U-0113 | DONE | global error boundary + status-based API error UX + 5xx retry actions |
| U-0114 | DONE | request_id propagation and safe client logging posture |
| U-0125 | DONE | Playwright smoke flow in CI |

## Web Admin (A)
| Ticket | Status | Notes |
|---|---|---|
| A-0101 | DONE | scaffold + routing |
| A-0102 | DONE | auth gating/workspace switcher emphasis |
| A-0103 | DONE | jobs console + retry |
| A-0104 | DONE | audit log viewer + filter |
| A-0105 | DONE | role change/remove API + admin UI implemented, with tenant-negative tests |
| A-0110 | DONE | dangerous action confirmation with workspace-name typing |
| A-0115 | DONE | admin Playwright smoke in CI |

## Next implementation targets
1. Backlog audit maintenance (keep B-0702 follow-up quality checks)
